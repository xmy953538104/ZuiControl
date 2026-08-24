package com.zui.zuicontrol

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

object ZuiControlRequest {
    data class Ack(
        val requestId: String,
        val state: String,
        val command: String,
        val detail: String,
    ) {
        val isTerminal: Boolean get() = state == ACK_DONE || state == ACK_FAILED
        val succeeded: Boolean get() = state == ACK_DONE
    }

    @Synchronized
    fun send(
        context: Context,
        cmd: String,
        pkg: String? = null,
        mode: String? = null,
    ): String {
        val resolver = context.contentResolver
        val currentRequest = Settings.System.getString(
            resolver, ZuiControlContract.KEY_REQUEST_TEXT,
        ).orEmpty()
        val currentAck = Settings.System.getString(
            resolver, ZuiControlContract.KEY_REQUEST_ACK,
        ).orEmpty()
        check(!hasPendingRequest(currentRequest, currentAck)) { "上一条系统命令尚未完成" }
        val requestId = "${System.currentTimeMillis()}_${SystemClock.elapsedRealtimeNanos()}_$cmd"
        val requestText = buildRequestText(
            requestId,
            cmd,
            pkg.orEmpty().replace("|", ""),
            mode.orEmpty().replace("|", ""),
        )
        check(Settings.System.putString(
            resolver, ZuiControlContract.KEY_REQUEST_TEXT, requestText,
        )) { "系统命令写入失败" }
        return requestId
    }

    fun awaitTerminalAck(
        context: Context,
        requestId: String,
        timeoutMs: Long = ACK_TIMEOUT_MS,
        onProgress: ((Ack) -> Unit)? = null,
    ): Ack {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastProgress: Ack? = null
        do {
            val ack = parseAck(Settings.System.getString(
                context.contentResolver, ZuiControlContract.KEY_REQUEST_ACK,
            ).orEmpty())
            if (ack?.requestId == requestId) {
                if (ack.isTerminal) return ack
                if (ack != lastProgress) {
                    lastProgress = ack
                    onProgress?.invoke(ack)
                }
            }
            Thread.sleep(ACK_POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw IllegalStateException("等待系统命令完成超时")
    }

    internal fun buildRequestText(
        requestId: String,
        command: String,
        packageName: String,
        mode: String,
    ): String = listOf(requestId, command, "", packageName, mode).joinToString("|")

    internal fun hasPendingRequest(requestText: String, ackText: String): Boolean {
        val requestId = requestIdFromText(requestText) ?: return false
        val requestCommand = requestCommandFromText(requestText) ?: return true
        val ack = parseAck(ackText)
        return ack?.requestId != requestId || ack.command != requestCommand || !ack.isTerminal
    }

    internal fun requestIdFromText(value: String): String? =
        value.substringBefore('|').trim().takeIf { it.isNotEmpty() }

    internal fun requestCommandFromText(value: String): String? =
        value.substringAfter('|', "").substringBefore('|').trim().takeIf { it.isNotEmpty() }

    internal fun parseAck(value: String): Ack? {
        val fields = value.split('|', limit = 4)
        if (fields.size != 4 || fields[0].isBlank() || fields[2].isBlank() ||
            fields[1] !in ACK_STATES) return null
        return Ack(fields[0], fields[1], fields[2], fields[3])
    }

    internal fun progressLabel(detail: String): String = when (detail) {
        "validating" -> "正在校验请求"
        "validating_target" -> "正在校验目标应用"
        "restarting_scheduler" -> "正在重启 Uperf 与线程服务"
        else -> detail.ifBlank { "正在等待系统处理" }
    }

    private const val ACK_PROCESSING = "processing"
    private const val ACK_DONE = "done"
    private const val ACK_FAILED = "failed"
    private val ACK_STATES = setOf(ACK_PROCESSING, ACK_DONE, ACK_FAILED)
    private const val ACK_POLL_MS = 200L
    const val ACK_TIMEOUT_MS = 120_000L
}
