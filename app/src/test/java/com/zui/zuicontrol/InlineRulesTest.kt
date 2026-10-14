package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.zip.Inflater

class InlineRulesTest {
    @Test fun canonicalBodyRoundTripsWithinExistingCommandBound() {
        val data=("schema 3\n"+"# repeated rule content\n".repeat(2000)).toByteArray()
        val generation="g"+"1".repeat(24)
        val value=checkNotNull(ZuioptRules.inlineUser(data,generation))
        assertTrue(value.length<=10924)
        val fields=value.split(':');assertEquals(5,fields.size)
        assertEquals(listOf("zlib-user-v1",generation,data.size.toString(),ZuioptRules.digest(data)),fields.take(4))
        val inflater=Inflater();val output=ByteArray(data.size)
        try{inflater.setInput(Base64.getDecoder().decode(fields[4]));assertEquals(data.size,inflater.inflate(output));assertTrue(inflater.finished());assertEquals(0,inflater.remaining);assertArrayEquals(data,output)}finally{inflater.end()}
    }
    @Test fun incompressibleDataKeepsTheExistingChunkPath() {
        val bytes=ByteArray(ZuioptRules.RULE_LIMIT);java.util.Random(6).nextBytes(bytes)
        assertNull(ZuioptRules.inlineUser(bytes,"g"+"2".repeat(24)))
    }
    @Test(expected=IllegalArgumentException::class) fun oversizedInputRejectedBeforeCompression() {
        ZuioptRules.inlineUser(ByteArray(ZuioptRules.RULE_LIMIT+1),"g"+"3".repeat(24))
    }
}
