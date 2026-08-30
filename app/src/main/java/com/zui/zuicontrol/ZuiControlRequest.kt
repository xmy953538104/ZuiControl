package com.zui.zuicontrol

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ZuiControlRequest {
    private data class PendingRequest(
        val requestId: String,
        val requestText: String,
        val sha256: String,
    )

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
        val currentAck = Settings.System.getString(
            resolver, ZuiControlContract.KEY_REQUEST_ACK,
        ).orEmpty()
        loadPending(context)?.let { pending ->
            if (isTerminalFor(pending, currentAck)) {
                clearPending(context, pending.requestId)
            } else {
                kickTrusted(context, pending)
                error("上一条系统命令尚未完成，已重新唤醒系统处理")
            }
        }
        val requestId = "${System.currentTimeMillis()}_${SystemClock.elapsedRealtimeNanos()}_$cmd"
        val requestText = buildRequestText(
            requestId,
            cmd,
            pkg.orEmpty().replace("|", ""),
            mode.orEmpty().replace("|", ""),
        )
        val pending = PendingRequest(requestId, requestText, sha256(requestText))
        check(savePending(context, pending)) { "系统命令认证状态写入失败" }
        Log.i(TIMING_TAG, "id=$requestId phase=T0 ns=${SystemClock.elapsedRealtimeNanos()}")
        try {
            check(Settings.System.putString(
                resolver, ZuiControlContract.KEY_REQUEST_TEXT, requestText,
            )) { "系统命令写入失败" }
        } catch (t: Throwable) {
            clearPending(context, requestId)
            throw t
        }
        ZuiControlClient.notifyControlRequest(requestId, pending.sha256)
        return requestId
    }

    @Synchronized
    fun kickPending(context: Context): String? {
        val pending = loadPending(context) ?: return null
        val ackText = Settings.System.getString(
            context.contentResolver, ZuiControlContract.KEY_REQUEST_ACK,
        ).orEmpty()
        if (isTerminalFor(pending, ackText)) {
            clearPending(context, pending.requestId)
            return null
        }
        return kickTrusted(context, pending)
    }

    fun recoverPending(context: Context): Ack? {
        val requestId = kickPending(context) ?: return null
        return awaitTerminalAck(context, requestId)
    }

    fun awaitTerminalAck(
        context: Context,
        requestId: String,
        timeoutMs: Long = ACK_TIMEOUT_MS,
        onProgress: ((Ack) -> Unit)? = null,
    ): Ack {
        val pending = loadPending(context)?.takeIf { it.requestId == requestId }
            ?: throw IllegalStateException("系统命令认证状态已丢失")
        val requestCommand = requestCommandFromText(pending.requestText)
            ?: throw IllegalStateException("系统命令认证状态无效")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastProgress: Ack? = null
        var retryCount = 0
        var nextKickAt = SystemClock.elapsedRealtime() + retryDelayMs(retryCount)
        do {
            val ack = parseAck(Settings.System.getString(
                context.contentResolver, ZuiControlContract.KEY_REQUEST_ACK,
            ).orEmpty())
            if (ack?.requestId == requestId && ack.command == requestCommand) {
                if (ack.isTerminal) {
                    Log.i(
                        TIMING_TAG,
                        "id=$requestId phase=T9 ns=${SystemClock.elapsedRealtimeNanos()}",
                    )
                    clearPending(context, requestId)
                    return ack
                }
                if (ack != lastProgress) {
                    lastProgress = ack
                    onProgress?.invoke(ack)
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (retryCount < MAX_KICK_RETRIES && now >= nextKickAt) {
                kickTrusted(context, pending)
                retryCount++
                nextKickAt = now + retryDelayMs(retryCount)
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0) Thread.sleep(minOf(ACK_POLL_MS, remaining))
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
        val requestCommand = requestCommandFromText(requestText) ?: return false
        val ack = parseAck(ackText)
        return ack?.requestId != requestId || ack.command != requestCommand || !ack.isTerminal
    }

    internal fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val valueByte = byte.toInt() and 0xff
                append(alphabet[valueByte ushr 4])
                append(alphabet[valueByte and 0x0f])
            }
        }
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

    internal fun retryDelayMs(retryCount: Int): Long {
        val shift = retryCount.coerceIn(0, 3)
        return INITIAL_KICK_RETRY_MS shl shift
    }

    private fun kickTrusted(context: Context, pending: PendingRequest): String? {
        val resolver = context.contentResolver
        val currentRequest = Settings.System.getString(
            resolver, ZuiControlContract.KEY_REQUEST_TEXT,
        ).orEmpty()
        if (currentRequest != pending.requestText && !Settings.System.putString(
                resolver, ZuiControlContract.KEY_REQUEST_TEXT, pending.requestText,
            )) {
            return null
        }
        ZuiControlClient.notifyControlRequest(pending.requestId, pending.sha256)
        return pending.requestId
    }

    private fun isTerminalFor(pending: PendingRequest, ackText: String): Boolean {
        val command = requestCommandFromText(pending.requestText) ?: return false
        val ack = parseAck(ackText) ?: return false
        return ack.requestId == pending.requestId && ack.command == command && ack.isTerminal
    }

    private fun savePending(context: Context, pending: PendingRequest): Boolean =
        preferences(context).edit()
            .putString(PREF_REQUEST_ID, pending.requestId)
            .putString(PREF_REQUEST_TEXT, pending.requestText)
            .putString(PREF_REQUEST_SHA256, pending.sha256)
            .commit()

    private fun loadPending(context: Context): PendingRequest? {
        val prefs = preferences(context)
        val id = prefs.getString(PREF_REQUEST_ID, null).orEmpty()
        val text = prefs.getString(PREF_REQUEST_TEXT, null).orEmpty()
        val digest = prefs.getString(PREF_REQUEST_SHA256, null).orEmpty()
        if (id.isNotEmpty() && requestIdFromText(text) == id && sha256(text) == digest) {
            return PendingRequest(id, text, digest)
        }
        if (id.isNotEmpty() || text.isNotEmpty() || digest.isNotEmpty()) {
            prefs.edit().clear().commit()
        }
        return null
    }

    private fun clearPending(context: Context, requestId: String) {
        val prefs = preferences(context)
        if (prefs.getString(PREF_REQUEST_ID, null) == requestId) {
            prefs.edit().clear().commit()
        }
    }

    private fun preferences(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val ACK_PROCESSING = "processing"
    private const val ACK_DONE = "done"
    private const val ACK_FAILED = "failed"
    private val ACK_STATES = setOf(ACK_PROCESSING, ACK_DONE, ACK_FAILED)
    private const val ACK_POLL_MS = 200L
    private const val INITIAL_KICK_RETRY_MS = 1_000L
    private const val PREFS_NAME = "zui_control_pending_command"
    private const val PREF_REQUEST_ID = "request_id"
    private const val PREF_REQUEST_TEXT = "request_text"
    private const val PREF_REQUEST_SHA256 = "request_sha256"
    private const val TIMING_TAG = "ZuiControlTiming"
    internal const val MAX_KICK_RETRIES = 6
    const val ACK_TIMEOUT_MS = 120_000L
}
