package com.zui.perfctl

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
            text = "System scheduling, AsoulOpt, and refresh control"
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

        section(root, "ZuiPP XML")
        row(root,
            actionButton("Apply XML") { sendRequest("apply_zuipp") },
            actionButton("Restore XML") { sendRequest("restore_zuipp") },
        )
        row(root,
            actionButton("Restart PP") { sendRequest("restart_zuipp") },
            actionButton("Refresh") { refreshStatus() },
        )

        section(root, "AsoulOpt")
        row(root,
            actionButton("Apply Config") { sendRequest("apply_asoul") },
            actionButton("Restore Config") { sendRequest("restore_asoul") },
        )
        row(root,
            actionButton("Restart Service") { sendRequest("restart_asoul") },
        )

        section(root, "Manual Refresh")
        row(root,
            actionButton("60 Hz") { sendRequest("set_refresh", 60) },
            actionButton("90 Hz") { sendRequest("set_refresh", 90) },
        )
        row(root,
            actionButton("120 Hz") { sendRequest("set_refresh", 120) },
            actionButton("144 Hz") { sendRequest("set_refresh", 144) },
        )
        row(root,
            actionButton("System Default") { sendRequest("restore_refresh") },
        )

        section(root, "Auto Refresh")
        row(root,
            actionButton("Enable") { sendRequest("enable_auto_refresh") },
            actionButton("Disable") { sendRequest("disable_auto_refresh") },
        )

        root.addView(TextView(this).apply {
            text = "Runtime files: /data/local/tmp/zui_perfctl. The first usable path is refresh-rate control; XML bind still depends on SELinux permissions."
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
            val resolver = contentResolver
            val id = System.currentTimeMillis().toString()
            Settings.System.putString(resolver, "zui_perfctl_cmd", cmd)
            Settings.System.putString(resolver, "zui_perfctl_rate", rate?.toString().orEmpty())
            Settings.System.putString(resolver, "zui_perfctl_request_id", id)
        }.onSuccess {
            statusView.text = buildString {
                append("Sent: ")
                append(cmd)
                if (rate != null) append(" ${rate}Hz")
                append("\nTime: ")
                append(timeFormat.format(Date()))
            }
        }.onFailure { error ->
            statusView.text = "Send failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun refreshStatus() {
        val resolver = contentResolver
        val status = Settings.System.getString(resolver, "zui_perfctl_status_text")
        val last = Settings.System.getString(resolver, "zui_perfctl_status_last")
        val time = Settings.System.getString(resolver, "zui_perfctl_status_time")
        statusView.text = when {
            !status.isNullOrBlank() -> status.replace(';', '\n').trim()
            !last.isNullOrBlank() -> "last=$last\ntime=${time.orEmpty()}"
            else -> "No daemon status yet. Reboot after flashing, then check /data/local/tmp/zui_perfctl/log/perfctld.log with adb."
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
