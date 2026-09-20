package com.zui.zuicontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePowerTest {
    @Test fun medianIsBoundedAndInvalidPowerClearsPreviousDischarge() {
        val live = LivePower()
        val raw = listOf(4.0, 30.0, 6.0, 8.0, 10.0)
        assertEquals(listOf(4.0, 17.0, 6.0, 8.0, 8.0), raw.map(live::add))
        assertEquals(listOf(4.0, 30.0, 6.0, 8.0, 10.0), raw)
        assertEquals(-1.0, live.add(-1.0), 0.0)
        assertEquals(12.0, live.add(12.0), 0.0)
        assertEquals(-1.0, live.add(Double.NaN), 0.0)
        assertEquals(3.0, live.add(3.0), 0.0)
        live.clear()
        assertEquals(15.0, live.add(15.0), 0.0)
    }
}
