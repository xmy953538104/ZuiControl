package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrontendTransportTest {
    @Test fun readChunksYieldAndConsumeOnTheSingleCommandLane(){
        val started=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val order=java.util.Collections.synchronizedList(mutableListOf<String>())
        val reader=Thread{
            FrontendTransport.commandRead{order+="chunk1";started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
                FrontendTransport.commandRead{order+="consume1"}}
            FrontendTransport.commandRead{order+="chunk2"};finished.countDown()
        }
        reader.start();assertTrue(started.await(5,TimeUnit.SECONDS))
        FrontendTransport.commands.execute{order+="userIntent"};release.countDown()
        assertTrue(finished.await(5,TimeUnit.SECONDS));reader.join()
        assertEquals(listOf("chunk1","consume1","userIntent","chunk2"),order)
        val task=FrontendTransport.commands.submit<String>{FrontendTransport.commandRead{Thread.currentThread().name}}
        assertEquals("ZuiCommandLane",task.get(5,TimeUnit.SECONDS))
    }
}
