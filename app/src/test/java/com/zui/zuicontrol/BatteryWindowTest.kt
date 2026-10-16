package com.zui.zuicontrol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BatteryWindowTest {
    @Test fun explicitOpenOnceAndLateCompletionCannotReplaceNewWindow() {
        val window=BatteryWindow()
        assertTrue(window.open());val first=window.requestId
        repeat(400){assertFalse(window.open())}
        assertTrue(window.accepts(first))
        window.close();assertFalse(window.accepts(first))
        assertTrue(window.open());val second=window.requestId
        window.complete(first,Result.success(JSONObject().put("readElapsedMs",1)))
        assertTrue(window.pending);assertNull(window.health)
        var callbacks=0;window.changed={callbacks++}
        window.complete(second,Result.success(JSONObject().put("readElapsedMs",301000)))
        assertEquals(1,callbacks);assertEquals(301000,window.health!!.getLong("readElapsedMs"))
        window.complete(second,Result.failure(IllegalStateException("late timeout")))
        assertEquals("",window.error);assertEquals(1,callbacks)
        repeat(1000){assertFalse(window.open())}
        window.close();window.complete(second,Result.success(JSONObject()))
        assertFalse(window.isOpen);assertNull(window.changed)
        assertTrue(window.open());assertNull(window.health)
    }
    @Test fun configurationReattachmentKeepsOneRequestAndTimeoutIsTerminal() {
        val window=BatteryWindow();window.open();val id=window.requestId
        window.changed={fail("old Activity must be detached")};window.changed=null
        assertFalse(window.open());assertEquals(id,window.requestId)
        window.complete(id,Result.failure(IllegalStateException("timeout")))
        assertFalse(window.pending);assertEquals("timeout",window.error)
        window.complete(id,Result.success(JSONObject()))
        assertNull(window.health);assertEquals("timeout",window.error)
    }
}
