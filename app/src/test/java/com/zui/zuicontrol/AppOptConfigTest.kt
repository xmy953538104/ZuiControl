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
    fun parsesAndSerializesPackageFallbackAndThreadRules() {
        val rules = AppOptConfig.parse(
            """
            # editable
            com.example.one=2-6
            com.example.one{RenderThread}=2-4
            com.example.one{GameThread}=7
            com.example.two=3-6
            """.trimIndent(),
        )

        assertEquals(2, rules.size)
        assertEquals(2, rules["com.example.one"]?.threadRules?.size)
        assertEquals(
            "com.example.one=2-6;com.example.one{RenderThread}=2-4;" +
                "com.example.one{GameThread}=7;com.example.two=3-6",
            AppOptConfig.payload(rules),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AppOptConfig.parse("com.example.one=2-6\ncom.example.one{GameThread}=7\n" +
                "com.example.one{GameThread}=2-4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppOptConfig.parse("com.example.one{GameThread}=7")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppOptConfig.parse("com.example.one=6-3")
        }
    }
}
