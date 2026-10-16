package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test

class QuickControlStateTest {
    private val first = QuickControlState.Target(0, "org.example.first", "APP", 7)
    private val second = first.copy(pkg = "org.example.second", scene = 8)
    @Test fun unstartedIntentCoalescesAndSameValueDoesNotDispatchTwice() {
        val state = QuickControlState()
        val old = state.offer("refresh", first, "90", "120", 1)!!
        val latest = state.offer("refresh", first, "60", "120", 2)!!
        assertFalse(state.dispatch(old)); assertTrue(state.dispatch(latest))
        assertNull(state.offer("refresh", first, "60", "120", 3))
        assertFalse(state.dispatch(latest))
    }
    @Test fun dispatchedIdentitySurvivesQueuedChangesAndReturnsToSameTarget() {
        val state = QuickControlState()
        val active = state.offer("uperf", first, "fast", "balance", 1)!!
        assertTrue(state.dispatch(active)); state.identify(active, "original")
        assertNull(state.offer("uperf", first, "powersave", "balance", 2))
        assertEquals("powersave", state.presentation("uperf", first)?.value)
        assertEquals("original", active.requestId)
        assertNull(state.offer("uperf", first, "fast", "balance", 3))
        assertNull(state.finish(active, QuickControlState.Phase.CONFIRMED))
        assertEquals("original", active.requestId)
    }
    @Test fun foregroundAndUserChangesNeverBorrowAnotherTargetsPending() {
        val state = QuickControlState()
        val active = state.offer("uperf", first, "fast", "balance", 1)!!
        state.dispatch(active)
        assertNull(state.presentation("uperf", second))
        assertNull(state.presentation("uperf", first.copy(user = 10)))
        state.offer("uperf", second, "powersave", "balance", 2)
        val next = state.finish(active, QuickControlState.Phase.CONFIRMED)!!
        assertEquals(second, next.target); assertEquals("powersave", next.value)
    }
    @Test fun unknownOutcomeRetainsIdentityAndBlocksQueuedDispatch() {
        val state = QuickControlState()
        val active = state.offer("refresh", first, "90", "120", 1)!!
        state.dispatch(active); state.identify(active, "still-current")
        state.offer("refresh", first, "60", "120", 2)
        assertNull(state.finish(active, QuickControlState.Phase.VERIFYING, "ACK pending"))
        assertEquals(QuickControlState.Phase.VERIFYING, state.presentation("refresh", first)?.phase)
        assertEquals("still-current", active.requestId)
        assertNotNull(state.finish(active, QuickControlState.Phase.FAILED, "definitive rejection"))
    }
    @Test fun recoveryUsesExistingRequestAndConfirmedSameValueIsANoop() {
        val state = QuickControlState()
        assertNull(state.offer("uperf", first, "balance", "balance", 1))
        val recovered = state.recover("uperf", first, "fast", "existing-id")
        assertFalse(state.dispatch(recovered))
        assertEquals("existing-id", recovered.requestId)
        assertEquals(QuickControlState.Phase.VERIFYING, state.presentation("uperf", first)?.phase)
        state.finish(recovered, QuickControlState.Phase.FAILED)
        assertEquals(QuickControlState.Phase.FAILED, state.presentation("uperf", first)?.phase)
    }
    @Test fun coldRecoveryRetainsAnUnsentNewIntentAndUnknownIsNeverSuccess() {
        val state=QuickControlState()
        state.offer("uperf",second,"balance","fast",1)
        val recovered=state.recover("uperf",first,"fast","old-request")
        val next=state.finish(recovered,QuickControlState.Phase.CONFIRMED)!!
        assertEquals(second,next.target)
        assertTrue(state.dispatch(next))
        state.finish(next,QuickControlState.Phase.VERIFYING)
        state.finish(next,QuickControlState.Phase.UNKNOWN,"current state read; command unknown")
        assertEquals(QuickControlState.Phase.UNKNOWN,state.presentation("uperf",second)?.phase)
    }
    @Test fun returningToConfirmedBeforeDispatchDoesNotWrite() {
        val state=QuickControlState()
        val cancelled=state.offer("refresh",first,"90","120",1)!!
        assertNull(state.offer("refresh",first,"120","120",2))
        assertFalse(state.dispatch(cancelled))
        assertNull(state.presentation("refresh",first))
    }
}
