package com.zui.zuicontrol

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object AppOptConfig {
    const val DISPLAY_PATH = "Download/ZuiControl/AppOpt.conf"
    const val TEMPLATE_ASSET = "appopt_sm8650_profiles.conf"
    private const val MAX_PAYLOAD_CHARS = 16_384
    internal const val DOWNLOAD_MIME_TYPE = "application/octet-stream"
    private const val DISPLAY_NAME = "AppOpt.conf"
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/ZuiControl/"
    private val threadPattern = Regex("^[A-Za-z0-9_.:+*? -]{1,31}$")

    fun parse(text: String): LinkedHashMap<String, AppOptRule> {
        data class Draft(
            var base: AppOptPreset? = null,
            val threads: MutableList<AppOptThreadRule> = mutableListOf(),
            val keys: MutableSet<String> = linkedSetOf(),
        )

        val drafts = linkedMapOf<String, Draft>()
        text.lineSequence().forEachIndexed { index, source ->
            val line = source.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val fields = line.split('=', limit = 2)
            require(fields.size == 2) { "第 ${index + 1} 行缺少 =" }
            val left = fields[0].trim()
            val cpuSet = fields[1].trim()
            val brace = left.indexOf('{')
            val packageName: String
            val thread: String?
            if (brace < 0) {
                packageName = left
                thread = null
            } else {
                require(left.endsWith('}') && left.indexOf('{', brace + 1) < 0) {
                    "第 ${index + 1} 行线程格式无效"
                }
                packageName = left.substring(0, brace)
                thread = left.substring(brace + 1, left.length - 1).trim()
                require(threadPattern.matches(thread)) {
                    "第 ${index + 1} 行线程名无效"
                }
            }
            require(PackageNames.isValid(packageName)) { "第 ${index + 1} 行包名无效" }
            val preset = AppOptPreset.fromCpuSet(cpuSet)
                ?: throw IllegalArgumentException("第 ${index + 1} 行 CPU 集合无效")
            val draft = drafts.getOrPut(packageName) { Draft() }
            val key = thread.orEmpty()
            require(draft.keys.add(key)) { "第 ${index + 1} 行规则重复" }
            if (thread == null) {
                draft.base = preset
            } else {
                draft.threads += AppOptThreadRule(thread, preset)
            }
        }

        val rules = linkedMapOf<String, AppOptRule>()
        drafts.forEach { (packageName, draft) ->
            val base = requireNotNull(draft.base) { "$packageName 缺少整包兜底规则" }
            rules[packageName] = AppOptRule(packageName, base, draft.threads.toList())
        }
        return rules
    }

    fun payload(rules: Map<String, AppOptRule>): String =
        canonicalLines(rules).joinToString(";").also {
            require(it.length <= MAX_PAYLOAD_CHARS) { "AppOpt 配置过大，请减少规则" }
        }

    fun text(rules: Map<String, AppOptRule>): String = buildString {
        appendLine("# ZuiControl AppOpt 配置")
        appendLine("# 包名=CPU集合 是未命中线程的兜底；包名{线程通配符}=CPU集合 是覆盖规则")
        appendLine("# CPU集合：单核心 0..7，或连续范围 X-Y（例如 2-7）")
        canonicalLines(rules).forEach(::appendLine)
    }

    fun totalRuleCount(rules: Map<String, AppOptRule>): Int =
        rules.values.sumOf(AppOptRule::totalRules)

    fun readTemplates(context: Context): LinkedHashMap<String, AppOptRule> =
        context.assets.open(TEMPLATE_ASSET).bufferedReader().use { parse(it.readText()) }

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

    private fun canonicalLines(rules: Map<String, AppOptRule>): List<String> = buildList {
        rules.values.forEach { rule ->
            add("${rule.packageName}=${rule.preset.cpuSet}")
            rule.threadRules.forEach { thread ->
                add("${rule.packageName}{${thread.pattern}}=${thread.preset.cpuSet}")
            }
        }
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
