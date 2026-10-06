package com.zui.zuicontrol

import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** MainActivity and QuickService share this process and the one durable request slot. */
internal object FrontendTransport {
    val commands = Executors.newSingleThreadExecutor { Thread(it, "ZuiCommandLane") }
    val reads = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(64),
        { Thread(it, "ZuiDirectRead") }, ThreadPoolExecutor.AbortPolicy())
}
