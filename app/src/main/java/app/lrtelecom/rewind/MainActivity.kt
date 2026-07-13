package app.lrtelecom.rewind

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var saveButton: Button
    private lateinit var statusText: TextView

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshUi()
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val (w, h) = captureSize()
                val i = Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(RecordingService.EXTRA_RESULT_DATA, data)
                    .putExtra(RecordingService.EXTRA_WIDTH, w)
                    .putExtra(RecordingService.EXTRA_HEIGHT, h)
                    .putExtra(RecordingService.EXTRA_DPI, resources.displayMetrics.densityDpi)
                ContextCompat.startForegroundService(this, i)
            }
            // The service broadcasts its state once it's actually up.
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toggleButton = findViewById(R.id.toggleButton)
        saveButton = findViewById(R.id.saveButton)
        statusText = findViewById(R.id.statusText)

        toggleButton.setOnClickListener {
            if (RecordingService.isRunning) {
                startService(
                    Intent(this, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_STOP)
                )
            } else {
                askThenStart()
            }
        }
        saveButton.setOnClickListener {
            startService(
                Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_SAVE)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(RecordingService.BROADCAST_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    private fun askThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startProjection()
    }

    private fun startProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** Real screen size, scaled down so the long edge is at most 1280px
     *  (keeps the rolling buffer small and every phone's encoder happy). */
    private fun captureSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var w: Int
        var h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            w = p.x; h = p.y
        }
        val longEdge = max(w, h)
        if (longEdge > 1280) {
            val scale = 1280f / longEdge
            w = (w * scale).roundToInt()
            h = (h * scale).roundToInt()
        }
        // Encoders want even dimensions.
        return Pair(w and 1.inv(), h and 1.inv())
    }

    private fun refreshUi() {
        val running = RecordingService.isRunning
        toggleButton.text = getString(if (running) R.string.stop_watching else R.string.start_watching)
        statusText.text = getString(if (running) R.string.status_on else R.string.status_off)
        saveButton.isEnabled = running
        saveButton.alpha = if (running) 1f else 0.4f
    }
}
