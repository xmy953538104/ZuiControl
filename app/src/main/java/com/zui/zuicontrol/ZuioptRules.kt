package com.zui.zuicontrol

import android.content.Context
import android.net.Uri
import android.provider.Settings
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
            boundedRead(it, if (kind == "pack") PACK_LIMIT else RULE_LIMIT)
        }

    fun command(context: Context, action: String, key: String = "", value: String = "") {
        require(action in setOf("state", "owner", "next", "read", "begin", "chunk", "commit", "abort", "enable", "disable", "rollback"))
        require(key.length <= 128 && value.length <= 10924 && '|' !in key && '|' !in value)
        val id = ZuiControlRequest.send(context, "zo_$action", pkg = key, mode = value)
        val ack = ZuiControlRequest.awaitTerminalAck(context, id)
        check(ack.succeeded) { "规则操作未完成：${ack.detail}；请刷新确认状态" }
    }

    fun state(context: Context): String {
        command(context, "state")
        return Settings.System.getString(context.contentResolver, ZuiControlContract.KEY_ZUIOPT_STATE)
            .orEmpty().also { require(it.length <= 4608) { "状态响应过大" } }
    }

    internal fun field(state: String, key: String): String = state.lineSequence()
        .firstOrNull { it.startsWith("$key=") }?.substringAfter('=').orEmpty()

    fun userRules(context: Context): String {
        val state = state(context)
        val generation = field(state, "generation")
        require(generation.matches(Regex("g[0-9a-f]{24}")))
        val size = field(state, "user_size").toInt()
        require(size in 1..RULE_LIMIT)
        val out = ByteArrayOutputStream()
        while (out.size() < size) {
            command(context, "read", generation, out.size().toString())
            val response = Settings.System.getString(context.contentResolver, ZuiControlContract.KEY_ZUIOPT_CHUNK).orEmpty()
            require(response.length <= 11000)
            val fields = response.split(':', limit = 3)
            check(fields.size == 3 && fields[0] == generation && fields[1] == out.size().toString()) { "读取期间规则发生变化，请重试" }
            val data = Base64.getDecoder().decode(fields[2])
            require(data.size in 1..CHUNK && out.size() + data.size <= size)
            out.write(data)
        }
        val data = out.toByteArray()
        check(digest(data) == field(state, "user_sha256")) { "规则响应哈希不一致" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
    }

    fun upload(context: Context, kind: String, data: ByteArray, packId: String = "-", priority: Int = 0) {
        require(kind in setOf("pack", "user", "appopt"))
        require(data.isNotEmpty() && data.size <= if (kind == "pack") PACK_LIMIT else RULE_LIMIT)
        require(priority in -1000000..1000000)
        require(packId == "-" || packId.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
        require(kind != "appopt" || packId != "-")
        val tx = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        command(context, "begin", tx, "$kind:${data.size}:${digest(data)}:$packId:$priority")
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
