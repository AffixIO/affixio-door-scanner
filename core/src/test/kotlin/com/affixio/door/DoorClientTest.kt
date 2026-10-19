package com.affixio.door

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoorClientTest {
    private val link = "https://www.affix-io.com/v/862a4a2ce4ad2ca2e82c1974cdabba82"
    private val gateAdmit = javaClass.getResource("/gate-admit.json")!!.readText()
    private val carrier = """{"id":"lin_1","kind":"link","proof_digest":"716ffed53ef5bdbc1552ab65ae077b16145b9f6d831478ed667a5b2cac72a773","proof_ref":"pf_3fb275d8-933b-40bf-9e21-4ae6170dd2d9","circuit_id":"ticket_age_entry","gate_id":"door-scanner-test"}"""

    data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private class Fake(vararg replies: Pair<String, HttpResult>) : Transport {
        val queue = replies.toMutableList()
        val calls = mutableListOf<Call>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
            calls += Call(method, url, headers, body)
            val (path, r) = queue.removeAt(0)
            assertTrue(url.endsWith(path), "expected $path, got $url")
            return r
        }
    }

    private fun client(t: Transport, key: ByteArray? = null) =
        DoorClient(DoorConfig(apiKey = "test-key"), t, key)

    @Test fun qrAdmitThenSignedReceipt() {
        val pk = Receipt.publicKeyFromHex(Regex("\"public_key_hex\":\"([0-9a-f]+)\"")
            .find(javaClass.getResource("/attestation-public-key.json")!!.readText())!!.groupValues[1])
        val t = Fake(
            "/v1/link/verify" to HttpResult(200, """{"allow":true,"reason_code":"VALID","carrier":$carrier}""", null),
            "/v1/gate/verify" to HttpResult(200, gateAdmit, null),
        )
        val d = client(t, pk).check(link)
        assertEquals(Verdict.ADMIT, d.verdict)
        assertEquals(true, d.receipt?.verified)
        assertEquals("""{"link":"$link"}""", t.calls[0].body)
        assertEquals("test-key", t.calls[0].headers["X-API-Key"])
        val gateBody = t.calls[1].body!!
        assertTrue(gateBody.contains("\"proof\":\"pf_3fb275d8-933b-40bf-9e21-4ae6170dd2d9\""))
        assertTrue(gateBody.contains("\"circuit_id\":\"ticket_age_entry\""))
        assertTrue(gateBody.contains("\"gate_id\":\"door-scanner-test\""))
        assertTrue(gateBody.contains("\"mode\":\"consume\""))
        assertTrue(t.calls[1].headers.containsKey("Idempotency-Key"))
    }

    @Test fun qrAlreadyUsed() {
        val t = Fake("/v1/link/verify" to HttpResult(409, """{"allow":false,"reason_code":"used","spent":true}""", null))
        assertEquals(Verdict.ALREADY_USED, client(t).check(link).verdict)
    }

    @Test fun gateDoubleSpend() {
        val t = Fake(
            "/v1/link/verify" to HttpResult(200, """{"allow":true,"reason_code":"VALID","carrier":$carrier}""", null),
            "/v1/gate/verify" to HttpResult(409, """{"error":"double_spend_detected","allow":false}""", null),
        )
        val d = client(t).check(link)
        assertEquals(Verdict.ALREADY_USED, d.verdict)
        assertEquals("double_spend_detected", d.reason)
    }

    @Test fun doorCodeAdmit() {
        val otpCarrier = carrier.replace("\"kind\":\"link\"", "\"kind\":\"otp\"")
        val t = Fake(
            "/v1/otp/verify" to HttpResult(200, """{"allow":true,"reason_code":"OTP_OK","carrier":$otpCarrier}""", null),
            "/v1/gate/verify" to HttpResult(200, gateAdmit, null),
        )
        assertEquals(Verdict.ADMIT, client(t).check("123456").verdict)
        assertEquals("""{"otp":"123456"}""", t.calls[0].body)
    }

    @Test fun doorCodeUnknown() {
        val t = Fake("/v1/otp/verify" to HttpResult(400, """{"allow":false,"reason_code":"invalid_otp"}""", null))
        assertEquals(Verdict.DENY, client(t).check("000000").verdict)
    }

    @Test fun expired() {
        val t = Fake("/v1/link/verify" to HttpResult(400, """{"allow":false,"reason_code":"expired"}""", null))
        val d = client(t).check(link)
        assertEquals(Verdict.DENY, d.verdict)
        assertEquals("Expired.", d.message)
    }

    @Test fun badKeyIsError() {
        val t = Fake("/v1/link/verify" to HttpResult(401, """{"error":"unauthorized"}""", null))
        assertEquals(Verdict.ERROR, client(t).check(link).verdict)
    }

    @Test fun rateLimited() {
        val t = Fake("/v1/link/verify" to HttpResult(429, "{}", "3"))
        val d = client(t).check(link)
        assertEquals(Verdict.ERROR, d.verdict)
        assertEquals("Busy, scan again in 3s.", d.message)
    }

    @Test fun offlineNeverAdmits() {
        val t = Transport { _, _, _, _ -> throw IOException("no route") }
        assertEquals(Verdict.ERROR, client(t).check(link).verdict)
    }

    @Test fun junkIsDeniedWithoutNetwork() {
        val t = Fake()
        assertEquals(Verdict.DENY, client(t).check("hello").verdict)
        assertTrue(t.calls.isEmpty())
    }
}
