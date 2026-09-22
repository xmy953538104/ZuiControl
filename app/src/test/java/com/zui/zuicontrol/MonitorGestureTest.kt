package com.zui.zuicontrol
import org.junit.Assert.*
import org.junit.Test

class MonitorGestureTest {
    @Test fun tapHoldAndSuccessfulStartMatrix() {
        for (duration in listOf(100L, 200, 250, 500, 900, 1500, 1900, 1999, 2000, 2100, 2600)) {
            val g = MonitorGesture(); g.press(10)
            assertEquals(duration > 250, g.arming(10 + duration))
            assertEquals(duration >= 2000, g.complete(10 + duration))
            assertFalse(g.complete(10 + duration))
            assertEquals(if (duration <= 250) MonitorGesture.Release.TO_BAR else MonitorGesture.Release.NONE,
                g.release(10 + duration))
            assertFalse(g.arming(9000)); assertFalse(g.complete(9000))
        }
    }
    @Test fun barGestureIsConsumedBeforeCircleCanArm() {
        for (duration in listOf(100L, 200, 500, 2600)) {
            val g = MonitorGesture(); g.press(10, circle = false)
            assertFalse(g.complete(5000)); assertFalse(g.arming(5000)); assertFalse(g.drag())
            assertEquals(MonitorGesture.Release.TO_CIRCLE, g.release(10 + duration))
            assertEquals(-1L, g.down); assertFalse(g.complete(9000)); assertFalse(g.arming(9000))
            assertEquals(MonitorGesture.Release.NONE, g.release(9010))
            g.press(9020); assertTrue(g.complete(11020))
        }
    }
    @Test fun dragAndCancellationCannotStartOrClick() {
        for (recording in listOf(false, true)) {
            val g = MonitorGesture(); g.press(10, recording = recording)
            assertTrue(g.drag()); assertTrue(g.drag())
            assertFalse(g.complete(9010)); assertFalse(g.arming(9010))
            assertEquals(MonitorGesture.Release.DRAG_END, g.release(9010))
            g.press(10000, recording = recording); g.cancel()
            assertFalse(g.drag()); assertFalse(g.complete(13000))
            assertEquals(MonitorGesture.Release.NONE, g.release(13000))
        }
    }
    @Test fun recordingTapStopsButHoldOrDragNeverStops() {
        val g = MonitorGesture(); g.press(10, recording = true)
        assertFalse(g.arming(510)); assertFalse(g.complete(2010))
        assertEquals(MonitorGesture.Release.NONE, g.release(2010))
        g.press(3000, recording = true)
        assertEquals(MonitorGesture.Release.STOP_RECORDING, g.release(3200))
        g.press(4000); assertTrue(g.complete(6000)); assertTrue(g.drag())
        assertEquals(MonitorGesture.Release.DRAG_END, g.release(6100))
    }
    @Test fun cyclePausesForTheWholeGesture() {
        val g = MonitorGesture(); g.release(0); g.cycle(2999); assertEquals(0, g.metric)
        g.cycle(3000); assertEquals(1, g.metric)
        g.press(3500); g.drag(); g.cycle(7000); assertEquals(1, g.metric)
        g.release(7100); g.cycle(10100); assertEquals(2, g.metric)
        g.cycle(13100); assertEquals(0, g.metric)
    }
}
