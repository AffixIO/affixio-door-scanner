package com.affixio.door

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses a real gate/verify response captured from the live API and the published public key. */
class ReceiptTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private val body = res("gate-admit.json")
    private val key = Receipt.publicKeyFromHex(
        Json.parseToJsonElement(res("attestation-public-key.json")).jsonObject["public_key_hex"]!!.jsonPrimitive.content
    )

    @Test fun realReceiptVerifies() {
        val r = Receipt.check(body, key)
        assertTrue(r.verified, r.reason)
        assertEquals("signature_valid", r.reason)
    }

    @Test fun editedPayloadFails() {
        val edited = body.replace("\"decision\":\"yes\"", "\"decision\":\"no\"")
        assertFalse(Receipt.check(edited, key).verified)
    }

    @Test fun wrongTicketFails() {
        val r = Receipt.check(body, key, expectedProofDigest = "0".repeat(64))
        assertEquals("not_this_ticket", r.reason)
    }

    @Test fun wrongKeyFails() {
        val other = key.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertFalse(Receipt.check(body, other).verified)
    }

    @Test fun spanMatchesExactText() {
        val span = JsonSpan.member("""{"a":"x}\"","signed_payload":{"b":[1,{"c":"}"}]},"z":1}""", "signed_payload")
        assertEquals("""{"b":[1,{"c":"}"}]}""", span)
    }
}
