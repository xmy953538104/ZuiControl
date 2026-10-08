package com.zui.zuicontrol
import org.junit.Assert.*
import org.junit.Test
class BackendHealthTest {
    private val healthy="systemServiceAlive=true\npolicyRecoveryRequired=false\npolicyRetentionError=\npolicyGeneration=543\nschedulerActive=1\nschedulerHealth=ok\nlastSchedulerError=none\nuperfMode=balance\nuperfFailSafe=0\nuperfServiceState=running\nzuioptFailSafe=0\nzuioptServiceState=running\nthreadManagerState=zuiopt_active\ngpuRuntime=READY\ngpuFailSafe=false\ngpuTransport=QTI_PerfLock\ngpuLastError=\ngpuHandle=0\nscreenInteractive=true\nmonitorMode=0\nmonitorActive=true\nmonitorTimer=true\nmonitorIntervalMs=5000\nmonitorRecordState=IDLE\nmonitorError=\nmonitorFinalizeError="
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
    @Test fun ownershipAndCoherenceFaultFixtures(){
        fun changed(vararg edits:Pair<String,String>):List<BackendHealth.Component>{var text=healthy;edits.forEach{(a,b)->text=text.replace(a,b)};return BackendHealth.components(text)}
        assertEquals(BackendHealth.State.FAILED,changed("uperfServiceState=running" to "uperfServiceState=stopped")[1].state)
        assertEquals(BackendHealth.State.DEGRADED,changed("uperfFailSafe=0" to "uperfFailSafe=1")[1].state)
        val disabled=changed("schedulerActive=1" to "schedulerActive=0","uperfServiceState=running" to "uperfServiceState=stopped","zuioptServiceState=running" to "zuioptServiceState=stopped","threadManagerState=zuiopt_active" to "threadManagerState=inactive_or_unhealthy")
        assertEquals("DISABLED_BY_OWNER",disabled[1].reason);assertEquals("DISABLED_BY_OWNER",disabled[2].reason)
        assertEquals(BackendHealth.State.DEGRADED,changed("threadManagerState=zuiopt_active" to "threadManagerState=android_default_failsafe","zuioptFailSafe=0" to "zuioptFailSafe=1","zuioptServiceState=running" to "zuioptServiceState=stopped")[2].state)
        assertEquals(BackendHealth.State.DEGRADED,changed("gpuRuntime=READY" to "gpuRuntime=DEGRADED_FAIL_SAFE")[3].state)
        assertEquals(BackendHealth.State.FAILED,changed("policyRecoveryRequired=false" to "policyRecoveryRequired=true")[0].state)
        assertEquals(BackendHealth.State.DEGRADED,changed("monitorTimer=true" to "monitorTimer=false")[4].state)
        assertEquals(BackendHealth.State.UNKNOWN,changed("threadManagerState=zuiopt_active\n" to "")[2].state)
        assertEquals(BackendHealth.State.UNKNOWN,changed("policyRetentionError=\n" to "")[0].state)
        assertTrue(changed("lastSchedulerError=none" to "lastSchedulerError=uperf_stopped_while_active").all{it.state==BackendHealth.State.OK})
        val facts=BackendHealth.components(healthy);assertTrue(facts.all{it.facts.isNotEmpty()});assertTrue(facts[2].facts.contains("线程管理：ZUIopt 已接管"));assertTrue(facts[4].facts.contains("采样周期：5000 ms"))
    }
    @Test fun freshSampleRevalidatesPausedCollectorWithoutPollingHealthyState(){
        for(paused in listOf("monitorTimer=true" to "monitorTimer=false","monitorActive=true" to "monitorActive=false","screenInteractive=true" to "screenInteractive=false")) {
            val text=healthy.replace(paused.first,paused.second)
            assertFalse(BackendHealth.collectorResumed(text,100,100))
            assertFalse(BackendHealth.collectorResumed(text,100,99))
            assertTrue(BackendHealth.collectorResumed(text,100,101))
        }
        assertFalse(BackendHealth.collectorResumed(healthy,100,101))
        assertFalse(BackendHealth.collectorResumed(healthy.replace("monitorError=","monitorError=source_failure"),100,101))
        assertFalse(BackendHealth.collectorResumed("monitorTimer=false",0,0))
        assertEquals(BackendHealth.State.DEGRADED,BackendHealth.components(healthy.replace("monitorError=","monitorError=source_failure"))[4].state)
    }
}
