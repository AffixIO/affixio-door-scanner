package com.affixio.door

/** What the scanner read, after clean-up. */
sealed class ScanInput {
    /** A six-digit door code typed in or read from a code. */
    data class DoorCode(val code: String) : ScanInput()

    /** A QR link (https://www.affix-io.com/v/<token> or affix://v/<token>) or a bare token. */
    data class PassLink(val link: String?, val token: String?) : ScanInput()

    data class Invalid(val reason: String) : ScanInput()

    companion object {
        private val DOOR_CODE = Regex("^[0-9]{6}$")
        private val TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")

        /**
         * Classify raw scanner output. Scanners often add a prefix, suffix, CR/LF or
         * Unicode control characters, so those are stripped first.
         */
        fun parse(raw: String?): ScanInput {
            if (raw == null) return Invalid("empty")
            val clean = raw.filter { !it.isISOControl() }.trim()
            if (clean.isEmpty()) return Invalid("empty")
            if (DOOR_CODE.matches(clean)) return DoorCode(clean)

            val lower = clean.lowercase()
            if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("affix://")) {
                val token = clean.substringAfter("/v/", "").substringBefore('?').substringBefore('#').trimEnd('/')
                if (!clean.contains("/v/") || !TOKEN.matches(token)) return Invalid("not_a_pass_link")
                // Send https links as scanned; normalise affix:// deep links to the https form.
                val link = if (lower.startsWith("affix://")) "https://www.affix-io.com/v/$token" else clean
                return PassLink(link = link, token = token)
            }
            if (TOKEN.matches(clean)) return PassLink(link = null, token = clean)
            return Invalid("unrecognised")
        }
    }
}
