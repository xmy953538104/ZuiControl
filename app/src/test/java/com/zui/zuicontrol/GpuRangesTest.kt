package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test

class GpuRangesTest {
    @Test fun globalConfigAndFixedTrackGeometry() {
        assertEquals("performance" to GpuRanges.Range(231,500), GpuRanges.global("gpuGlobal=0|performance|231|500",0))
        for (line in listOf("gpuGlobal=1|performance|231|500", "gpuGlobal=0|invalid|231|500",
                "gpuGlobal=0|performance|500|231", "gpuGlobal=0|performance|232|500")) {
            assertNull(GpuRanges.global(line,0))
        }
        val track = GpuRanges.track(400f,48f,8f)
        assertEquals(32f,track.left,0f); assertEquals(368f,track.right,0f)
        for (min in GpuRanges.opps) for (max in GpuRanges.opps.filter { it >= min }) {
            val range = GpuRanges.Range(min,max)
            assertTrue(track.x(min) >= track.left && track.x(max) <= track.right)
            assertTrue(track.x(min) <= track.x(max))
            assertEquals(min,track.snap(track.x(min))); assertEquals(max,track.snap(track.x(max)))
            assertTrue(track.x(min)-24 >= 0 && track.x(max)+24 <= 400)
            assertEquals(336f,track.right-track.left,0f) // independent of selected interval
        }
        assertTrue(track.labelsCollide(GpuRanges.Range(422,500),48f,48f,4f))
        assertTrue(track.labelsCollide(GpuRanges.Range(500,500),48f,48f,4f))
        assertFalse(track.labelsCollide(GpuRanges.Range(231,903),48f,48f,4f))
    }
    @Test fun defaultsAndOverrideModel() {
        assertEquals(GpuRanges.Range(231,366), GpuRanges.default("powersave"))
        assertEquals(GpuRanges.Range(231,578), GpuRanges.default("balance"))
        assertEquals(GpuRanges.Range(422,903), GpuRanges.default("performance"))
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
