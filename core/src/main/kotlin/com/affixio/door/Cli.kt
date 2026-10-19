package com.affixio.door

/**
 * Terminal version of the door check, handy for testing a pass without a device.
 *
 *   AFFIX_API_KEY=... ./gradlew -q :core:run --args="https://www.affix-io.com/v/<token>"
 *   AFFIX_API_KEY=... ./gradlew -q :core:run --args="123456"
 *
 * Optional: AFFIX_GATE_ID, AFFIX_API_BASE.
 */
fun main(args: Array<String>) {
    val input = args.firstOrNull() ?: run {
        System.err.println("usage: run --args=\"<pass link | token | 6-digit code>\"")
        kotlin.system.exitProcess(2)
    }
    val client = DoorClient(
        DoorConfig(
            baseUrl = System.getenv("AFFIX_API_BASE") ?: "https://api.affix-io.com",
            apiKey = System.getenv("AFFIX_API_KEY") ?: "",
            gateId = System.getenv("AFFIX_GATE_ID") ?: "",
        )
    )
    runCatching { client.fetchAttestationPublicKey() }
        .onFailure { System.err.println("warning: could not fetch attestation public key: ${it.message}") }
    val d = client.check(input)
    println("${d.verdict}  ${d.message}  (${d.reason})")
    d.receipt?.let { println("receipt: ${if (it.verified) "signature verified offline" else "NOT verified"} (${it.reason})") }
    kotlin.system.exitProcess(if (d.verdict == Verdict.ADMIT) 0 else 1)
}
