package com.zui.zuicontrol
import org.junit.Assert.*
import org.junit.Test
class BackendHealthTest {
    private val healthy="systemServiceAlive=true\nschedulerActive=1\nuperfFailSafe=0\nuperfServiceState=running\nzuioptFailSafe=0\nzuioptServiceState=running\ngpuRuntime=READY\ngpuHandle=0\nmonitorMode=0\nmonitorError=\nmonitorFinalizeError="
    @Test fun fiveComponentsAndIdleTruth(){
        val components=BackendHealth.components(healthy)
        assertEquals(5,components.size);assertTrue(components.all { it.state==BackendHealth.State.OK })
        assertTrue(BackendHealth.components("").all { it.state==BackendHealth.State.UNKNOWN })
        val failed=BackendHealth.components(healthy.replace("zuioptFailSafe=0","zuioptFailSafe=1").replace("zuioptServiceState=running","zuioptServiceState=stopped"))
        assertEquals(BackendHealth.State.DEGRADED,failed[2].state)
        assertEquals(BackendHealth.State.OK,failed[3].state)
        assertEquals(160,BackendHealth.components(healthy.replace("monitorError=","monitorError="+"e".repeat(1000)))[4].reason.length)
    }
    @Test fun requestHistoryIsNotRuntimeHealth(){
        val rejected=healthy+"\nmonitorLastRequestError=IllegalArgumentException:thread_identity"
        assertTrue(BackendHealth.components(rejected).all { it.state==BackendHealth.State.OK })
        val runtime=rejected.replace("monitorError=","monitorError=source_failure")
        assertEquals(BackendHealth.State.DEGRADED,BackendHealth.components(runtime)[4].state)
        assertTrue(BackendHealth.components(rejected).all { it.state==BackendHealth.State.OK })
        val finalize=rejected.replace("monitorFinalizeError=","monitorFinalizeError=record_finalize:SQLite")
        assertEquals(BackendHealth.State.DEGRADED,BackendHealth.components(finalize)[4].state)
        assertEquals(BackendHealth.State.OK,BackendHealth.components(finalize)[3].state)
    }
}
