package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test

class OwnerVisualClosureTest {
    @Test fun exactComponentMetrics() {
        listOf(13.5f,11f,10f,8f,20f).forEach{assertEquals(it,OwnerTypography.size(it),0f)}
    }
    @Test fun missingTop15BreaksNeverBecomeZero() {
        val samples=listOf(OwnerCpuSample(0.0,Double.NaN),OwnerCpuSample(3000.0,26.5),
            OwnerCpuSample(6000.0,Double.NaN),OwnerCpuSample(9000.0,11.0))
        val segments=OwnerCpuTimeline.segments(samples)
        assertEquals(listOf(listOf(samples[1]),listOf(samples[3])),segments)
        assertFalse(segments.flatten().any{it.cpu==0.0})
    }
    @Test fun absentTop15TimestampBreaksPath() {
        val samples=listOf(OwnerCpuSample(0.0,10.0),OwnerCpuSample(3000.0,20.0),OwnerCpuSample(9000.0,30.0))
        assertEquals(listOf(samples.take(2),samples.takeLast(1)),OwnerCpuTimeline.segments(samples))
    }
    @Test fun invalidTimesAndNegativeValuesDoNotConnect() {
        val samples=listOf(OwnerCpuSample(0.0,10.0),OwnerCpuSample(Double.NaN,5.0),
            OwnerCpuSample(3000.0,-1.0),OwnerCpuSample(6000.0,0.0))
        assertEquals(listOf(listOf(samples[0]),listOf(samples[3])),OwnerCpuTimeline.segments(samples))
    }
}
