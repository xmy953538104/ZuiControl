package com.zui.zuicontrol

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64

/** SAF transport only. All validation, durable decisions and owner ACKs belong to system_server. */
internal object SettingsBackup {
    private const val LIMIT=196608
    private fun transport(action: String, argument: String=""): String = PerformanceMonitor.command(action,argument).also {
        check(!it.startsWith("ok=0")) { it }
    }
    private fun command(context: Context, action: String, tx: String=""): ZuiControlRequest.Ack {
        val id=ZuiControlRequest.send(context,action,pkg=tx)
        return ZuiControlRequest.awaitTerminalAck(context,id,timeoutMs=60000).also { check(it.succeeded) { it.detail } }
    }
    fun export(context: Context): ByteArray {
        val ack=command(context,"sb_export")
        val fields=ack.detail.split(';').mapNotNull { val p=it.indexOf('=');if(p<0)null else it.substring(0,p) to it.substring(p+1) }.toMap()
        val size=fields.getValue("backupSize").toInt();val hash=fields.getValue("backupHash")
        require(size in 1..LIMIT&&hash.matches(Regex("[0-9a-f]{64}")))
        val out=ByteArrayOutputStream()
        while(out.size()<size){val chunk=Base64.getDecoder().decode(transport("backupRead","$hash:${out.size()}"))
            require(chunk.isNotEmpty()&&out.size()+chunk.size<=size);out.write(chunk)}
        return out.toByteArray().also { check(ZuioptRules.digest(it)==hash) { "备份传输哈希不一致" } }
    }
    fun save(context: Context, uri: Uri, bytes: ByteArray) {
        require(bytes.isNotEmpty())
        checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes);it.flush() }
        val read=checkNotNull(context.contentResolver.openInputStream(uri)).use { ZuioptRules.boundedRead(it,LIMIT) }
        check(read.contentEquals(bytes)) { "目标文件未完整保存，请勿使用该文件" }
    }
    fun restore(context: Context, uri: Uri) {
        val bytes=checkNotNull(context.contentResolver.openInputStream(uri)).use { ZuioptRules.boundedRead(it,LIMIT) }
        val tx=ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        transport("backupBegin",JSONObject().put("transaction",tx).put("size",bytes.size).put("hash",ZuioptRules.digest(bytes)).toString())
        for(offset in bytes.indices step 8192)transport("backupChunk",JSONObject().put("transaction",tx).put("offset",offset)
            .put("data",Base64.getEncoder().encodeToString(bytes.copyOfRange(offset,minOf(offset+8192,bytes.size)))).toString())
        command(context,"sb_restore",tx)
        context.startService(android.content.Intent(context,ZuiControlQuickService::class.java).setAction("com.zui.zuicontrol.SETTINGS_RESTORED"))
    }
}
