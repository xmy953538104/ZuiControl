package com.zui.zuicontrol

import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** MainActivity and QuickService share this process and the one durable request slot. */
internal object FrontendTransport {
    private val commandLane=ThreadLocal.withInitial{false}
    val commands = Executors.newSingleThreadExecutor { task->Thread({commandLane.set(true);task.run()}, "ZuiCommandLane") }
    val reads = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(64),
        { Thread(it, "ZuiDirectRead") }, ThreadPoolExecutor.AbortPolicy())
    /** Consume one ACK-bound read result on the existing slot, yielding between chunks. */
    fun <T> commandRead(task:()->T):T {
        if(commandLane.get()==true)return task()
        try{return commands.submit(java.util.concurrent.Callable{task()}).get()}
        catch(e:java.util.concurrent.ExecutionException){throw (e.cause ?: e)}
    }
}
