package app.lrtelecom.rewind

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a rolling in-memory buffer of the last ~12 seconds of encoded screen
 * video (H.264 via MediaCodec surface input + MediaProjection virtual display).
 * On SAVE, the newest 10 seconds are muxed to an MP4 and dropped into the
 * gallery (Movies/Rewind). Nothing is ever written to disk until the user
 * explicitly saves.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "app.lrtelecom.rewind.START"
        const val ACTION_SAVE = "app.lrtelecom.rewind.SAVE"
        const val ACTION_STOP = "app.lrtelecom.rewind.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_WINDOW_S = "windowS"
        const val BROADCAST_STATE = "app.lrtelecom.rewind.STATE"

        /** "Save last N seconds" — 10 free; 30/60/120 are Rewind Pro. */
        val WINDOW_CHOICES = intArrayOf(10, 30, 60, 120)
        const val FREE_WINDOW_S = 10

        fun windowLabel(s: Int): String = when {
            s >= 120 -> "${s / 60} minutes"
            s == 60 -> "1 minute"
            else -> "$s seconds"
        }

        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "rewind"
        private const val NOTIF_ID = 1
        private const val SAVED_NOTIF_ID = 2
    }

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var drainThread: Thread? = null
    private var handlerThread: HandlerThread? = null

    @Volatile
    private var draining = false

    private val lock = Any()
    private val buffer = ArrayDeque<Sample>()

    @Volatile
    private var saveMs = 10_000L      // chosen replay window
    private val keepMs get() = saveMs + 2_000L

    @Volatile
    private var outputFormat: MediaFormat? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked screen capture from the system UI.
            stopEverything()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_SAVE -> Thread { saveClip() }.start()
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (isRunning) return
        saveMs = intent.getIntExtra(EXTRA_WINDOW_S, FREE_WINDOW_S) * 1000L
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            stopSelf(); return
        }
        val width = intent.getIntExtra(EXTRA_WIDTH, 720)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1560)
        val dpi = intent.getIntExtra(EXTRA_DPI, 320)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, resultData) ?: run { stopSelf(); return }
        projection = mp

        handlerThread = HandlerThread("rewind-projection").also { it.start() }
        val handler = Handler(handlerThread!!.looper)
        mp.registerCallback(projectionCallback, handler)

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                // Longer windows use a lower bitrate so the in-memory buffer
                // stays reasonable (2 min @ 4 Mbps ≈ 60 MB).
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    when { saveMs >= 120_000L -> 4_000_000; saveMs >= 60_000L -> 5_000_000; else -> 6_000_000 }
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Emit frames even when the screen is static so saves always
                // cover the full window.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec

            virtualDisplay = mp.createVirtualDisplay(
                "rewind", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, handler
            )
        } catch (e: Exception) {
            stopEverything()
            stopSelf()
            return
        }

        draining = true
        drainThread = Thread { drainLoop() }.also { it.start() }
        isRunning = true
        sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName))
    }

    private fun drainLoop() {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (draining) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                idx >= 0 -> {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val arr = ByteArray(info.size)
                            buf.get(arr)
                            synchronized(lock) {
                                buffer.addLast(Sample(arr, info.presentationTimeUs, info.flags))
                                prune()
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    /** Drop old samples, but never leave the buffer starting mid-GOP: the head
     *  segment is only dropped once the NEXT keyframe is itself older than the
     *  cutoff, so the buffer always starts at a keyframe and always covers the
     *  full keep-window. */
    private fun prune() {
        val last = buffer.lastOrNull() ?: return
        val cutoff = last.ptsUs - keepMs * 1000
        while (true) {
            var seenFirstKey = false
            var secondKeyIdx = -1
            for (i in buffer.indices) {
                if ((buffer[i].flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    if (!seenFirstKey) seenFirstKey = true
                    else { secondKeyIdx = i; break }
                }
            }
            if (secondKeyIdx == -1) return
            if (buffer[secondKeyIdx].ptsUs > cutoff) return
            repeat(secondKeyIdx) { buffer.removeFirst() }
        }
    }

    private fun saveClip() {
        val fmt = outputFormat
        val snapshot = synchronized(lock) { buffer.toList() }
        if (fmt == null || snapshot.size < 2) {
            toast(getString(R.string.nothing_yet))
            return
        }
        val endPts = snapshot.last().ptsUs
        val wantStart = endPts - saveMs * 1000
        var startIdx = -1
        for (i in snapshot.indices) {
            val s = snapshot[i]
            if ((s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && s.ptsUs <= wantStart) startIdx = i
        }
        if (startIdx == -1) {
            startIdx = snapshot.indexOfFirst { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
        }
        if (startIdx == -1) {
            toast(getString(R.string.nothing_yet))
            return
        }
        val clip = snapshot.subList(startIdx, snapshot.size)

        try {
            val tmp = File(cacheDir, "rewind_${System.currentTimeMillis()}.mp4")
            val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(fmt)
            muxer.start()
            val base = clip.first().ptsUs
            val info = MediaCodec.BufferInfo()
            for (s in clip) {
                info.set(0, s.data.size, s.ptsUs - base, s.flags)
                muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
            muxer.release()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Rewind_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Rewind")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)!!.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            tmp.delete()

            toast(getString(R.string.saved_toast))
            notifySaved(uri)
        } catch (e: Exception) {
            toast(getString(R.string.save_failed))
        }
    }

    private fun notifySaved(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(this, 3, view, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rewind)
            .setContentTitle(getString(R.string.saved_title))
            .setContentText(getString(R.string.saved_body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SAVED_NOTIF_ID, n)
    }

    private fun toast(msg: String) {
        Handler(mainLooper).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun buildNotification(): Notification {
        fun servicePI(action: String, req: Int): PendingIntent =
            PendingIntent.getService(
                this, req,
                Intent(this, RecordingService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE
            )

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rewind)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_body))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                0,
                getString(R.string.action_save_n, windowLabel((saveMs / 1000L).toInt())),
                servicePI(ACTION_SAVE, 1)
            )
            .addAction(0, getString(R.string.action_stop), servicePI(ACTION_STOP, 2))
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun stopEverything() {
        draining = false
        drainThread?.join(500)
        drainThread = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        synchronized(lock) { buffer.clear() }
        outputFormat = null
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName))
    }

    override fun onDestroy() {
        if (isRunning) stopEverything()
        super.onDestroy()
    }
}
