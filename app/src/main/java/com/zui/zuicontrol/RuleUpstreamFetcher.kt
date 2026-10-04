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
    val configured get() = GITHUB_BASE.isNotBlank() && GITEE_BASE.isNotBlank()
    const val DIAGNOSTIC = "RULE_REMOTE_CONFIGURATION_REQUIRED"
}

/** App-layer, user-triggered transport. The existing native library owns all diffs and commits. */
class RuleUpstreamFetcher(private val primary: String = RuleRemoteConfig.GITHUB_BASE,
    private val mirror: String = RuleRemoteConfig.GITEE_BASE,
    private val fetch: (String, Int) -> ByteArray = ::httpsRead) {
    data class Latest(val revision: Long, val version: String, val date: String, val commit: String,
        val size: Int, val sha256: String, val soc: String, val topology: String, val ruleSchema: Int) {
        init {
            require(revision >= 0 && size in 1..65536 && sha256.matches(Regex("[0-9a-f]{64}")))
            require(soc == "SM8650" && topology == "0-7" && ruleSchema == 2) { "RULE_TARGET_MISMATCH" }
            for (field in listOf(version, date, commit)) require(field.isNotEmpty() && field.length <= 128 && field.all { it.code in 32..126 })
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
        check(primary.isNotBlank() && mirror.isNotBlank()) { RuleRemoteConfig.DIAGNOSTIC }
        for (base in listOf(primary, mirror)) {
            val url = URL(base)
            require(url.protocol == "https" && url.userInfo == null && url.query == null && url.ref == null)
        }
    }
    private fun installedRevision(baseline: ZuioptLibrary.Baseline): Long =
        Regex("revision:([0-9]+)").matchEntire(baseline.metadata.optString("sourceEvidence"))
            ?.groupValues?.get(1)?.toLongOrNull() ?: error("RULE_BASELINE_REVISION_REQUIRED")
    private fun readLatest(base: String): Latest {
        val json = JSONObject(fetch(base.trimEnd('/') + "/latest.json", 8192).toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1)
        return Latest(json.getLong("revision"), json.getString("version"), json.getString("date"),
            json.getString("commit"), json.getInt("size"), json.getString("sha256"),
            json.getString("targetSoC"), json.getString("targetTopology"), json.getInt("ruleSchema"))
    }
    private fun pair(): Pair<Latest?, Latest?> {
        fun available(base: String) = try { readLatest(base) } catch (_: IOException) { null }
        val a = available(primary); val b = available(mirror)
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
