package com.affixio.doorscanner

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)
        val apiBase = findViewById<EditText>(R.id.apiBase)
        val apiKey = findViewById<EditText>(R.id.apiKey)
        val gateId = findViewById<EditText>(R.id.gateId)
        val circuitId = findViewById<EditText>(R.id.circuitId)
        val receipt = findViewById<CheckBox>(R.id.requestReceipt)
        val actions = findViewById<EditText>(R.id.actions)
        val extras = findViewById<EditText>(R.id.extras)

        apiBase.setText(prefs.apiBase)
        apiKey.setText(prefs.apiKey)
        gateId.setText(prefs.gateId)
        circuitId.setText(prefs.circuitId)
        receipt.isChecked = prefs.requestReceipt
        actions.setText(prefs.actions.joinToString("\n"))
        extras.setText(prefs.extras.joinToString("\n"))

        findViewById<Button>(R.id.save).setOnClickListener {
            val base = apiBase.text.toString().trim()
            if (!base.startsWith("https://")) {
                apiBase.error = "Must start with https://"
                return@setOnClickListener
            }
            if (base != prefs.apiBase) prefs.attestationKeyHex = null
            prefs.apiBase = base
            prefs.apiKey = apiKey.text.toString()
            prefs.gateId = gateId.text.toString()
            prefs.circuitId = circuitId.text.toString()
            prefs.requestReceipt = receipt.isChecked
            prefs.actions = actions.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            prefs.extras = extras.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.datawedge).setOnClickListener {
            ScannerBroadcasts.createDataWedgeProfile(this)
            Toast.makeText(this, R.string.datawedge_sent, Toast.LENGTH_LONG).show()
        }
    }
}
