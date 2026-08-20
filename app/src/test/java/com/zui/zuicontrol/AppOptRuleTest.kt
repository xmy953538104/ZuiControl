package com.zui.zuicontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppOptRuleTest {
    @Test
    fun parsesPublishedCanonicalAndLegacyRules() {
        val rules = AppOptRules.parse(
            """
            com.example.game=2-6
            com.example.game{RenderThread}=2-4
            com.example.game{GameThread}=7
            """.trimIndent(),
        )

        assertEquals(1, rules.size)
        assertEquals(AppOptPreset.GAME_BACKGROUND, rules["com.example.game"]?.preset)
        assertEquals(2, rules["com.example.game"]?.threadRules?.size)

        val legacy = AppOptRules.parse("com.example.all|0-7\ncom.example.invalid|7-2")
        assertEquals(AppOptPreset.ALL, legacy["com.example.all"]?.preset)
        assertFalse(legacy.containsKey("com.example.invalid"))
        assertFalse(AppOptPreset.PERFORMANCE_CLUSTER.canUseAsPackagePreset)
        assertFalse(AppOptPreset.PRIME.canUseAsPackagePreset)
        assertEquals(5, AppOptPreset.packagePresets.size)
        assertEquals("2-7", AppOptPreset.fromEndpoints(7, 2)?.cpuSet)
        assertEquals("3", AppOptPreset.fromEndpoints(3)?.cpuSet)
        assertEquals(null, AppOptPreset.fromCpuSet("6-2"))
        assertEquals(null, AppOptPreset.fromCpuSet("01"))
    }
}
