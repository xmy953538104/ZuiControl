package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test

class GpuRangesTest {
    @Test fun defaultsAndOverrideModel() {
        assertEquals(GpuRanges.Range(231,422), GpuRanges.default("powersave"))
        assertEquals(GpuRanges.Range(231,629), GpuRanges.default("balance"))
        assertEquals(GpuRanges.Range(231,903), GpuRanges.default("performance"))
        assertEquals(GpuRanges.Range(629,903), GpuRanges.default("fast"))
        assertNull(GpuRanges.profile("profile=0|com.test.app|120|0|DISPLAY_ONLY",0))
        assertEquals("com.test.app" to GpuRanges.Range(366,720),
            GpuRanges.profile("gpuProfile=0|com.test.app|366|720",0))
        assertNull(GpuRanges.profile("gpuProfile=1|com.test.app|366|720",0))
        assertNull(GpuRanges.profile("gpuProfile=0|com.test.app|903|231",0))
        assertNull(GpuRanges.profile("gpuProfile=0|com.test.app|232|720",0))
        for (i in GpuRanges.opps.indices) assertEquals(GpuRanges.opps[i],GpuRanges.snap(i/11f))
        for (i in -100..1100) assertTrue(GpuRanges.snap(i/1000f) in GpuRanges.opps)
    }
}
