package com.zui.zuicontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppOptRuleTest {
    @Test
    fun parsesOnlySupportedWholePackagePresets() {
        val rules = AppOptRules.parse(
            """
            com.example.all|0-7
            com.example.efficiency|0-4
            com.example.performance|5-7
            com.example.little|0-1
            com.example.invalid|2-3
            com.example.thread{RenderThread}|5-7
            malformed
            """.trimIndent(),
        )

        assertEquals(4, rules.size)
        assertEquals(AppOptPreset.ALL, rules["com.example.all"]?.preset)
        assertEquals(AppOptPreset.EFFICIENCY, rules["com.example.efficiency"]?.preset)
        assertEquals(AppOptPreset.PERFORMANCE, rules["com.example.performance"]?.preset)
        assertEquals(AppOptPreset.LITTLE, rules["com.example.little"]?.preset)
        assertFalse(rules.containsKey("com.example.invalid"))
    }
}
