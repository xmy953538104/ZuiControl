package com.zui.zuicontrol

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object AppOptConfig {
    const val DISPLAY_PATH = "Download/ZuiControl/AppOpt.conf"
    internal const val DOWNLOAD_MIME_TYPE = "application/octet-stream"
    private const val DISPLAY_NAME = "AppOpt.conf"
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/ZuiControl/"

    fun parse(text: String): LinkedHashMap<String, AppOptRule> {
        val rules = linkedMapOf<String, AppOptRule>()
        text.lineSequence().forEachIndexed { index, source ->
            val line = source.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val fields = line.split('=', limit = 2)
            require(fields.size == 2 && PackageNames.isValid(fields[0])) {
                "第 ${index + 1} 行包名无效"
            }
            val preset = AppOptPreset.fromCpuSet(fields[1])
                ?: throw IllegalArgumentException("第 ${index + 1} 行预设无效")
            require(!rules.containsKey(fields[0])) { "第 ${index + 1} 行包名重复" }
            rules[fields[0]] = AppOptRule(fields[0], preset)
        }
        return rules
    }

    fun payload(rules: Map<String, AppOptRule>): String =
        rules.values.joinToString(";") { "${it.packageName}=${it.preset.cpuSet}" }

    fun text(rules: Map<String, AppOptRule>): String = buildString {
        appendLine("# ZuiControl AppOpt 配置")
        appendLine("# 格式：包名=0-7/0-4/5-7/0-1；只允许已安装用户 App")
        rules.values.forEach { appendLine("${it.packageName}=${it.preset.cpuSet}") }
    }

    fun read(context: Context): String {
        val uri = find(context) ?: throw IllegalStateException("配置文件不存在")
        return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("配置文件无法读取")
    }

    fun write(context: Context, rules: Map<String, AppOptRule>) {
        val resolver = context.contentResolver
        val uri = find(context) ?: resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, DISPLAY_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, DOWNLOAD_MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            },
        ) ?: throw IllegalStateException("配置文件无法创建")
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
            it.write(text(rules))
        } ?: throw IllegalStateException("配置文件无法写入")
    }

    fun ensure(context: Context, rules: Map<String, AppOptRule>) {
        if (find(context) == null) write(context, rules)
    }

    private fun find(context: Context): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(DISPLAY_NAME, relativePath),
            "${MediaStore.MediaColumns._ID} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else Uri.withAppendedPath(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cursor.getLong(0).toString(),
            )
        }
    }
}
