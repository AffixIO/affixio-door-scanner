package com.affixio.door

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import java.security.MessageDigest
import java.util.Base64

/** Result of checking the ML-DSA-65 signature on a gate receipt, without calling the API. */
data class ReceiptCheck(val verified: Boolean, val reason: String, val signedAt: String? = null)

/**
 * Offline check of the attestation returned by POST /v1/gate/verify with requestAttestation=true.
 *
 * The API signs `signed_payload`: payload_digest = SHA-256 of the JSON text of signed_payload,
 * and mldsa_signature_b64 is an ML-DSA-65 signature over the 32 raw digest bytes. The JSON text
 * is taken byte for byte from the response body, so no re-serialisation is needed.
 */
object Receipt {
    fun publicKeyFromHex(hex: String): ByteArray = hexToBytes(hex.trim())

    fun check(rawBody: String, publicKey: ByteArray, expectedProofDigest: String? = null): ReceiptCheck {
        return try {
            val root = Json.parseToJsonElement(rawBody).jsonObject
            val att = root["attestation"] as? JsonObject ?: return ReceiptCheck(false, "no_attestation")
            val signedText = JsonSpan.member(rawBody, "signed_payload") ?: return ReceiptCheck(false, "no_signed_payload")
            val signed = Json.parseToJsonElement(signedText).jsonObject
            val digestHex = att.str("payload_digest") ?: return ReceiptCheck(false, "no_payload_digest")
            val sigB64 = att.str("mldsa_signature_b64") ?: return ReceiptCheck(false, "no_signature")
            if (att.str("algorithm") != "ML-DSA-65") return ReceiptCheck(false, "unsupported_algorithm")

            val actual = sha256Hex(signedText.toByteArray(Charsets.UTF_8))
            if (!actual.equals(digestHex, ignoreCase = true)) return ReceiptCheck(false, "digest_mismatch")

            // Bind the signature to this admission, not just to any signed payload.
            if (signed.str("decision") != "yes") return ReceiptCheck(false, "signed_decision_not_yes")
            if (signed.str("mode") != "consume") return ReceiptCheck(false, "signed_mode_not_consume")
            val responseDigest = root.str("proof_digest")
            if (responseDigest != null && signed.str("proof_digest") != responseDigest) {
                return ReceiptCheck(false, "proof_digest_mismatch")
            }
            if (expectedProofDigest != null && signed.str("proof_digest") != expectedProofDigest) {
                return ReceiptCheck(false, "not_this_ticket")
            }

            val ok = verifyMlDsa65(publicKey, hexToBytes(digestHex), Base64.getDecoder().decode(sigB64))
            ReceiptCheck(ok, if (ok) "signature_valid" else "invalid_signature", att.str("signed_at"))
        } catch (e: Exception) {
            ReceiptCheck(false, "malformed_receipt")
        }
    }

    fun verifyMlDsa65(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = MLDSASigner()
        signer.init(false, MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, publicKey))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    private fun JsonObject.str(key: String): String? = (this[key])?.let {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/** Finds the exact source text of a top-level member in a JSON object. */
internal object JsonSpan {
    fun member(json: String, name: String): String? {
        var i = skipWs(json, 0)
        if (i >= json.length || json[i] != '{') return null
        i++
        while (true) {
            i = skipWs(json, i)
            if (i >= json.length) return null
            if (json[i] == '}') return null
            if (json[i] == ',') { i++; continue }
            val keyEnd = endOfValue(json, i)
            val key = Json.parseToJsonElement(json.substring(i, keyEnd)).jsonPrimitive.content
            i = skipWs(json, keyEnd)
            if (i >= json.length || json[i] != ':') return null
            i = skipWs(json, i + 1)
            val end = endOfValue(json, i)
            if (key == name) return json.substring(i, end)
            i = end
        }
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** Index just past the JSON value starting at [start]. */
    private fun endOfValue(s: String, start: Int): Int {
        val c = s[start]
        if (c == '"') {
            var i = start + 1
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i += 2
                    '"' -> return i + 1
                    else -> i++
                }
            }
            error("unterminated string")
        }
        if (c == '{' || c == '[') {
            var depth = 0
            var i = start
            while (i < s.length) {
                when (s[i]) {
                    '"' -> { i = endOfValue(s, i); continue }
                    '{', '[' -> depth++
                    '}', ']' -> { depth--; if (depth == 0) return i + 1 }
                }
                i++
            }
            error("unterminated container")
        }
        var i = start
        while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']' && !s[i].isWhitespace()) i++
        return i
    }
}
