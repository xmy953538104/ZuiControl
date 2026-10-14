package com.zui.zuicontrol

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One logical feed, edited/published on GitHub; Gitee is a read-only mirror. */
object RuleRemoteConfig {
    const val GITHUB_BASE = "https://raw.githubusercontent.com/xmy953538104/ZuiControl-Rules/main/output"
    const val GITEE_BASE = "https://gitee.com/xmy953538104/ZuiControl-Rules/raw/main/output"
    val configured get() = GITHUB_BASE.isNotBlank()
    const val DIAGNOSTIC = "RULE_REMOTE_CONFIGURATION_REQUIRED"
}

/** App-layer, user-triggered transport. The existing native library owns all diffs and commits. */
class RuleUpstreamFetcher(private val primary: String = RuleRemoteConfig.GITHUB_BASE,
    private val mirror: String = if(primary==RuleRemoteConfig.GITHUB_BASE)RuleRemoteConfig.GITEE_BASE else "",
    private val capabilities: () -> Set<String> = { emptySet() },
    private val fetch: (String, Int) -> ByteArray = ::httpsRead) {
    var checkedSource: String = ""; private set
    var downloadedSource: String = ""; private set
    var mirrorStatus: String = "NOT_CHECKED"; private set
    data class Latest(val revision: Long, val version: String, val date: String, val commit: String,
        val size: Int, val sha256: String, val soc: String, val topology: String, val rulesSchema: Int, val source: String,
        val requiredCapabilities: Set<String> = emptySet()) {
        init {
            require(revision in 1..9007199254740991L && size in 1..65536 && sha256.matches(Regex("[0-9a-f]{64}")))
            require(soc == "SM8650" && topology == "0-7" && rulesSchema == 3) { "RULE_TARGET_MISMATCH" }
            require(BASE_CAPABILITIES.all{it in requiredCapabilities} && requiredCapabilities.all{it in CAPABILITIES}){"RULE_REQUIRED_CAPABILITIES"}
            require(source.matches(Regex("[A-Za-z0-9_.:/-]{1,128}")) && version.matches(Regex("[A-Za-z0-9_.+-]{1,64}")))
            require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && commit.matches(Regex("[0-9a-f]{40}")))
            java.time.LocalDate.parse(date)
        }
        fun newerThan(revision: Long, hash: String): Boolean {
            require(this.revision >= revision) { "RULE_REVISION_REGRESSION" }
            require(this.revision != revision || sha256 == hash) { "RULE_REVISION_HASH_MISMATCH" }
            return this.revision > revision && sha256 != hash
        }
        fun verify(bytes: ByteArray, installedRevision: Long) {
            require(revision > installedRevision) { "RULE_REVISION_REGRESSION" }
            require(bytes.size == size && sha256 == hash(bytes)) { "RULE_INTEGRITY_MISMATCH" }
            require(bytes.all{it.toInt() in 9..13 || it.toInt() in 32..126}){"RULE_ASCII_REQUIRED"}
            val model=ZuioptRuleModel.parseNormalized(bytes.toString(Charsets.UTF_8))
            require(model.schema==rulesSchema) { "RULE_SCHEMA_MISMATCH" }
            require(required(model)==requiredCapabilities){"RULE_CAPABILITIES_CONTENT_MISMATCH"}
        }
    }
    private fun configured() {
        check(primary.isNotBlank()) { RuleRemoteConfig.DIAGNOSTIC }
        for (base in listOf(primary, mirror).filter { it.isNotBlank() }) {
            val url = URL(base)
            require(url.protocol == "https" && url.userInfo == null && url.query == null && url.ref == null)
            require(url.port == -1 || url.port == 443) { "RULE_SOURCE_PORT" }
            require(if(base==primary) url.host in setOf("raw.githubusercontent.com","github.com","api.github.com")
                else base.trimEnd('/') == RuleRemoteConfig.GITEE_BASE) { "RULE_SOURCE_NOT_ALLOWED" }
        }
    }
    private fun installedRevision(baseline: ZuioptLibrary.Baseline): Long {
        val evidence=baseline.metadata.optString("sourceEvidence")
        if(baseline.metadata.optString("source")=="FROZEN_FACTORY_SEED" && baseline.metadata.optString("rulesHash")==baseline.hash)return 0
        return Regex("publisher-revision:([0-9]+)").matchEntire(evidence)
            ?.groupValues?.get(1)?.toLongOrNull()?.takeIf{it in 1..9007199254740991L} ?: error("RULE_BASELINE_REVISION_REQUIRED")
    }
    private fun requireCapabilities(latest: Latest){
        val missing=latest.requiredCapabilities-capabilities();check(missing.isEmpty()){"RULE_CAPABILITIES_MISSING:"+missing.sorted().joinToString(",")}
    }
    private fun readLatest(base: String): Latest {
        val bytes = fetch(base.trimEnd('/') + "/latest.json", 2048)
        require(bytes.size in 1..2048) { "RULE_SIZE_LIMIT" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        for(key in listOf("schema","revision","rulesSchema","rulesSize"))require(json.get(key) is Int || json.get(key) is Long){"RULE_MANIFEST_NUMBER_TYPE"}
        for(key in listOf("source","sourceVersion","sourceDate","sourceCommit","targetSoC","targetTopology","rulesSha256"))require(json.get(key) is String){"RULE_MANIFEST_STRING_TYPE"}
        val schema=json.getInt("rulesSchema")
        val fields=setOf("schema", "revision", "source", "sourceVersion", "sourceDate", "sourceCommit","targetSoC", "targetTopology", "rulesSchema", "rulesSha256", "rulesSize")
        require(schema==3 && json.keys().asSequence().toSet() == fields+"requiredCapabilities") { "RULE_MANIFEST_FIELDS" }
        require(json.getInt("schema") == 2)
        val list=json.getJSONArray("requiredCapabilities");require(list.length() in 4..16);val values=(0 until list.length()).map{list.getString(it)};require(values==values.distinct().sorted());val required=values.toSet()
        return Latest(json.getLong("revision"), json.getString("sourceVersion"), json.getString("sourceDate"),
            json.getString("sourceCommit"), json.getInt("rulesSize"), json.getString("rulesSha256"),
            json.getString("targetSoC"), json.getString("targetTopology"), schema, json.getString("source"),required)
    }
    fun check(baseline: ZuioptLibrary.Baseline): Latest? {
        configured()
        checkedSource = ""
        val latest = try { readLatest(primary).also { checkedSource=primary } }
            catch (primaryError: IOException) {
                check(mirror.isNotBlank()) { "RULE_SOURCES_UNAVAILABLE" }
                try { readLatest(mirror).also { checkedSource=mirror } }
                catch (mirrorError: IOException) { throw IllegalStateException("RULE_SOURCES_UNAVAILABLE",mirrorError) }
            }
        requireCapabilities(latest)
        mirrorStatus = if(mirror.isBlank())"NOT_CONFIGURED" else if(checkedSource==mirror)"MANIFEST_FALLBACK" else try {
            val mirrored=readLatest(mirror)
            when { mirrored==latest->"MANIFEST_MATCH";mirrored.revision<latest.revision->"LAGGING_NOT_SELECTED"
                else->"DIFFERENT_IDENTITY_NOT_SELECTED" }
        } catch (e: IOException) { "UNAVAILABLE_"+(e.message?.takeIf{it.matches(Regex("HTTP [0-9]{3}"))}?.replace(' ','_') ?: "IO") }
        catch (_: Exception) { "INVALID_MANIFEST_NOT_SELECTED" }
        return latest.takeIf { it.newerThan(installedRevision(baseline), baseline.hash) }
    }
    fun download(latest: Latest, baseline: ZuioptLibrary.Baseline): ByteArray {
        configured()
        requireCapabilities(latest)
        val revisions = installedRevision(baseline)
        downloadedSource = ""
        fun rules(base: String): ByteArray {
            check(readLatest(base) == latest) { "RULE_SOURCE_CHANGED_RECHECK_REQUIRED" }
            val bytes = fetch(base.trimEnd('/') + "/rules.conf", latest.size)
            latest.verify(bytes, revisions)
            downloadedSource=base
            return bytes
        }
        return try { rules(primary) } catch (primaryError: IOException) {
            check(mirror.isNotBlank()) { "RULE_SOURCES_UNAVAILABLE" }
            try { rules(mirror) }
            catch (mirrorError: IOException) { throw IllegalStateException("RULE_SOURCES_UNAVAILABLE",mirrorError) }
        }
    }
    companion object {
        internal val BASE_CAPABILITIES=setOf("schema3","category-ranking","runtime-inheritance","owned-rt-recovery")
        internal val CAPABILITIES=BASE_CAPABILITIES+setOf("mode0","mode1","mode2","marker-dummy","marker-double","walt-boost-restorable","walt-reduce-restorable")
        internal fun required(model: ZuioptRuleModel): Set<String> {
            require(model.schema==3) { "RULE_SCHEMA3_REQUIRED" }
            val needed=BASE_CAPABILITIES.toMutableSet()
            for(profile in model.profiles.values){val runtime=profile.runtime;val mode=if(runtime.mode<0)model.mode else runtime.mode;val rt=if(runtime.rt<0)model.rt else runtime.rt
                needed+="mode$mode";if(mode==2 && runtime.placement=="preset")needed+=if(rt==1)"walt-reduce-restorable" else "walt-boost-restorable"
                if(runtime.marker!="none")needed+="marker-${runtime.marker}"
            }
            if(model.profiles.isEmpty())needed+="mode${model.mode}";return needed
        }
        internal fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        internal fun mirrorRedirect(original: URL, location: String): URL {
            val next=URL(original,location)
            val path=URL(RuleRemoteConfig.GITEE_BASE).path
            require(original.host=="gitee.com" && original.path in setOf("$path/latest.json","$path/rules.conf") &&
                next.protocol=="https" && next.host=="raw.giteeusercontent.com" && next.path==original.path &&
                next.userInfo==null && next.ref==null && (next.port==-1 || next.port==443)) { "RULE_REDIRECT_NOT_ALLOWED" }
            return next
        }
        private fun httpsRead(url: String, limit: Int): ByteArray {
            var target=URL(url)
            for(hop in 0..1){
                val connection = target.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000; connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                try {
                    val status=connection.responseCode
                    if(status in setOf(301,302,307,308) && hop==0){
                        target=mirrorRedirect(target,connection.getHeaderField("Location") ?: throw IOException("RULE_REDIRECT_LOCATION"))
                        continue
                    }
                    if(status!=200)throw IOException("HTTP $status")
                    require(connection.contentLengthLong <= limit) { "RULE_SIZE_LIMIT" }
                    return connection.inputStream.use { ZuioptRules.boundedRead(it, limit) }
                } finally { connection.disconnect() }
            }
            throw IOException("RULE_REDIRECT_LIMIT")
        }
    }
}
