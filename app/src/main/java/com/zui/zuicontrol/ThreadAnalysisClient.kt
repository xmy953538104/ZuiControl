package com.zui.zuicontrol

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Explicit system_server analysis. Rebinding/reading never starts a session. */
object ThreadAnalysisClient {
    private fun call(action: String, argument: JSONObject = JSONObject()): String =
        PerformanceMonitor.command(action,argument.toString()).also { check(!it.startsWith("ok=0")) { it } }
    fun start(context: Context, packageName: String, activeMs: Long): JSONObject {
        require(activeMs in setOf(0L,120000L,300000L,600000L))
        ZuioptRules.state(context) // Refresh the existing native generation observation before this explicit session.
        return JSONObject(call("analysisStart",JSONObject().put("package",packageName).put("activeMs",activeMs)))
    }
    fun state(): JSONObject = JSONObject(call("analysisState"))
    fun stop(session: String): JSONObject = JSONObject(call("analysisStop",JSONObject().put("session",session)))
    fun delete(packageName: String) { call("analysisDelete",JSONObject().put("package",packageName)) }
    fun read(packageName: String): JSONObject {
        val out=ByteArrayOutputStream();var hash="";var size=0
        do {
            val part=JSONObject(call("analysisRead",JSONObject().put("package",packageName).put("offset",out.size()).put("hash",hash)))
            if(part.length()==0){check(out.size()==0);return part}
            if(out.size()==0){hash=part.getString("hash");size=part.getInt("size");require(size in 1..524288)}
            require(part.getString("hash")==hash&&part.getInt("size")==size&&part.getInt("offset")==out.size())
            val chunk=Base64.getDecoder().decode(part.getString("data"));require(chunk.size in 1..8192&&out.size()+chunk.size<=size);out.write(chunk)
        }while(out.size()<size)
        val bytes=out.toByteArray();check(ZuioptRules.digest(bytes)==hash)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
}
