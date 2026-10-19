package com.affixio.door

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val status: Int, val body: String, val retryAfter: String?)

/** Minimal HTTP transport so the core has no Android or networking library dependency. */
fun interface Transport {
    @Throws(IOException::class)
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult
}

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : Transport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResult(status, text, conn.getHeaderField("Retry-After"))
        } finally {
            conn.disconnect()
        }
    }
}
