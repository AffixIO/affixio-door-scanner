package com.affixio.door

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScanInputTest {
    private val token = "862a4a2ce4ad2ca2e82c1974cdabba82"

    @Test fun doorCode() {
        assertEquals(ScanInput.DoorCode("123456"), ScanInput.parse(" 123456\r\n"))
    }

    @Test fun httpsLink() {
        val r = ScanInput.parse("https://www.affix-io.com/v/$token\n")
        assertEquals(ScanInput.PassLink("https://www.affix-io.com/v/$token", token), r)
    }

    @Test fun deepLinkIsNormalised() {
        val r = ScanInput.parse("affix://v/$token")
        assertEquals(ScanInput.PassLink("https://www.affix-io.com/v/$token", token), r)
    }

    @Test fun bareToken() {
        assertEquals(ScanInput.PassLink(null, token), ScanInput.parse(token))
    }

    @Test fun controlCharactersStripped() {
        assertIs<ScanInput.PassLink>(ScanInput.parse("\u0002https://www.affix-io.com/v/$token\u0003"))
    }

    @Test fun rejectsOtherUrls() {
        assertIs<ScanInput.Invalid>(ScanInput.parse("https://example.org/page"))
    }
    @Test fun rejectsShortNumbers() {
        assertIs<ScanInput.Invalid>(ScanInput.parse("12345"))
    }
    @Test fun rejectsEmpty() {
        assertIs<ScanInput.Invalid>(ScanInput.parse("  "))
    }
}
