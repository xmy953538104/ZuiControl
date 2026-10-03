package com.zui.zuicontrol
import org.junit.Assert.*
import org.junit.Test
class NotificationMetricTest {
    @Test fun bothPowerAvailabilityEdgesBypassOrdinaryCoalescing() {
        assertTrue(NotificationQuickControlHelper.availabilityChanged(35.0,35.0,4.0,-1.0))
        assertTrue(NotificationQuickControlHelper.availabilityChanged(35.0,35.0,-1.0,4.0))
        assertFalse(NotificationQuickControlHelper.availabilityChanged(35.0,35.1,4.0,5.0))
        assertFalse(NotificationQuickControlHelper.availabilityChanged(35.0,35.1,-1.0,-1.0))
        assertTrue(NotificationQuickControlHelper.availabilityChanged(35.0,-1.0,4.0,4.0))
    }

    @Test fun eachMetricValidityIsIndependentOfOverlay() {
        for (active in listOf(false,true)) for (quiet in listOf(-1.0,Double.NaN,36.24))
            for (power in listOf(-1.0,Double.POSITIVE_INFINITY,4.26)) {
                val s=NotificationQuickControlHelper.Snapshot("com.example",120,UperfMode.BALANCE,active,true,true,quiet,power)
                assertEquals(if(quiet.isFinite()&&quiet>0) "36.2" else "--",NotificationQuickControlHelper.metricNumber(s.quietC))
                assertEquals(if(power.isFinite()&&power>0) "4.3" else "--",NotificationQuickControlHelper.metricNumber(s.powerW))
            }
    }
    @Test fun displayedPrecisionSuppressesJitterButNotExpiry() {
        assertEquals(NotificationQuickControlHelper.metricNumber(36.21),NotificationQuickControlHelper.metricNumber(36.24))
        assertNotEquals(NotificationQuickControlHelper.metricNumber(36.24),NotificationQuickControlHelper.metricNumber(36.26))
        assertNotEquals(NotificationQuickControlHelper.metricNumber(36.24),NotificationQuickControlHelper.metricNumber(-1.0))
        assertEquals("--",NotificationQuickControlHelper.metricNumber(0.0))
    }
}
