package com.zui.zuicontrol

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Explicit release configuration; no authoritative production URLs were supplied. */
object RuleRemoteConfig {
    const val GITHUB_BASE = ""
    const val GITEE_BASE = ""
    val configured get() = GITHUB_BASE.isNotBlank()
    const val DIAGNOSTIC = "RULE_REMOTE_CONFIGURATION_REQUIRED"
}

/** App-layer, user-triggered transport. The existing native library owns all diffs and commits. */
class RuleUpstreamFetcher(private val primary: String = RuleRemoteConfig.GITHUB_BASE,
    private val mirror: String = RuleRemoteConfig.GITEE_BASE,
    private val fetch: (String, Int) -> ByteArray = ::httpsRead) {
    data class Latest(val revision: Long, val version: String, val date: String, val commit: String,
        val size: Int, val sha256: String, val soc: String, val topology: String, val rulesSchema: Int, val source: String) {
        init {
            require(revision in 1..9007199254740991L && size in 1..65536 && sha256.matches(Regex("[0-9a-f]{64}")))
            require(soc == "SM8650" && topology == "0-7" && rulesSchema == 2) { "RULE_TARGET_MISMATCH" }
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
            require(bytes.toString(Charsets.UTF_8).lineSequence().any { it.trim() == "schema 2" }) { "RULE_SCHEMA_MISMATCH" }
        }
    }
    private fun configured() {
        check(primary.isNotBlank()) { RuleRemoteConfig.DIAGNOSTIC }
        for (base in listOf(primary, mirror).filter { it.isNotBlank() }) {
            val url = URL(base)
            require(url.protocol == "https" && url.userInfo == null && url.query == null && url.ref == null)
        }
    }
    private fun installedRevision(baseline: ZuioptLibrary.Baseline): Long =
        Regex("publisher-revision:([0-9]+)").matchEntire(baseline.metadata.optString("sourceEvidence"))
            ?.groupValues?.get(1)?.toLongOrNull() ?: error("RULE_BASELINE_REVISION_REQUIRED")
    private fun readLatest(base: String): Latest {
        val bytes = fetch(base.trimEnd('/') + "/latest.json", 2048)
        require(bytes.size in 1..2048) { "RULE_SIZE_LIMIT" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.keys().asSequence().toSet() == setOf("schema", "revision", "source", "sourceVersion", "sourceDate", "sourceCommit",
            "targetSoC", "targetTopology", "rulesSchema", "rulesSha256", "rulesSize")) { "RULE_MANIFEST_FIELDS" }
        require(json.getInt("schema") == 1)
        return Latest(json.getLong("revision"), json.getString("sourceVersion"), json.getString("sourceDate"),
            json.getString("sourceCommit"), json.getInt("rulesSize"), json.getString("rulesSha256"),
            json.getString("targetSoC"), json.getString("targetTopology"), json.getInt("rulesSchema"), json.getString("source"))
    }
    private fun pair(): Pair<Latest?, Latest?> {
        fun available(base: String) = try { readLatest(base) } catch (_: IOException) { null }
        val a = available(primary); val b = if (mirror.isNotBlank()) available(mirror) else null
        check(a != null || b != null) { "RULE_SOURCES_UNAVAILABLE" }
        check(a == null || b == null || a == b) { "RULE_SOURCE_MISMATCH" }
        return a to b
    }
    fun check(baseline: ZuioptLibrary.Baseline): Latest? {
        configured()
        val (a, b) = pair(); val latest = checkNotNull(a ?: b)
        return latest.takeIf { it.newerThan(installedRevision(baseline), baseline.hash) }
    }
    fun download(latest: Latest, baseline: ZuioptLibrary.Baseline): ByteArray {
        configured()
        val (a, b) = pair()
        check((a ?: b) == latest) { "RULE_SOURCE_CHANGED_RECHECK_REQUIRED" }
        val revisions = installedRevision(baseline)
        fun rules(base: String, manifest: Latest?): ByteArray? {
            if (manifest == null) return null
            val bytes = try { fetch(base.trimEnd('/') + "/rules.conf", 65536) } catch (_: IOException) { return null }
            latest.verify(bytes, revisions); return bytes
        }
        val first = rules(primary, a); val second = rules(mirror, b)
        check(first == null || second == null || first.contentEquals(second)) { "RULE_SOURCE_MISMATCH" }
        return checkNotNull(first ?: second) { "RULE_SOURCES_UNAVAILABLE" }
    }
    companion object {
        internal fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun httpsRead(url: String, limit: Int): ByteArray {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            try {
                if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
                require(connection.contentLengthLong <= limit) { "RULE_SIZE_LIMIT" }
                return connection.inputStream.use { ZuioptRules.boundedRead(it, limit) }
            } finally { connection.disconnect() }
        }
    }
}
