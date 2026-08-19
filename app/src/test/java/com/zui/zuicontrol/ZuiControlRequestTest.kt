package com.zui.zuicontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        val request = "request-1|status||||"

        assertTrue(ZuiControlRequest.hasPendingRequest(request, ""))
        assertTrue(ZuiControlRequest.hasPendingRequest(
            request,
            "request-1|processing|status|",
        ))
        assertTrue(ZuiControlRequest.hasPendingRequest(
            request,
            "request-2|done|status|",
        ))
        assertTrue(ZuiControlRequest.hasPendingRequest(
            request,
            "request-1|done|wrong_command|",
        ))
        assertFalse(ZuiControlRequest.hasPendingRequest(
            request,
            "request-1|done|status|",
        ))
        assertFalse(ZuiControlRequest.hasPendingRequest(
            request,
            "request-1|failed|status|rejected",
        ))
    }

    @Test
    fun emptyRequestHasNoPendingWork() {
        assertFalse(ZuiControlRequest.hasPendingRequest("", ""))
    }

    @Test
    fun buildsAppOptCommandsInGenericFifteenFieldFormat() {
        val emptyExtras = List(10) { "" }

        assertEquals(
            "id|set_appopt_rule||com.xmy.ap|5-7||||||||||",
            ZuiControlRequest.buildGenericRequestText(
                "id",
                ZuiControlContract.CMD_SET_APPOPT_RULE,
                "",
                "com.xmy.ap",
                "5-7",
                emptyExtras,
            ),
        )
        assertEquals(
            "id|remove_appopt_rule||com.xmy.ap|||||||||||",
            ZuiControlRequest.buildGenericRequestText(
                "id",
                ZuiControlContract.CMD_REMOVE_APPOPT_RULE,
                "",
                "com.xmy.ap",
                "",
                emptyExtras,
            ),
        )
        assertEquals(
            "id|restore_asoul|||||||||||||",
            ZuiControlRequest.buildGenericRequestText(
                "id",
                ZuiControlContract.CMD_STOP_APPOPT,
                "",
                "",
                "",
                emptyExtras,
            ),
        )
    }

    @Test
    fun buildsValidatedBatchAppOptRequest() {
        val payload = "com.example.one=2-6;com.example.one{RenderThread}=2-4;" +
            "com.example.one{GameThread}=7"
        assertEquals(
            "id|replace_appopt_rules|$payload",
            ZuiControlRequest.buildAppOptReplaceRequestText("id", payload),
        )
    }

    @Test
    fun mapsDaemonStagesToUserProgress() {
        assertEquals("正在等待 ZuiPP 新进程稳定（3 秒）", ZuiControlRequest.progressLabel("waiting_zuipp"))
        assertEquals("正在重启并检查 AppOpt", ZuiControlRequest.progressLabel("restarting_appopt"))
        assertEquals("custom stage", ZuiControlRequest.progressLabel("custom stage"))
    }
}
