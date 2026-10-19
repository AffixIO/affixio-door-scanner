package com.affixio.doorscanner

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Hardware scanners on Android handhelds can send each scan to an app as a broadcast intent.
 * Action names and extra keys differ by maker and firmware, so both lists are editable in Settings.
 * Check the values against your device's scanner settings.
 */
object ScannerBroadcasts {
    /** Our own action, used by the Zebra DataWedge profile this app can create. */
    const val ZEBRA_ACTION = "com.affixio.doorscanner.SCAN"

    val DEFAULT_ACTIONS = listOf(
        ZEBRA_ACTION,                                    // Zebra DataWedge (Intent output)
        "com.honeywell.decode.intent.action.EDIT_DATA",  // Honeywell (Data Intent)
        "com.datalogic.decodewedge.decode_action",       // Datalogic (Intent Wedge)
        "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED",   // Sunmi
        "android.intent.ACTION_DECODE_DATA",             // Urovo
        "nlscan.action.SCANNER_RESULT",                  // Newland
    )

    val DEFAULT_EXTRAS = listOf(
        "com.symbol.datawedge.data_string",                 // Zebra
        "data",                                             // Honeywell, Sunmi
        "com.datalogic.decode.intentwedge.barcode_string",  // Datalogic
        "barcode_string",                                   // Urovo
        "SCAN_BARCODE1",                                    // Newland
    )

    /** First non-empty string found under any configured extra key. */
    fun extract(intent: Intent, extraKeys: List<String>): String? {
        for (key in extraKeys) {
            val v = intent.getStringExtra(key)
            if (!v.isNullOrBlank()) return v
            val bytes = intent.getByteArrayExtra(key)
            if (bytes != null && bytes.isNotEmpty()) return String(bytes, Charsets.UTF_8)
        }
        return null
    }

    /**
     * Ask Zebra DataWedge (6.9 or later) to create a profile for this app that sends scans as a
     * broadcast with [ZEBRA_ACTION] and turns keystroke output off. Harmless on non-Zebra devices.
     */
    fun createDataWedgeProfile(context: Context) {
        val barcode = Bundle().apply {
            putString("PLUGIN_NAME", "BARCODE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply { putString("scanner_input_enabled", "true") })
        }
        val intentOut = Bundle().apply {
            putString("PLUGIN_NAME", "INTENT")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("intent_output_enabled", "true")
                putString("intent_action", ZEBRA_ACTION)
                putString("intent_delivery", "2") // 2 = broadcast
            })
        }
        val keystroke = Bundle().apply {
            putString("PLUGIN_NAME", "KEYSTROKE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply { putString("keystroke_output_enabled", "false") })
        }
        val app = Bundle().apply {
            putString("PACKAGE_NAME", context.packageName)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }
        val profile = Bundle().apply {
            putString("PROFILE_NAME", "AffixIODoorScanner")
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
            putParcelableArray("APP_LIST", arrayOf(app))
            putParcelableArrayList("PLUGIN_CONFIG", arrayListOf(barcode, intentOut, keystroke))
        }
        context.sendBroadcast(Intent("com.symbol.datawedge.api.ACTION").apply {
            putExtra("com.symbol.datawedge.api.SET_CONFIG", profile)
        })
    }
}
