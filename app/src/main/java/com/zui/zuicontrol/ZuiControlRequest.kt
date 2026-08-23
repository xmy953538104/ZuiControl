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
        val isTerminal: Boolean
            get() = state == ACK_DONE || state == ACK_FAILED

        val succeeded: Boolean
            get() = state == ACK_DONE
    }

    @Synchronized
    fun send(
        context: Context,
        cmd: String,
        rate: Int? = null,
        pkg: String? = null,
        mode: String? = null,
        littleMax: Int? = null,
        littleMin: Int? = null,
        bigMax: Int? = null,
        bigMin: Int? = null,
        titanMax: Int? = null,
        titanMin: Int? = null,
        megaMax: Int? = null,
        megaMin: Int? = null,
        gpuMax: Int? = null,
        gpuMin: Int? = null,
        stagePayload: String? = null,
        appOptPayload: String? = null,
        gamePolicy: String? = null,
        framePolicy: String? = null,
    ): String {
        val resolver = context.contentResolver
        val currentRequest = Settings.System.getString(
            resolver,
            ZuiControlContract.KEY_REQUEST_TEXT,
        ).orEmpty()
        val currentAck = Settings.System.getString(
            resolver,
            ZuiControlContract.KEY_REQUEST_ACK,
        ).orEmpty()
        check(!hasPendingRequest(currentRequest, currentAck)) {
            "上一条系统命令尚未完成"
        }
        val requestId = "${System.currentTimeMillis()}_${SystemClock.elapsedRealtimeNanos()}_$cmd"
        val requestText = if (cmd == ZuiControlContract.CMD_SET_PERFORMANCE_PROFILE_STAGED) {
            listOf(
                requestId,
                cmd,
                stagePayload.orEmpty().filter { it.isDigit() || it == '-' || it == ',' || it == ';' },
                pkg.orEmpty().replace("|", ""),
                mode.orEmpty().replace("|", ""),
                gamePolicy.orEmpty().filter { it.isLetterOrDigit() || it == '_' },
                framePolicy.orEmpty().filter { it.isLetterOrDigit() || it == '_' },
            ).joinToString("|")
        } else if (cmd == ZuiControlContract.CMD_REPLACE_APPOPT_RULES) {
            buildAppOptReplaceRequestText(requestId, appOptPayload.orEmpty())
        } else {
            buildGenericRequestText(
                requestId,
                cmd,
                rate?.toString().orEmpty(),
                pkg.orEmpty().replace("|", ""),
                mode.orEmpty().replace("|", ""),
                listOf(
                    littleMax?.toString().orEmpty(),
                    littleMin?.toString().orEmpty(),
                    bigMax?.toString().orEmpty(),
                    bigMin?.toString().orEmpty(),
                    titanMax?.toString().orEmpty(),
                    titanMin?.toString().orEmpty(),
                    megaMax?.toString().orEmpty(),
                    megaMin?.toString().orEmpty(),
                    gpuMax?.toString().orEmpty(),
                    gpuMin?.toString().orEmpty(),
                ),
            )
        }
        check(Settings.System.putString(
            resolver,
            ZuiControlContract.KEY_REQUEST_TEXT,
            requestText,
        )) {
            "系统命令写入失败"
        }
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
                context.contentResolver,
                ZuiControlContract.KEY_REQUEST_ACK,
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
            fields[1] !in ACK_STATES) {
            return null
        }
        return Ack(fields[0], fields[1], fields[2], fields[3])
    }

    internal fun progressLabel(detail: String): String = when (detail) {
        "validating" -> "正在校验请求"
        "validating_target" -> "正在校验目标应用"
        "generating_xml" -> "正在生成并校验 XML"
        "reloading_zuipp" -> "正在挂载新 XML"
        "stopping_game_helper" -> "正在准备游戏助手"
        "stopping_zuipp_services" -> "正在停止 ZuiPP 服务"
        "waiting_zuipp" -> "正在等待 ZuiPP 新进程稳定（3 秒）"
        "initializing_zuipp_connect" -> "正在初始化 ZuiPP 调度通道"
        "preparing_game_helper" -> "正在恢复游戏识别"
        "syncing_game_assistant" -> "正在同步游戏助手"
        "committing" -> "正在原子提交配置"
        "validating_rules" -> "正在校验 AppOpt 规则"
        "writing_rules" -> "正在写入 AppOpt 配置"
        "restarting_appopt" -> "正在重启并检查 AppOpt"
        "stopping_appopt" -> "正在停止并检查 AppOpt"
        "stopping_target" -> "正在关闭受影响 App"
        "stopping_targets" -> "正在关闭受影响的 App"
        "restarting_scheduler" -> "正在重启 Uperf 与线程服务"
        else -> detail.ifBlank { "正在等待系统处理" }
    }

    internal fun buildGenericRequestText(
        requestId: String,
        command: String,
        rate: String,
        packageName: String,
        mode: String,
        extraFields: List<String>,
    ): String {
        require(extraFields.size == 10)
        return (listOf(requestId, command, rate, packageName, mode) + extraFields)
            .joinToString("|")
    }

    internal fun buildAppOptReplaceRequestText(requestId: String, payload: String): String =
        listOf(
            requestId,
            ZuiControlContract.CMD_REPLACE_APPOPT_RULES,
            payload.filter {
                it.isLetterOrDigit() || it == '_' || it == '.' ||
                    it == '=' || it == '-' || it == ';' || it == ',' ||
                    it == '{' || it == '}' || it == '*' || it == '?' ||
                    it == ':' || it == '+' || it == ' '
            },
        ).joinToString("|")

    private const val ACK_PROCESSING = "processing"
    private const val ACK_DONE = "done"
    private const val ACK_FAILED = "failed"
    private val ACK_STATES = setOf(ACK_PROCESSING, ACK_DONE, ACK_FAILED)
    private const val ACK_POLL_MS = 200L
    const val ACK_TIMEOUT_MS = 120_000L
}
