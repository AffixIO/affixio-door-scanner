package com.affixio.door

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

enum class Verdict { ADMIT, DENY, ALREADY_USED, ERROR }

data class Decision(
    val verdict: Verdict,
    /** Machine-readable reason, e.g. VALID, used, expired, double_spend_detected. */
    val reason: String,
    /** Short text for door staff. */
    val message: String,
    val receipt: ReceiptCheck? = null,
)

data class DoorConfig(
    val baseUrl: String = "https://api.affix-io.com",
    val apiKey: String,
    /** Optional. When blank, the gate_id bound to the ticket is used. */
    val gateId: String = "",
    /** Used only when the ticket does not say which circuit it was proved with. */
    val defaultCircuitId: String = "ticket_age_entry",
    /** Ask for an ML-DSA-65 signed receipt on admit and check it on the device. */
    val requestAttestation: Boolean = true,
    val userAgent: String = "affixio-door-scanner/0.1",
)

/**
 * Door check against the AffixIO REST API (openapi 1.8.0, quickstart section 5):
 *
 *   QR link:   POST /v1/link/verify {link}  ->  POST /v1/gate/verify {circuit_id, proof, gate_id, mode: consume}
 *   Door code: POST /v1/otp/verify  {otp}   ->  POST /v1/gate/verify {...}
 *
 * The gate consume spends the proof, so the first scan admits and any later scan of the same
 * ticket (QR, screenshot or door code) is refused. This needs a network connection.
 */
class DoorClient(
    private val config: DoorConfig,
    private val transport: Transport = UrlConnectionTransport(),
    /** ML-DSA-65 public key (1952 bytes). Null means receipts are not checked offline. */
    var attestationPublicKey: ByteArray? = null,
) {
    private val base = config.baseUrl.trimEnd('/')

    fun check(raw: String?): Decision {
        if (config.apiKey.isBlank()) return Decision(Verdict.ERROR, "no_api_key", "Add an API key in Settings.")
        return try {
            when (val input = ScanInput.parse(raw)) {
                is ScanInput.Invalid -> Decision(Verdict.DENY, input.reason, "Not an AffixIO pass or door code.")
                is ScanInput.DoorCode -> {
                    val body = buildJsonObject { put("otp", input.code) }
                    carrierThenGate(post("/v1/otp/verify", body), isDoorCode = true)
                }
                is ScanInput.PassLink -> {
                    val body = buildJsonObject {
                        if (input.link != null) put("link", input.link) else put("token", input.token)
                    }
                    carrierThenGate(post("/v1/link/verify", body), isDoorCode = false)
                }
            }
        } catch (e: IOException) {
            Decision(Verdict.ERROR, "network", "No connection. Cannot confirm single use, do not admit.")
        }
    }

    /** GET /v1/attestation/public-key. Keyless. Call once and cache the result. */
    fun fetchAttestationPublicKey(): ByteArray {
        val r = transport.request("GET", "$base/v1/attestation/public-key", baseHeaders(withKey = false), null)
        if (r.status != 200) throw IOException("public key HTTP ${r.status}")
        val obj = Json.parseToJsonElement(r.body).jsonObject
        require(obj.s("algorithm") == "ML-DSA-65") { "unexpected algorithm" }
        val key = Receipt.publicKeyFromHex(obj.s("public_key_hex") ?: error("no public_key_hex"))
        attestationPublicKey = key
        return key
    }

    private fun carrierThenGate(r: HttpResult, isDoorCode: Boolean): Decision {
        common(r)?.let { return it }
        val obj = parse(r.body)
        val reason = obj?.s("reason_code") ?: obj?.s("error") ?: "http_${r.status}"
        when (r.status) {
            200 -> if (obj?.b("allow") != true) return Decision(Verdict.DENY, reason, "Not valid.")
            409 -> return Decision(Verdict.ALREADY_USED, reason, "Already used. Do not admit.")
            400 -> return Decision(Verdict.DENY, reason, denyText(reason, isDoorCode))
            else -> return Decision(Verdict.ERROR, reason, "Unexpected reply (HTTP ${r.status}).")
        }
        val carrier = obj["carrier"] as? JsonObject
            ?: return Decision(Verdict.ERROR, "no_carrier", "Reply had no ticket details.")
        val proof = carrier.s("proof_ref") ?: carrier.s("proof_digest")
            ?: return Decision(Verdict.DENY, "unbound_carrier", "Pass is not linked to a ticket.")
        val circuit = carrier.s("circuit_id") ?: config.defaultCircuitId
        val gate = config.gateId.ifBlank { carrier.s("gate_id") ?: "" }
        return gate(proof, circuit, gate, carrier.s("proof_digest"))
    }

    private fun gate(proof: String, circuitId: String, gateId: String, proofDigest: String?): Decision {
        val body = buildJsonObject {
            put("circuit_id", circuitId)
            put("proof", proof)
            if (gateId.isNotBlank()) put("gate_id", gateId)
            put("mode", "consume")
            if (config.requestAttestation) put("requestAttestation", true)
        }
        val r = post("/v1/gate/verify", body, idempotencyKey = UUID.randomUUID().toString())
        common(r)?.let { return it }
        val obj = parse(r.body)
        val reason = obj?.s("reason_code") ?: obj?.s("error") ?: "http_${r.status}"
        return when {
            r.status == 200 && obj?.b("allow") == true -> {
                val key = attestationPublicKey
                val receipt = if (config.requestAttestation && key != null) Receipt.check(r.body, key, proofDigest) else null
                Decision(Verdict.ADMIT, reason, "Admit.", receipt)
            }
            r.status == 409 -> Decision(Verdict.ALREADY_USED, obj?.s("error") ?: reason, "Already used. Do not admit.")
            r.status == 200 || r.status == 400 || r.status == 403 || r.status == 422 ->
                Decision(Verdict.DENY, reason, "Not valid.")
            else -> Decision(Verdict.ERROR, reason, "Unexpected reply (HTTP ${r.status}).")
        }
    }

    /** Status codes handled the same way on every call. */
    private fun common(r: HttpResult): Decision? = when (r.status) {
        401 -> Decision(Verdict.ERROR, "unauthorized", "API key missing or not accepted. Check Settings.")
        429 -> Decision(Verdict.ERROR, "rate_limited", "Busy, scan again" + (r.retryAfter?.let { " in ${it}s" } ?: "") + ".")
        in 500..599 -> Decision(Verdict.ERROR, "server_${r.status}", "Service unavailable. Do not admit; try again.")
        else -> null
    }

    private fun denyText(reason: String, isDoorCode: Boolean) = when (reason) {
        "expired" -> "Expired."
        "invalid_otp" -> "Code not recognised, expired or already used."
        "unknown_token", "not_found" -> "Pass not recognised."
        else -> if (isDoorCode) "Code not valid." else "Pass not valid."
    }

    private fun post(path: String, body: JsonObject, idempotencyKey: String? = null): HttpResult {
        val headers = baseHeaders(withKey = true).toMutableMap()
        headers["Content-Type"] = "application/json"
        if (idempotencyKey != null) headers["Idempotency-Key"] = idempotencyKey
        return transport.request("POST", base + path, headers, body.toString())
    }

    private fun baseHeaders(withKey: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", config.userAgent)
        if (withKey) put("X-API-Key", config.apiKey)
    }

    private fun parse(text: String): JsonObject? =
        runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonObject.s(key: String): String? = prim(this[key])?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.b(key: String): Boolean? = prim(this[key])?.booleanOrNull
    private fun prim(e: JsonElement?): JsonPrimitive? = e as? JsonPrimitive
}
