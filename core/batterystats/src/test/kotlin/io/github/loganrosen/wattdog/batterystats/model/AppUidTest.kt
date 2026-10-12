package io.github.loganrosen.wattdog.batterystats.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUidTest {
    @Test
    fun `parses and round-trips batterystats uid forms`() {
        val cases = mapOf(
            "1000" to 1000,
            "u0a316" to 10316,
            "u10a622" to 1010622,
            "u0i12" to 99012,
            "u0i0" to 99000,
            "u0ai3" to 90003,
            "u10s1000" to 1001000,
        )
        for ((text, raw) in cases) {
            val uid = AppUid.parse(text)
            assertEquals(raw, uid?.raw, text)
            assertEquals(text, uid.toString(), text)
        }
    }

    @Test
    fun `classifies uids`() {
        assertTrue(AppUid(10316).isApplication)
        assertFalse(AppUid(1000).isApplication)
        assertTrue(AppUid(99012).isIsolated)
        assertEquals(10, AppUid(1010622).userId)
    }

    @Test
    fun `rejects garbage`() {
        assertNull(AppUid.parse("u0x5"))
        assertNull(AppUid.parse("com.example"))
    }

    @Test
    fun `names well-known system uids in any user`() {
        assertEquals("Audio server", SystemUids.nameFor(AppUid(1041)))
        assertEquals("Android System", SystemUids.nameFor(AppUid(1001000)))
        assertNull(SystemUids.nameFor(AppUid(10316)))
    }
}
