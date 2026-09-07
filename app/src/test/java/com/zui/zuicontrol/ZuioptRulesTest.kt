package com.zui.zuicontrol

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ZuioptRulesTest {
    @Test fun boundedDocumentAndDigest() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ZuioptRules.digest("abc".toByteArray()))
        val bytes = ByteArray(ZuioptRules.PACK_LIMIT) { 17 }
        assertArrayEquals(bytes, ZuioptRules.boundedRead(ByteArrayInputStream(bytes), ZuioptRules.PACK_LIMIT))
        assertThrows(IllegalArgumentException::class.java) { ZuioptRules.boundedRead(ByteArrayInputStream(ByteArray(0)), 10) }
        assertThrows(IllegalArgumentException::class.java) { ZuioptRules.boundedRead(ByteArrayInputStream(ByteArray(11)), 10) }
    }

    @Test fun stateFieldsAreExact() {
        val state = "generation=g123\nnext_owner=ASOULOPT\ncurrent_owner=ZUIOPT\n"
        assertEquals("ZUIOPT", ZuioptRules.field(state, "current_owner"))
        assertEquals("", ZuioptRules.field(state, "owner"))
    }
}
