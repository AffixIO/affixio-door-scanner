package com.affixio.doorscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.affixio.door.Decision
import com.affixio.door.DoorClient
import com.affixio.door.Receipt
import com.affixio.door.Verdict
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var panel: View
    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var detail: TextView
    private lateinit var doorCode: EditText

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val resetToReady = Runnable { showReady() }
    private var busy = false
    private var tone: ToneGenerator? = null

    // Keyboard-wedge scanners type the barcode followed by Enter.
    private val wedge = StringBuilder()
    private var lastKeyAt = 0L

    private val camera = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { submit(it, "camera") }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ScannerBroadcasts.extract(intent, prefs.extras)?.let { submit(it, "scanner") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        panel = findViewById(R.id.statusPanel)
        title = findViewById(R.id.statusTitle)
        message = findViewById(R.id.statusMessage)
        detail = findViewById(R.id.statusDetail)
        doorCode = findViewById(R.id.doorCode)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull()

        findViewById<Button>(R.id.checkButton).setOnClickListener { submitDoorCode() }
        doorCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitDoorCode(); true } else false
        }
        findViewById<Button>(R.id.cameraButton).setOnClickListener {
            camera.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.camera_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }
        handleScanIntent(intent)
        showReady()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleScanIntent(intent)
    }

    /** DataWedge can also deliver scans by starting this activity. */
    private fun handleScanIntent(intent: Intent?) {
        if (intent?.action == ScannerBroadcasts.ZEBRA_ACTION) {
            ScannerBroadcasts.extract(intent, prefs.extras)?.let { submit(it, "scanner") }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply { prefs.actions.forEach { addAction(it) } }
        // Exported so the device's scanner service can reach it. A forged broadcast can only
        // trigger a check; the API decides whether to admit.
        ContextCompat.registerReceiver(this, scanReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        loadAttestationKey()
    }

    override fun onPause() {
        unregisterReceiver(scanReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        tone?.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (doorCode.hasFocus() || event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyAt > 1000) wedge.clear()
        lastKeyAt = now
        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (wedge.isNotEmpty()) {
                val scanned = wedge.toString()
                wedge.clear()
                submit(scanned, "keyboard")
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val c = event.unicodeChar
        if (c != 0 && !Character.isISOControl(c)) {
            wedge.append(c.toChar())
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun submitDoorCode() {
        val code = doorCode.text.toString()
        doorCode.text.clear()
        submit(code, "door code")
    }

    private fun submit(raw: String, source: String) {
        if (busy) return
        busy = true
        main.removeCallbacks(resetToReady)
        show(R.color.ready, R.color.on_status, getString(R.string.checking), "", "${mask(raw)} via $source")
        val client = DoorClient(prefs.doorConfig(), attestationPublicKey = currentKey())
        io.execute {
            val decision = client.check(raw)
            main.post {
                busy = false
                showDecision(decision, raw, source)
            }
        }
    }

    private fun showDecision(d: Decision, raw: String, source: String) {
        val receiptLine = d.receipt?.let {
            if (it.verified) getString(R.string.receipt_ok) else getString(R.string.receipt_bad, it.reason)
        }
        val info = listOfNotNull("${mask(raw)} via $source", "reason: ${d.reason}", receiptLine).joinToString("\n")
        when (d.verdict) {
            Verdict.ADMIT -> show(R.color.admit, R.color.on_status, getString(R.string.admit), d.message, info)
            Verdict.DENY -> show(R.color.deny, R.color.on_status, getString(R.string.deny), d.message, info)
            Verdict.ALREADY_USED -> show(R.color.used, R.color.on_used, getString(R.string.already_used), d.message, info)
            Verdict.ERROR -> show(R.color.error, R.color.on_status, getString(R.string.error), d.message, info)
        }
        feedback(d.verdict)
        main.postDelayed(resetToReady, if (d.verdict == Verdict.ADMIT) 3000 else 5000)
    }

    private fun showReady() =
        show(R.color.ready, R.color.on_status, getString(R.string.ready), getString(R.string.ready_hint), "")

    private fun show(bg: Int, fg: Int, t: String, m: String, d: String) {
        panel.setBackgroundColor(ContextCompat.getColor(this, bg))
        val c = ContextCompat.getColor(this, fg)
        title.setTextColor(c); message.setTextColor(c); detail.setTextColor(c)
        title.text = t; message.text = m; detail.text = d
    }

    private fun feedback(v: Verdict) {
        val (toneType, ms) = when (v) {
            Verdict.ADMIT -> ToneGenerator.TONE_PROP_ACK to 150
            Verdict.ALREADY_USED -> ToneGenerator.TONE_PROP_NACK to 400
            else -> ToneGenerator.TONE_SUP_ERROR to 600
        }
        tone?.startTone(toneType, ms)
        val vib = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vib.vibrate(ms.toLong())
    }

    /** Fetch the public attestation key once (keyless endpoint) and cache it. */
    private fun loadAttestationKey() {
        if (!prefs.requestReceipt || prefs.attestationKeyHex != null) return
        val client = DoorClient(prefs.doorConfig())
        io.execute {
            val key = runCatching { client.fetchAttestationPublicKey() }.getOrNull() ?: return@execute
            val hex = key.joinToString("") { "%02x".format(it) }
            main.post { prefs.attestationKeyHex = hex }
        }
    }

    private fun currentKey(): ByteArray? =
        prefs.attestationKeyHex?.let { runCatching { Receipt.publicKeyFromHex(it) }.getOrNull() }

    /** Never show a full ticket token or code on screen. */
    private fun mask(raw: String): String {
        val s = raw.trim()
        return when {
            s.length == 6 && s.all { it.isDigit() } -> "code ••••" + s.takeLast(2)
            s.length > 6 -> "pass …" + s.takeLast(5)
            else -> "input"
        }
    }
}
