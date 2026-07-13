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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var saveButton: Button
    private lateinit var statusText: TextView
    private lateinit var windowRow: LinearLayout
    private lateinit var billing: BillingManager

    private var windowS: Int
        get() = getSharedPreferences("rewind", MODE_PRIVATE)
            .getInt("window_s", RecordingService.FREE_WINDOW_S)
        set(value) = getSharedPreferences("rewind", MODE_PRIVATE)
            .edit().putInt("window_s", value).apply()

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
                    .putExtra(RecordingService.EXTRA_WINDOW_S, effectiveWindow())
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
        windowRow = findViewById(R.id.windowRow)

        billing = BillingManager(this) { buildWindowRow() }
        billing.connect()
        buildWindowRow()

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
        saveButton.text = getString(
            R.string.action_save_n, RecordingService.windowLabel(effectiveWindow())
        )
    }

    /** The stored choice, downgraded to free if Pro lapsed. */
    private fun effectiveWindow(): Int =
        if (windowS == RecordingService.FREE_WINDOW_S || Pro.isPro(this)) windowS
        else RecordingService.FREE_WINDOW_S

    private fun buildWindowRow() {
        windowRow.removeAllViews()
        val pro = Pro.isPro(this)
        for (w in RecordingService.WINDOW_CHOICES) {
            val b = Button(this)
            val locked = w != RecordingService.FREE_WINDOW_S && !pro
            b.text = when {
                w >= 60 -> "${w / 60}m" + if (locked) " 🔒" else ""
                else -> "${w}s" + if (locked) " 🔒" else ""
            }
            b.alpha = if (w == effectiveWindow()) 1f else 0.45f
            b.setOnClickListener {
                if (locked) { showPaywall(w); return@setOnClickListener }
                windowS = w
                buildWindowRow()
                refreshUi()
                if (RecordingService.isRunning) {
                    Toast.makeText(this, R.string.window_applies_next, Toast.LENGTH_SHORT).show()
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = 8
            windowRow.addView(b, lp)
        }
    }

    private fun showPaywall(wantedWindow: Int) {
        val price = billing.product?.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
        val message = if (price != null)
            getString(R.string.paywall_body_priced, RecordingService.windowLabel(wantedWindow), price)
        else
            getString(R.string.paywall_body_unpriced, RecordingService.windowLabel(wantedWindow))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.paywall_title)
            .setMessage(message)
            .setPositiveButton(R.string.paywall_cta) { _, _ ->
                if (!billing.launchPurchase()) {
                    Toast.makeText(this, R.string.paywall_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.paywall_later, null)
            .show()
    }
}
