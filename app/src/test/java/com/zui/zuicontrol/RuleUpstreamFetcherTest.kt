package com.zui.zuicontrol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.zip.ZipInputStream

class RuleUpstreamFetcherTest {
    private val source="https://raw.githubusercontent.com/qualification/controlled/main/output"
    private val runtime=ZuioptRuleModel.parse("schema 3\nenabled true\ndebug false\nruntime_defaults 2 1\nprofile P 2-6\nruntime P -1 -1 preset double\npackage exact org.example.game P 100\n").normalized().toByteArray()
    private fun baseline(revision: Long=0,hash: String="0".repeat(64))=ZuioptLibrary.Baseline("g"+"a".repeat(24),hash,"",if(revision==0L)JSONObject().put("source","FROZEN_FACTORY_SEED").put("rulesHash",hash)else JSONObject().put("sourceEvidence","publisher-revision:$revision"))
    private fun manifest(bytes: ByteArray=runtime,revision: Long=2): JSONObject {
        val model=ZuioptRuleModel.parseNormalized(bytes.toString(Charsets.UTF_8))
        val json=JSONObject().put("schema",if(model.schema==3)2 else 1).put("revision",revision).put("source","controlled")
            .put("sourceVersion","R3").put("sourceDate","2026-10-13").put("sourceCommit","b".repeat(40))
            .put("targetSoC","SM8650").put("targetTopology","0-7").put("rulesSchema",model.schema)
            .put("rulesSha256",RuleUpstreamFetcher.hash(bytes)).put("rulesSize",bytes.size)
        if(model.schema==3)json.put("requiredCapabilities",JSONArray(RuleUpstreamFetcher.required(model).sorted()))
        return json
    }
    private fun rejected(block: () -> Unit): String {
        try{block()}catch(e:IllegalArgumentException){return e.message.orEmpty()}catch(e:IllegalStateException){return e.message.orEmpty()}
        throw AssertionError("invalid fetch accepted")
    }
    @Test fun schema3FetchPackAndInheritance(){
        val metadata=manifest().toString().toByteArray();var reads=0
        val fetcher=RuleUpstreamFetcher(source,capabilities={RuleUpstreamFetcher.CAPABILITIES}){url,_->reads++;if(url.endsWith("latest.json"))metadata else runtime}
        val base=baseline();val latest=checkNotNull(fetcher.check(base));val downloaded=fetcher.download(latest,base)
        assertArrayEquals(runtime,downloaded);assertEquals(3,reads)
        val packed=ZuioptLibrary.pack(base,ZuioptLibrary.Provenance("controlled","R3",commit="b".repeat(40),evidence="publisher-revision:2"),downloaded)
        ZipInputStream(packed.inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break;if(entry.name=="rules.conf")assertArrayEquals(runtime,zip.readBytes())}}
        val model=ZuioptRuleModel.parseNormalized(downloaded.toString(Charsets.UTF_8));assertEquals(-1,model.profiles.getValue("P").runtime.mode);assertEquals(-1,model.profiles.getValue("P").runtime.rt)
    }
    @Test fun missingAndChangedCapabilitiesReject(){
        var available=RuleUpstreamFetcher.CAPABILITIES;val metadata=manifest().toString().toByteArray()
        val fetcher=RuleUpstreamFetcher(source,capabilities={available}){url,_->if(url.endsWith("latest.json"))metadata else runtime}
        val latest=checkNotNull(fetcher.check(baseline()));available-= "walt-reduce-restorable"
        assertTrue(rejected{fetcher.check(baseline())}.contains("RULE_CAPABILITIES_MISSING"))
        assertTrue(rejected{fetcher.download(latest,baseline())}.contains("RULE_CAPABILITIES_MISSING"))
    }
    @Test fun schema2CompatibleWithoutRuntimeCapabilities(){
        val bytes="schema 2\nenabled true\ndebug false\n".toByteArray();val metadata=manifest(bytes).toString().toByteArray()
        val fetcher=RuleUpstreamFetcher(source,capabilities={error("Schema2 must not request runtime capabilities")}){url,_->if(url.endsWith("latest.json"))metadata else bytes}
        assertArrayEquals(bytes,fetcher.download(checkNotNull(fetcher.check(baseline())),baseline()))
    }
    @Test fun integrityTargetAndInterruptedDownloadReject(){
        for((key,value) in listOf("rulesSha256" to "0".repeat(64),"rulesSize" to runtime.size+1,"targetTopology" to "0-8","rulesSchema" to "3")){
            val metadata=manifest().put(key,value).toString().toByteArray()
            val fetcher=RuleUpstreamFetcher(source,capabilities={RuleUpstreamFetcher.CAPABILITIES}){url,_->if(url.endsWith("latest.json"))metadata else runtime}
            rejected{val latest=checkNotNull(fetcher.check(baseline()));fetcher.download(latest,baseline())}
        }
        val metadata=manifest().toString().toByteArray()
        val broken=RuleUpstreamFetcher(source,capabilities={RuleUpstreamFetcher.CAPABILITIES}){url,_->if(url.endsWith("latest.json"))metadata else throw IOException("interrupted")}
        assertTrue(rejected{broken.download(checkNotNull(broken.check(baseline())),baseline())}.contains("RULE_SOURCES_UNAVAILABLE"))
    }
    @Test fun revisionsSourceChangesAndFactoryMigration(){
        var metadata=manifest().toString().toByteArray()
        val fetcher=RuleUpstreamFetcher(source,capabilities={RuleUpstreamFetcher.CAPABILITIES}){url,_->if(url.endsWith("latest.json"))metadata else runtime}
        val latest=checkNotNull(fetcher.check(baseline()));assertNull(fetcher.check(baseline(2,RuleUpstreamFetcher.hash(runtime))))
        assertTrue(rejected{fetcher.check(baseline(2))}.contains("RULE_REVISION_HASH_MISMATCH"))
        assertTrue(rejected{fetcher.check(baseline(3))}.contains("RULE_REVISION_REGRESSION"))
        metadata=manifest(revision=3).toString().toByteArray();assertTrue(rejected{fetcher.download(latest,baseline())}.contains("RULE_SOURCE_CHANGED_RECHECK_REQUIRED"))
        rejected{fetcher.check(baseline().copy(metadata=JSONObject().put("sourceEvidence","unknown")))}
    }
    @Test fun githubOnlyAndContentCapabilitiesAgree(){
        rejected{RuleUpstreamFetcher("https://gitee.com/controlled",fetch={_,_->error("network must not start")}).check(baseline())}
        val missing=RuleUpstreamFetcher.BASE_CAPABILITIES+"mode2"+"marker-double"
        val metadata=manifest().put("requiredCapabilities",JSONArray(missing.sorted())).toString().toByteArray()
        val fetcher=RuleUpstreamFetcher(source,capabilities={RuleUpstreamFetcher.CAPABILITIES}){url,_->if(url.endsWith("latest.json"))metadata else runtime}
        assertTrue(rejected{fetcher.download(checkNotNull(fetcher.check(baseline())),baseline())}.contains("RULE_CAPABILITIES_CONTENT_MISMATCH"))
    }
    @Test fun mirrorUnavailableOrLaggingDoesNotBlockPrimary(){
        val mirror=RuleRemoteConfig.GITEE_BASE
        var mirrorManifest: ByteArray?=null;var ruleReads=0
        val fetcher=RuleUpstreamFetcher(source,mirror,{RuleUpstreamFetcher.CAPABILITIES}){url,_->
            if(url.startsWith(mirror)){mirrorManifest?:throw IOException("HTTP 302")}
            else if(url.endsWith("latest.json"))manifest().toString().toByteArray()
            else {ruleReads++;runtime}
        }
        val latest=checkNotNull(fetcher.check(baseline()));assertEquals(0,ruleReads)
        assertEquals("UNAVAILABLE_HTTP_302",fetcher.mirrorStatus)
        mirrorManifest=manifest(revision=1).toString().toByteArray()
        assertEquals(latest,fetcher.check(baseline()));assertEquals("LAGGING_NOT_SELECTED",fetcher.mirrorStatus)
        assertArrayEquals(runtime,fetcher.download(latest,baseline()));assertEquals(source,fetcher.downloadedSource)
        assertEquals(1,ruleReads)
    }
    @Test fun fallbackRequiresTheFrozenWholeManifest(){
        var failed=false;var mirrorBytes=manifest().toString().toByteArray()
        val mirror=RuleRemoteConfig.GITEE_BASE
        val fetcher=RuleUpstreamFetcher(source,mirror,{RuleUpstreamFetcher.CAPABILITIES}){url,_->
            if(url.startsWith(source)&&failed)throw IOException("offline")
            if(url.endsWith("latest.json"))if(url.startsWith(mirror))mirrorBytes else manifest().toString().toByteArray() else runtime
        }
        val latest=checkNotNull(fetcher.check(baseline()));failed=true
        assertArrayEquals(runtime,fetcher.download(latest,baseline()));assertEquals(mirror,fetcher.downloadedSource)
        mirrorBytes=manifest(revision=1).toString().toByteArray()
        assertTrue(rejected{fetcher.download(latest,baseline())}.contains("RULE_SOURCE_CHANGED"))
        mirrorBytes=manifest().put("rulesSha256","f".repeat(64)).toString().toByteArray()
        assertTrue(rejected{fetcher.download(latest,baseline())}.contains("RULE_SOURCE_CHANGED"))
        mirrorBytes="<html>login</html>".toByteArray()
        try{fetcher.download(latest,baseline());fail("HTML accepted")}catch(_:org.json.JSONException){}
    }
    @Test fun onlyVerifiedSamePathGiteeCdnRedirectAllowed(){
        val original=java.net.URL(RuleRemoteConfig.GITEE_BASE+"/rules.conf")
        val good="https://raw.giteeusercontent.com${original.path}?anonymous-signature=fixture"
        assertEquals("raw.giteeusercontent.com",RuleUpstreamFetcher.mirrorRedirect(original,good).host)
        for(bad in listOf(good.replace("https:","http:"),good.replace("raw.giteeusercontent.com","evil.example"),
            good.replace("/rules.conf","/login"),good.replace("https://","https://user@"),good+"#fragment"))
            rejected{RuleUpstreamFetcher.mirrorRedirect(original,bad)}
        rejected{RuleUpstreamFetcher.mirrorRedirect(java.net.URL(source+"/rules.conf"),good)}
    }
}
