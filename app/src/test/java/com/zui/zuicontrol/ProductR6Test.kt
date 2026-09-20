package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test

class ProductR6Test {
    @Test fun niceAxesCoverDataAndUseFourToSixReadableTicks() {
        for (values in listOf(emptyList(), listOf(0.0), listOf(44.0), listOf(57.3, 63.8),
                listOf(3.8, 11.7), listOf(37.1, 37.2), listOf(0.0, 401.0), listOf(Double.NaN, -1.0))) {
            val axis = RecordAxis.of(values)
            assertTrue(axis.ticks.size in 4..6)
            values.filter { it.isFinite() && it >= 0 }.forEach { assertTrue(it in axis.low..axis.high) }
            assertEquals(axis.ticks.size, axis.ticks.map { axis.label(it) }.distinct().size)
        }
        val quiet = RecordAxis.of(listOf(38.2, 39.1))
        assertTrue(quiet.low > 30.0)
        assertFalse(quiet.label(quiet.ticks.first()).endsWith(".000000"))
    }
    @Test fun appChangesAndSwitchesResolveTheSamePersistedRules() {
        val a = "com.example.a"; val b = "com.example.b"
        val before = "$a|fast\n$b|performance"
        assertEquals(UperfMode.FAST, UperfMode.resolve("balance", before, a))
        assertEquals(UperfMode.PERFORMANCE, UperfMode.resolve("balance", before, b))
        val after = "$a|powersave\n$b|performance"
        assertEquals(UperfMode.POWERSAVE, UperfMode.resolve("balance", after, a))
        assertEquals(UperfMode.PERFORMANCE, UperfMode.resolve("balance", after, b))
        assertEquals(UperfMode.POWERSAVE, UperfMode.resolve("balance", after, a))
        assertEquals(UperfMode.BALANCE, UperfMode.resolve("balance", "", a))
        assertEquals(UperfMode.FAST, UperfMode.resolve("invalid", " $a | fast \n$a|balance", a))
        assertEquals(UperfMode.BALANCE, UperfMode.resolve("invalid", "$a|invalid", a))
    }
}
