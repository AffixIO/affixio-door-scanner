package com.affixio.doorscanner

import android.content.Context
import com.affixio.door.DoorConfig

/** Settings stored in the app's private SharedPreferences. Nothing is sent anywhere except the API. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("door_scanner", Context.MODE_PRIVATE)

    var apiBase: String
        get() = sp.getString("api_base", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_API_BASE
        set(v) = sp.edit().putString("api_base", v.trim()).apply()
    var apiKey: String
        get() = sp.getString("api_key", null) ?: BuildConfig.DEFAULT_API_KEY
        set(v) = sp.edit().putString("api_key", v.trim()).apply()
    var gateId: String
        get() = sp.getString("gate_id", null) ?: BuildConfig.DEFAULT_GATE_ID
        set(v) = sp.edit().putString("gate_id", v.trim()).apply()
    var circuitId: String
        get() = sp.getString("circuit_id", null)?.takeIf { it.isNotBlank() } ?: "ticket_age_entry"
        set(v) = sp.edit().putString("circuit_id", v.trim()).apply()
    var requestReceipt: Boolean
        get() = sp.getBoolean("request_receipt", true)
        set(v) = sp.edit().putBoolean("request_receipt", v).apply()
    var actions: List<String>
        get() = lines("actions", ScannerBroadcasts.DEFAULT_ACTIONS)
        set(v) = sp.edit().putString("actions", v.joinToString("\n")).apply()
    var extras: List<String>
        get() = lines("extras", ScannerBroadcasts.DEFAULT_EXTRAS)
        set(v) = sp.edit().putString("extras", v.joinToString("\n")).apply()
    /** Cached ML-DSA-65 public key (hex) from GET /v1/attestation/public-key. Public data. */
    var attestationKeyHex: String?
        get() = sp.getString("attestation_key_hex", null)
        set(v) = sp.edit().putString("attestation_key_hex", v).apply()

    private fun lines(key: String, default: List<String>): List<String> =
        sp.getString(key, null)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default

    fun doorConfig() = DoorConfig(
        baseUrl = apiBase,
        apiKey = apiKey,
        gateId = gateId,
        defaultCircuitId = circuitId,
        requestAttestation = requestReceipt,
        userAgent = "affixio-door-scanner-android/${BuildConfig.VERSION_NAME}",
    )
}
