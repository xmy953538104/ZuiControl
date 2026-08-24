package com.zui.zuicontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZuiControlRequestTest {
    @Test
    fun parsesFixedFourFieldAck() {
        val ack = ZuiControlRequest.parseAck("request-1|done|status|ok")

        assertTrue(ack?.isTerminal == true)
        assertTrue(ack?.succeeded == true)
        assertNull(ZuiControlRequest.parseAck("request-1|done|status"))
        assertNull(ZuiControlRequest.parseAck("request-1|unknown|status|"))
    }

    @Test
    fun onlyExactTerminalAckClearsPendingRequest() {
        val request = "request-1|status|||"

        assertTrue(ZuiControlRequest.hasPendingRequest(request, ""))
        assertTrue(ZuiControlRequest.hasPendingRequest(request, "request-1|processing|status|"))
        assertTrue(ZuiControlRequest.hasPendingRequest(request, "request-2|done|status|"))
        assertTrue(ZuiControlRequest.hasPendingRequest(request, "request-1|done|wrong|"))
        assertFalse(ZuiControlRequest.hasPendingRequest(request, "request-1|done|status|"))
        assertFalse(ZuiControlRequest.hasPendingRequest(request, "request-1|failed|status|rejected"))
    }

    @Test
    fun emptyRequestHasNoPendingWork() {
        assertFalse(ZuiControlRequest.hasPendingRequest("", ""))
    }

    @Test
    fun buildsMinimalFiveFieldRequest() {
        assertEquals(
            "id|set_uperf_app||com.example.game|performance",
            ZuiControlRequest.buildRequestText(
                "id",
                ZuiControlContract.CMD_SET_UPERF_APP,
                "com.example.game",
                "performance",
            ),
        )
        assertEquals(
            "id|restart_scheduler|||",
            ZuiControlRequest.buildRequestText(
                "id",
                ZuiControlContract.CMD_RESTART_SCHEDULER,
                "",
                "",
            ),
        )
    }

    @Test
    fun mapsCurrentDaemonStages() {
        assertEquals("正在校验请求", ZuiControlRequest.progressLabel("validating"))
        assertEquals("正在校验目标应用", ZuiControlRequest.progressLabel("validating_target"))
        assertEquals("正在重启 Uperf 与线程服务", ZuiControlRequest.progressLabel("restarting_scheduler"))
        assertEquals("custom stage", ZuiControlRequest.progressLabel("custom stage"))
    }
}
