package com.zui.zuicontrol
import org.junit.Assert.*
import org.junit.Test

class MonitorGestureTest {
    @Test fun shortLongAndCancel() {
        val g=MonitorGesture();g.press(100)
        assertFalse(g.complete(2099));assertTrue(g.shortRelease())
        g.release(2099);assertFalse(g.complete(5000))
        g.press(6000);assertTrue(g.complete(8000));assertFalse(g.complete(9000))
        assertFalse(g.shortRelease());g.release(9000)
        g.press(10000);g.cancel();assertFalse(g.complete(13000));assertFalse(g.shortRelease())
    }
    @Test fun cyclePausesDuringHold() {
        val g=MonitorGesture();g.release(0);g.cycle(2999);assertEquals(0,g.metric)
        g.cycle(3000);assertEquals(1,g.metric)
        g.press(3500);g.cycle(7000);assertEquals(1,g.metric)
        g.release(7100);g.cycle(10100);assertEquals(2,g.metric)
        g.cycle(13100);assertEquals(0,g.metric)
    }
}
