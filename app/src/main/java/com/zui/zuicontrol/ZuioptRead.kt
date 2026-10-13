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

    private fun request(argument: String, runtime: Boolean=false): String? {
        val manager = checkNotNull(ZuiControlManager.get()) { "系统规则服务不可用" }
        val capabilities = manager.getCapabilities()
        check(capabilities.length <= 8192 && capabilities.startsWith("ok=1\n")) { "规则读取能力不可用" }
        val transports = capabilities.lineSequence().filter { it.startsWith("rulesReadTransport=") }.toList()
        if (transports.isEmpty()) return null // Explicit old-ROM compatibility.
        check(transports == listOf("rulesReadTransport=$TRANSPORT")) { "规则读取能力不匹配" }
        if(runtime){
            val support=capabilities.lineSequence().filter{it.startsWith("rulesRuntimeReadTransport=")}.toList()
            if(support.isEmpty())return null
            check(support==listOf("rulesRuntimeReadTransport=DAEMON_RUNTIME_V1")){"运行态读取能力不匹配"}
        }
        var result = manager.utility("rulesRead", argument)
        repeat(3){if(result=="ok=0\nerror=rules_read_busy"){
            Thread.sleep(20)
            result=manager.utility("rulesRead",argument)
        }}
        if (result == "ok=0\nerror=rules_read_owner_absent") return null
        check(result.length <= PREFIX.length + 200000 && result.startsWith(PREFIX)) {
            "原生规则读取未完成：${result.take(256)}"
        }
        return result.removePrefix(PREFIX)
    }

    fun state(): String? = request("state")?.also(::validateState)
    fun runtime(state: String): org.json.JSONObject? {
        validateState(state)
        val frame=request("runtime|${ZuioptRules.field(state,"generation")}",true) ?: return null
        return decodeRuntime(frame,state)
    }
    internal fun decodeRuntime(frame: String, expected: String): org.json.JSONObject {
        validateState(expected);require(frame.length<=51000 && '\r' !in frame && '\u0000' !in frame)
        val parts=frame.split('\n');require(parts.size==4 && parts[0]=="ZUIOPT_RUNTIME_SNAPSHOT_V1" && parts[3].isEmpty())
        fun decoded(index: Int,key: String,limit: Int): String {
            require(parts[index].startsWith("$key="));val encoded=parts[index].removePrefix("$key=")
            require(encoded.length<=4*((limit+2)/3));val data=Base64.getDecoder().decode(encoded);require(data.size in 1..limit)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        }
        val state=decoded(1,"state",4608);validateState(state)
        for(key in identities+"loaded_generation")require(ZuioptRules.field(state,key)==ZuioptRules.field(expected,key)){"运行态规则身份已变化"}
        val owner=ZuioptRules.field(state,"loaded_generation");val identity=owner.split(':')
        require(identity.size==5 && identity[0]==ZuioptRules.field(state,"generation") && identity[1].matches(Regex("[0-9a-f-]{36}")) && identity[2].toInt()>0 && identity[3].toLong()>0 && identity[4]==ZuioptRules.field(state,"effective_sha256"))
        val text=decoded(2,"runtime",32768);require(text.endsWith('\n') && '\r' !in text && '\u0000' !in text)
        val json=org.json.JSONObject(text)
        val fields=setOf("schema","boot","owner","status","totalProcesses","processes","truncated")
        require(json.keys().asSequence().toSet() in setOf(fields,fields+"runtimeCapabilities") && json.getInt("schema")==2 && json.getString("owner")==owner && json.getString("boot")==identity[1])
        if(json.has("runtimeCapabilities")){
            val capabilities=json.getJSONArray("runtimeCapabilities");require(capabilities.length() in 1..16)
            val names=(0 until capabilities.length()).map{capabilities.getString(it)}
            require(names.toSet().size==names.size && names.all{it.matches(Regex("[a-z0-9-]{1,40}"))})
        }
        require(json.getString("status") in setOf("PENDING_OWNER_ACK","NO_RUNTIME_OBSERVATIONS","ACKNOWLEDGED_RUNTIME"))
        val processes=json.getJSONArray("processes");require(processes.length()<=json.getInt("totalProcesses") && json.getInt("totalProcesses")>=0 && (json.getBoolean("truncated") || processes.length()==json.getInt("totalProcesses")))
        val pids=mutableSetOf<Int>()
        for(i in 0 until processes.length()){
            val row=processes.getJSONObject(i)
            val fields=setOf("schema","boot","pid","processStart","uid","package","requestedMode","effectiveMode","modeReason","requestedRt","effectiveRt","rtReason","C1","C1Start","C2","C2Start")
            require(row.keys().asSequence().toSet() in setOf(fields,fields+"waltRestoreType"))
            if(row.has("waltRestoreType"))require(row.getString("waltRestoreType") in setOf("EXACT_RESTORE","SEMANTIC_RESTORE","FALLBACK"))
            require(row.getInt("schema")==1 && row.getString("boot")==identity[1] && row.getInt("pid")>0 && pids.add(row.getInt("pid")) && row.getLong("processStart")>0 && row.getInt("uid")>=10000)
            require(row.getString("package").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]*)+")))
            for(key in listOf("requestedMode","effectiveMode"))require(row.getInt(key) in 0..2)
            for(key in listOf("requestedRt","effectiveRt"))require(row.getInt(key) in 0..1)
            for(key in listOf("modeReason","rtReason"))require(row.getString(key).length<=128)
            for(key in listOf("C1","C2"))require(row.getInt(key)>=0 && (row.getInt(key)==0)==(row.getLong(key+"Start")==0L) && row.getLong(key+"Start")>=0)
        }
        return json
    }

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
