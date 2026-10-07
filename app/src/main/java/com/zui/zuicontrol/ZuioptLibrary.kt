package com.zui.zuicontrol

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.GregorianCalendar
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Non-UI manual import/preview transport; native generation store owns all merge decisions. */
object ZuioptLibrary {
    enum class Decision { KEEP_MINE, USE_UPSTREAM, MANUAL_MERGE }
    data class Provenance(val source: String,val version: String,val date: String="",val commit: String="",val evidence: String)
    data class Baseline(val generation: String,val hash: String,val rules: String,val metadata: JSONObject)
    data class Preview(val transaction: String,val generation: String,val upstreamHash: String,val canonicalHash: String,val apps: List<JSONObject>)
    fun baseline(context: Context): Baseline {
        val state=ZuioptRules.state(context);val generation=ZuioptRules.field(state,"generation")
        fun read(kind: String,size: Int): ByteArray {
            require(size in 1..65536);val out=ByteArrayOutputStream()
            while(out.size()<size){
                val response=FrontendTransport.commandRead {
                    val id=ZuioptRules.command(context,"upstream_read",generation,"$kind:${out.size()}")
                    ZuiControlClient.utilityValue("result","$id|rulesChunk")
                }
                val part=response.split(':',limit=3)
                check(part.size==3&&part[0]==generation&&part[1]==out.size().toString())
                val bytes=Base64.getDecoder().decode(part[2]);require(bytes.size in 1..8192&&out.size()+bytes.size<=size);out.write(bytes)
            }
            return out.toByteArray()
        }
        val bytes=read("rules",ZuioptRules.field(state,"upstream_size").toInt())
        val hash=ZuioptRules.field(state,"upstream_sha256");check(ZuioptRules.digest(bytes)==hash)
        val metadata=read("metadata",ZuioptRules.field(state,"upstream_metadata_size").toInt())
        return Baseline(generation,hash,bytes.toString(Charsets.UTF_8),JSONObject(metadata.toString(Charsets.UTF_8)))
    }
    fun pack(baseline: Baseline, source: Provenance, rules: ByteArray, appOpt: Boolean=false,
             decisions: Map<String,Decision> = emptyMap(), manual: String="schema 2\nenabled true\ndebug false\n"): ByteArray {
        require(baseline.hash.matches(Regex("[0-9a-f]{64}"))&&rules.size in 1..ZuioptRules.RULE_LIMIT)
        require(source.source.isNotEmpty()&&source.version.isNotEmpty()&&(source.commit.isNotEmpty()||source.evidence.isNotEmpty()))
        for(text in listOf(source.source,source.version,source.date,source.commit,source.evidence))require(text.length<=128&&text.all { it.code in 32..126 })
        require(decisions.size<=512)
        val choiceText=if(decisions.isEmpty())"# KEEP_MINE for conflicts\n" else decisions.toSortedMap().entries.joinToString("\n",postfix="\n") {
            require(it.key.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")));"${it.key} ${it.value.name}"
        }
        val manualBytes=manual.toByteArray(Charsets.UTF_8);val choiceBytes=choiceText.toByteArray(Charsets.UTF_8)
        require(manualBytes.size in 1..65536&&choiceBytes.size in 1..65536)
        val manifest=JSONObject().put("schema",1).put("kind",if(appOpt)"APPOPT" else "UPSTREAM")
            .put("source",source.source).put("sourceVersion",source.version).put("sourceDate",source.date)
            .put("sourceCommit",source.commit).put("sourceEvidence",source.evidence).put("targetSoC","SM8650").put("targetTopology","0-7")
            .put("oldUpstreamHash",baseline.hash).put("newUpstreamHash",if(appOpt)baseline.hash else ZuioptRules.digest(rules))
            .put("rulesHash",ZuioptRules.digest(rules)).put("manualHash",ZuioptRules.digest(manualBytes)).put("decisionsHash",ZuioptRules.digest(choiceBytes))
        val files=linkedMapOf("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8),"rules.conf" to rules,"manual.conf" to manualBytes,"decisions.conf" to choiceBytes)
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for((name,bytes) in files){
                val entry=ZipEntry(name);entry.method=ZipEntry.STORED;entry.size=bytes.size.toLong();entry.compressedSize=entry.size
                entry.crc=CRC32().apply { update(bytes) }.value
                entry.time=GregorianCalendar().apply { clear();set(1980,0,1,0,0,0) }.timeInMillis
                zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry()
            }
        }
        return out.toByteArray().also { require(it.size<=ZuioptRules.PACK_LIMIT) }
    }
    fun preview(context: Context, bytes: ByteArray, baseline: Baseline): Preview {
        require(bytes.size in 1..ZuioptRules.PACK_LIMIT)
        val tx=ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        ZuioptRules.command(context,"begin",tx,"library:${bytes.size}:${ZuioptRules.digest(bytes)}:-:0:${baseline.generation}")
        try{
            for(offset in bytes.indices step 8192)ZuioptRules.command(context,"chunk","$tx:$offset",Base64.getEncoder().encodeToString(bytes.copyOfRange(offset,minOf(offset+8192,bytes.size))))
            val rows=mutableListOf<JSONObject>();var count=0;var digest=""
            do{
                val id=ZuioptRules.command(context,"preview",tx,rows.size.toString())
                val page=JSONObject(ZuiControlClient.utilityValue("result","$id|rulesPreview"))
                if(rows.isEmpty()){count=page.getInt("count");digest=page.getString("canonicalHash");require(count in 0..512)}
                check(page.getString("canonicalHash")==digest&&page.getInt("count")==count&&page.getInt("offset")==rows.size)
                val apps=page.getJSONArray("apps");require(apps.length() in 0..32&&rows.size+apps.length()<=count)
                check(apps.length()>0||rows.size==count);for(i in 0 until apps.length())rows+=apps.getJSONObject(i)
            }while(rows.size<count)
            return Preview(tx,baseline.generation,baseline.hash,digest,rows)
        }catch(e: Exception){runCatching { ZuioptRules.command(context,"abort",tx) };throw e}
    }
    fun confirm(context: Context, preview: Preview){ZuioptRules.command(context,"commit",preview.transaction)}
    fun cancel(context: Context, preview: Preview){ZuioptRules.command(context,"abort",preview.transaction)}
    fun restoreApp(context: Context, packageName: String, baseline: Baseline){
        ZuioptRules.command(context,"restore_app",baseline.generation,"$packageName:${baseline.hash}")
    }
}
