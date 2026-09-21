package com.zui.zuicontrol
import org.junit.Assert.*
import org.junit.Test

class MonitorGestureTest {
    @Test fun shortLongAndCancel() {
        val g=MonitorGesture();g.press(100)
        assertFalse(g.complete(2099));assertFalse(g.shortRelease(2099))
        g.release(2099);assertFalse(g.complete(5000))
        g.press(6000);assertTrue(g.complete(8000));assertFalse(g.complete(9000))
        assertFalse(g.shortRelease(9000));g.release(9000)
        g.press(10000);g.cancel();assertFalse(g.complete(13000));assertFalse(g.shortRelease(10050))
    }
    @Test fun quickTapIsDistinctFromCancelledHold() {
        for (duration in listOf(100L,200,250,500,900,1500,1900,1999,2100,2600)) {
            val g=MonitorGesture();g.press(10)
            assertEquals(duration<=250,g.shortRelease(10+duration))
            assertEquals(duration>=2000,g.complete(10+duration))
            assertFalse(g.complete(10+duration))
            g.release(10+duration)
            assertFalse(g.shortRelease(10+duration))
        }
    }
    @Test fun cyclePausesDuringHold() {
        val g=MonitorGesture();g.release(0);g.cycle(2999);assertEquals(0,g.metric)
        g.cycle(3000);assertEquals(1,g.metric)
        g.press(3500);g.cycle(7000);assertEquals(1,g.metric)
        g.release(7100);g.cycle(10100);assertEquals(2,g.metric)
        g.cycle(13100);assertEquals(0,g.metric)
    }
}
