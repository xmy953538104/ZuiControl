package com.zui.zuicontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppOptConfigTest {
    @Test
    fun downloadMimeTypeDoesNotInviteTxtSuffix() {
        assertEquals("application/octet-stream", AppOptConfig.DOWNLOAD_MIME_TYPE)
    }

    @Test
    fun parsesAndSerializesStrictWholePackageRules() {
        val rules = AppOptConfig.parse(
            """
            # editable
            com.example.one=0-1
            com.example.two=5-7
            """.trimIndent(),
        )

        assertEquals(2, rules.size)
        assertEquals(
            "com.example.one=0-1;com.example.two=5-7",
            AppOptConfig.payload(rules),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AppOptConfig.parse("com.example.one=0-1\ncom.example.one=0-4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppOptConfig.parse("com.example.one{Thread}=5-7")
        }
    }
}
