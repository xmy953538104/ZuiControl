package com.zui.zuicontrol

import android.zui.ZuiControlManager
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Finite reads from the existing daemon; writes retain the durable command plane. */
internal object ZuioptRead {
    const val TRANSPORT = "DAEMON_SNAPSHOT_V1"
    private const val PREFIX = "ok=1\nresultCategory=AUTHORITATIVE_READ_ONLY\nreadData="
    private val identities = listOf("generation", "canonical_sha256", "user_sha256", "user_size",
        "effective_sha256", "upstream_sha256", "upstream_size", "upstream_metadata_size", "upstream_metadata_sha256")
    data class Snapshot(val state: String, val user: String, val upstream: String, val metadata: String)

    private fun request(argument: String): String? {
        val manager = checkNotNull(ZuiControlManager.get()) { "系统规则服务不可用" }
        val capabilities = manager.getCapabilities()
        check(capabilities.length <= 8192 && capabilities.startsWith("ok=1\n")) { "规则读取能力不可用" }
        val transports = capabilities.lineSequence().filter { it.startsWith("rulesReadTransport=") }.toList()
        if (transports.isEmpty()) return null // Explicit old-ROM compatibility.
        check(transports == listOf("rulesReadTransport=$TRANSPORT")) { "规则读取能力不匹配" }
        val result = manager.utility("rulesRead", argument)
        if (result == "ok=0\nerror=rules_read_owner_absent") return null
        check(result.length <= PREFIX.length + 200000 && result.startsWith(PREFIX)) {
            "原生规则读取未完成：${result.take(256)}"
        }
        return result.removePrefix(PREFIX)
    }

    fun state(): String? = request("state")?.also(::validateState)

    fun snapshot(state: String): Snapshot? {
        if (ZuioptRules.field(state, "rules_read_transport") != TRANSPORT) return null
        validateState(state)
        val result = checkNotNull(request("snapshot|${ZuioptRules.field(state, "generation")}")) {
            "规则所有者已停止，请重新读取状态"
        }
        return decodeSnapshot(result, state)
    }

    private fun validateState(state: String) {
        require(state.length <= 4608 && state.endsWith('\n') && '\r' !in state && '\u0000' !in state)
        for (key in identities + "rules_read_transport") {
            require(state.lineSequence().count { it.startsWith("$key=") } == 1) { "规则状态身份缺失或重复：$key" }
        }
        require(ZuioptRules.field(state, "generation").matches(Regex("g[0-9a-f]{24}")))
        require(ZuioptRules.field(state, "rules_read_transport") == TRANSPORT)
        for (key in listOf("canonical_sha256", "user_sha256", "effective_sha256", "upstream_sha256", "upstream_metadata_sha256"))
            require(ZuioptRules.field(state, key).matches(Regex("[0-9a-f]{64}")))
        for (key in listOf("user_size", "upstream_size")) require(ZuioptRules.field(state, key).toInt() in 1..65536)
        require(ZuioptRules.field(state, "upstream_metadata_size").toInt() in 1..8192)
    }

    internal fun decodeSnapshot(frame: String, expected: String): Snapshot {
        validateState(expected)
        require(frame.length <= 200000 && '\r' !in frame && '\u0000' !in frame)
        val parts = frame.split('\n')
        require(parts.size == 6 && parts[0] == "ZUIOPT_READ_SNAPSHOT_V1" && parts[5].isEmpty())
        fun part(index: Int, key: String, limit: Int): ByteArray {
            val prefix = "$key="
            require(parts[index].startsWith(prefix))
            val encoded = parts[index].removePrefix(prefix)
            require(encoded.length <= 4 * ((limit + 2) / 3))
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size in 1..limit && Base64.getEncoder().encodeToString(bytes) == encoded)
            return bytes
        }
        fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val fresh = utf8(part(1, "state", 4608)); validateState(fresh)
        check(identities.all { ZuioptRules.field(expected, it) == ZuioptRules.field(fresh, it) }) {
            "读取期间规则版本变化，请重试"
        }
        fun checked(index: Int, key: String, sizeKey: String, hashKey: String, limit: Int): String {
            val bytes = part(index, key, limit)
            check(bytes.size == ZuioptRules.field(fresh, sizeKey).toInt()
                && ZuioptRules.digest(bytes) == ZuioptRules.field(fresh, hashKey)) { "规则响应身份不一致" }
            return utf8(bytes)
        }
        return Snapshot(fresh, checked(2, "user", "user_size", "user_sha256", 65536),
            checked(3, "upstream", "upstream_size", "upstream_sha256", 65536),
            checked(4, "metadata", "upstream_metadata_size", "upstream_metadata_sha256", 8192))
    }
}
