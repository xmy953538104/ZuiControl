package com.zui.perfctl

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        refreshStatus()
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "ZuiperfCtl"
            textSize = 24f
            setTextColor(0xff111827.toInt())
        })

        root.addView(TextView(this).apply {
            text = "系统调度 / AsoulOpt / 刷新率"
            textSize = 14f
            setTextColor(0xff4b5563.toInt())
            setPadding(0, dp(4), 0, dp(14))
        })

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xff111827.toInt())
            setBackgroundColor(0xffeef2f7.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(statusView, matchWrap())

        section(root, "ZuiPP XML 调度")
        row(root,
            actionButton("应用 XML") { sendRequest("apply_zuipp") },
            actionButton("恢复官方") { sendRequest("restore_zuipp") },
        )
        row(root,
            actionButton("重启 PP") { sendRequest("restart_zuipp") },
            actionButton("刷新状态") { refreshStatus() },
        )

        section(root, "AsoulOpt")
        row(root,
            actionButton("应用配置") { sendRequest("apply_asoul") },
            actionButton("恢复配置") { sendRequest("restore_asoul") },
        )
        row(root,
            actionButton("重启服务") { sendRequest("restart_asoul") },
        )

        section(root, "手动锁帧")
        row(root,
            actionButton("60 Hz") { sendRequest("set_refresh", 60) },
            actionButton("90 Hz") { sendRequest("set_refresh", 90) },
        )
        row(root,
            actionButton("120 Hz") { sendRequest("set_refresh", 120) },
            actionButton("144 Hz") { sendRequest("set_refresh", 144) },
        )
        row(root,
            actionButton("恢复系统") { sendRequest("restore_refresh") },
        )

        section(root, "按前台应用切换")
        row(root,
            actionButton("启用") { sendRequest("enable_auto_refresh") },
            actionButton("停用") { sendRequest("disable_auto_refresh") },
        )

        root.addView(TextView(this).apply {
            text = "配置文件位置：/data/zui_perfctl/zuipp、/data/zui_perfctl/asoul、/data/zui_perfctl/refresh。第一版先保留文件级编辑入口，后续再做游戏档位表单。"
            textSize = 12f
            setTextColor(0xff6b7280.toInt())
            setPadding(0, dp(18), 0, 0)
        })

        return scroll
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xff111827.toInt())
            setPadding(0, dp(22), 0, dp(8))
        })
    }

    private fun row(root: LinearLayout, vararg buttons: Button) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.forEach { button ->
            line.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(0, 0, dp(8), dp(8))
            })
        }
        root.addView(line, matchWrap())
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun sendRequest(cmd: String, rate: Int? = null) {
        runCatching {
            val request = buildString {
                appendLine("id=${System.currentTimeMillis()}")
                appendLine("cmd=$cmd")
                if (rate != null) appendLine("rate=$rate")
            }
            val tmp = File(filesDir, "request.prop.tmp")
            val dst = File(filesDir, "request.prop")
            tmp.writeText(request)
            if (dst.exists() && !dst.delete()) {
                error("delete old request failed")
            }
            if (!tmp.renameTo(dst)) {
                dst.writeText(request)
                tmp.delete()
            }
        }.onSuccess {
            statusView.text = "已发送：$cmd${rate?.let { " $it Hz" } ?: ""}\n时间：${timeFormat.format(Date())}"
        }.onFailure { error ->
            statusView.text = "发送失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun refreshStatus() {
        val status = File(filesDir, "status.prop")
        statusView.text = if (status.isFile) {
            status.readText().trim().ifBlank { "守护服务状态为空" }
        } else {
            "还没有收到守护服务状态。先确认系统内置后重启，或通过 adb 查看 /data/zui_perfctl/log/perfctld.log。"
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
