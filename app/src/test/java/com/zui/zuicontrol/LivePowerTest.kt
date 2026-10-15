package com.zui.zuicontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LivePowerTest {
    @Test fun signedNetPowerAndInputAreIndependentAndZeroIsNotMissing() {
        val sample=JSONObject().put("elapsedMs",100).put("batteryNetPowerW",-1.0).put("inputPowerW",2.5)
        assertEquals(-1.0,LivePower.value(sample),0.0)
        assertEquals(2.5,LivePower.value(sample,true),0.0)
        assertEquals("-1.0",LivePower.number(LivePower.value(sample)))
        assertEquals("0.0",LivePower.number(0.0))
        assertEquals("0.0",LivePower.number(-0.0))
        sample.put("batteryNetPowerW",JSONObject.NULL)
        assertTrue(LivePower.value(sample).isNaN())
        assertEquals(2.5,LivePower.value(sample,true),0.0)
        sample.put("elapsedMs",0)
        assertTrue(LivePower.value(sample,true).isNaN())
        assertEquals("--",LivePower.number(Double.NaN))
        assertEquals("--",LivePower.number(Double.POSITIVE_INFINITY))
    }
    @Test fun legacyDischargeSampleCannotPretendToBeNetPower() {
        assertTrue(LivePower.value(JSONObject().put("elapsedMs",100).put("powerW",4.0)).isNaN())
    }
}
