package com.zui.zuicontrol
import org.junit.Assert.*
import org.junit.Test

class MonitorGestureTest {
    @Test fun confirmedSingleDoubleAndDeadline() {
        val g=MonitorGesture(300)
        g.press(0,false); assertEquals(MonitorGesture.Release.TO_CIRCLE,g.release(100))
        g.press(200); assertEquals(MonitorGesture.Release.WAIT_TAP,g.release(300))
        assertEquals(MonitorGesture.Release.NONE,g.confirm(599))
        g.press(599); assertEquals(MonitorGesture.Release.NONE,g.confirm(600))
        assertEquals(MonitorGesture.Release.START_RECORDING,g.release(650))
        assertEquals(MonitorGesture.Release.NONE,g.release(650))
        assertEquals(MonitorGesture.Release.NONE,g.confirm(999))
        g.press(1000); g.release(1100)
        assertEquals(MonitorGesture.Release.TO_BAR,g.confirm(1400))
        assertEquals(MonitorGesture.Release.NONE,g.confirm(1500))
    }
    @Test fun longHoldCannotStartOrReplace() {
        for (duration in listOf(251L,500,1999,2000,10000)) {
            val g=MonitorGesture();g.press(0);g.release(100);g.press(200)
            assertEquals(MonitorGesture.Release.NONE,g.release(200+duration))
            assertEquals(MonitorGesture.Release.NONE,g.confirm(20000))
        }
    }
    @Test fun dragAndCancelCancelBothTaps() {
        for (recording in listOf(false,true)) {
            val g=MonitorGesture();g.press(0);g.release(100);g.press(200,recording=recording)
            assertTrue(g.drag());assertEquals(MonitorGesture.Release.DRAG_END,g.release(250))
            assertEquals(MonitorGesture.Release.NONE,g.confirm(1000))
            g.press(1100);g.release(1150);g.cancel()
            assertEquals(MonitorGesture.Release.NONE,g.confirm(2000))
            assertFalse(g.drag())
        }
    }
    @Test fun recordingTapStopsAndBarCannotDrag() {
        val g=MonitorGesture();g.press(0,recording=true)
        assertEquals(MonitorGesture.Release.STOP_RECORDING,g.release(100))
        g.press(200,false);assertFalse(g.drag())
        g.cancel();assertEquals(MonitorGesture.Release.NONE,g.release(300))
    }
    @Test fun cycleDoesNotSampleOrInterruptPointer() {
        val g=MonitorGesture();g.cycle(3000);assertEquals(1,g.metric)
        g.press(3500);g.drag();g.cycle(7000);assertEquals(1,g.metric)
        g.release(7100);g.cycle(10100);assertEquals(2,g.metric)
    }
}
