package com.zui.zuicontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2StatusTest {
    @Test
    fun onlyP2StateCanMarkXmlAsFailed() {
        assertFalse(
            hasP2StateError(
                "state=mounted;source=active",
                "state=done;reason=boot_active",
            ),
        )
        assertTrue(hasP2StateError("state=error;stage=mount", ""))
        assertTrue(hasP2StateError("state=mounted", "state=failed;stage=restart"))
    }
}
