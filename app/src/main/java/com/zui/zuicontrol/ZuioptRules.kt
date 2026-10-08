package com.zui.zuicontrol

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Client for the existing authenticated command plane; never edits daemon state. */
object ZuioptRules {
    const val RULE_LIMIT = 65536
    const val PACK_LIMIT = 131072
    const val CHUNK = 8192
    private val random = SecureRandom()

    internal fun digest(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }

    internal fun boundedRead(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (count < 0) break
            check(count > 0) { "导入文件读取未取得进展" }
            out.write(buffer, 0, count)
            require(out.size() <= limit) { "文件超过 ${limit / 1024} KiB 上限" }
        }
        require(out.size() > 0) { "文件为空" }
        return out.toByteArray()
    }

    fun readDocument(context: Context, uri: Uri, kind: String): ByteArray =
        checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件" }.use {
            boundedRead(it, if (kind == "pack" || kind == "library") PACK_LIMIT else RULE_LIMIT)
        }

    fun command(context: Context, action: String, key: String = "", value: String = ""): String {
        require(action in setOf("state", "reset", "read", "begin", "chunk", "validate", "preview", "upstream_read", "restore_app", "commit", "abort", "enable", "disable", "rollback"))
        require(key.length <= 128 && value.length <= 10924 && '|' !in key && '|' !in value)
        val command = if (action == "reset") ZuiControlContract.CMD_RESET_ZUIOPT_FAILSAFE else "zo_$action"
        val id = ZuiControlRequest.send(context, command, pkg = key, mode = value)
        val ack = ZuiControlRequest.awaitTerminalAck(context, id)
        check(ack.succeeded) { "规则操作未完成：${ack.detail}；请刷新确认状态" }
        return id
    }

    fun state(context: Context): String = ZuioptRead.state() ?: FrontendTransport.commandRead {
        val id = command(context, "state")
        ZuiControlClient.utilityValue("result", "$id|rulesState").also { require(it.length <= 4608) { "状态响应过大" } }
    }

    internal fun field(state: String, key: String): String = state.lineSequence()
        .firstOrNull { it.startsWith("$key=") }?.substringAfter('=').orEmpty()

    data class Snapshot(val generation: String, val text: String)

    fun userRules(context: Context): Snapshot = userRules(context, state(context))

    internal fun userRules(context: Context, state: String): Snapshot {
        ZuioptRead.snapshot(state)?.let { return Snapshot(field(it.state,"generation"),it.user) }
        val generation = field(state, "generation")
        require(generation.matches(Regex("g[0-9a-f]{24}")))
        val size = field(state, "user_size").toInt()
        require(size in 1..RULE_LIMIT)
        val out = ByteArrayOutputStream()
        while (out.size() < size) {
            val response = FrontendTransport.commandRead {
                val id = command(context, "read", generation, out.size().toString())
                ZuiControlClient.utilityValue("result", "$id|rulesChunk")
            }
            require(response.length <= 11000)
            val fields = response.split(':', limit = 3)
            check(fields.size == 3 && fields[0] == generation && fields[1] == out.size().toString()) { "读取期间规则发生变化，请重试" }
            val data = Base64.getDecoder().decode(fields[2])
            require(data.size in 1..CHUNK && out.size() + data.size <= size)
            out.write(data)
        }
        val data = out.toByteArray()
        check(digest(data) == field(state, "user_sha256")) { "规则响应哈希不一致" }
        return Snapshot(generation, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString())
    }

    data class Validation(val valid: Boolean, val normalizedSha256: String, val error: String)
    fun validate(context: Context, data: ByteArray, expectedGeneration: String): Validation {
        require(data.size in 1..RULE_LIMIT && expectedGeneration.matches(Regex("g[0-9a-f]{24}")))
        val tx = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        command(context, "begin", tx, "user:${data.size}:${digest(data)}:-:0:$expectedGeneration")
        try {
            for (offset in data.indices step CHUNK) command(context, "chunk", "$tx:$offset",
                Base64.getEncoder().encodeToString(data.copyOfRange(offset, minOf(offset + CHUNK, data.size))))
            val id = command(context, "validate", tx)
            val result = ZuiControlClient.utilityValue("result", "$id|rulesValidation")
            require(result.length <= 256)
            val valid = field(result, "validation")
            require(valid in setOf("PASS", "FAIL"))
            val hash = field(result, "normalized_sha256")
            if (valid == "PASS") require(hash.matches(Regex("[0-9a-f]{64}")))
            return Validation(valid == "PASS", hash, field(result, "error"))
        } finally { runCatching { command(context, "abort", tx) } }
    }

    fun upload(context: Context, kind: String, data: ByteArray, packId: String = "-", priority: Int = 0, expectedGeneration: String) {
        require(kind in setOf("pack", "user")) { "AppOpt must use the preview/confirm library API" }
        require(data.isNotEmpty() && data.size <= if (kind == "pack") PACK_LIMIT else RULE_LIMIT)
        require(priority in -1000000..1000000)
        require(packId == "-" || packId.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
        val tx = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(expectedGeneration.matches(Regex("g[0-9a-f]{24}")))
        command(context, "begin", tx, "$kind:${data.size}:${digest(data)}:$packId:$priority:$expectedGeneration")
        try {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + CHUNK, data.size)
                command(context, "chunk", "$tx:$offset", Base64.getEncoder().encodeToString(data.copyOfRange(offset, end)))
                offset = end
            }
            command(context, "commit", tx)
        } catch (error: Exception) {
            // Best effort abort cannot clear another transaction. A lost client expires in ten minutes/on reboot.
            runCatching { command(context, "abort", tx) }
            throw error
        }
    }
}
