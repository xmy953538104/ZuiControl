package com.zui.zuicontrol

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.zui.ZuiControlManager
import org.json.JSONObject
import java.util.Locale

/** Rendering only: the server's single snapshot feeds compact and expanded views. */
class PerformanceMonitor(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var closed = false
    private var remote: ZuiControlManager? = null
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1 || flags and IBinder.FLAG_ONEWAY == 0) return false
            if (getCallingUid() != 1000) return false
            data.enforceInterface("android.zui.IMonitorSnapshot")
            val text = data.readString() ?: return false
            if (text.length > 32768) return false
            handler.post { if (!closed) render(text) }
            return true
        }
    }

    fun start() {
        if (!Settings.canDrawOverlays(context)) return
        remote = ZuiControlManager.get()
        runCatching { remote?.monitor("register", "", false, false, callback) }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        runCatching { remote?.monitor("register", "", false, false, null) }
        hide()
    }

    private fun hide() {
        view?.let { runCatching { windows.removeViewImmediate(it) } }
        view = null
    }

    private fun render(text: String) {
        val data = runCatching { JSONObject(text) }.getOrNull()
        if (data == null || !data.optBoolean("active") || !Settings.canDrawOverlays(context)) {
            hide()
            if (data?.optBoolean("active") == true) {
                runCatching { remote?.monitor("register", "", false, false, null) }
            }
            return
        }
        val content = format(data)
        try {
            if (view == null) {
                val density = context.resources.displayMetrics.density
                val pad = (12 * density).toInt()
                val label = TextView(context).apply {
                    maxWidth = (420 * density).toInt()
                    textSize = 12f
                    setTextColor(Color.rgb(234, 242, 255))
                    setPadding(pad, pad, pad, pad)
                    background = GradientDrawable().apply {
                        setColor(Color.argb(220, 18, 31, 52))
                        cornerRadius = 12 * density
                        setStroke((density).toInt().coerceAtLeast(1), Color.rgb(69, 137, 235))
                    }
                    this.text = content
                    contentDescription = "ZuiControl 性能监视器"
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.END; x = pad; y = pad * 3; title = "ZuiControl 性能监视器" }
                windows.addView(label, params)
                view = label
            } else view?.text = content
        } catch (_: RuntimeException) {
            hide()
            // A hidden/failed overlay must not leave its collector running.
            runCatching { remote?.monitor("register", "", false, false, null) }
        }
    }

    companion object {
        fun command(action: String, pkg: String = "", enabled: Boolean = false, expanded: Boolean = false): String =
            runCatching { ZuiControlManager.get()?.monitor(action, pkg, enabled, expanded, null) ?: "ok=0\nerror=service_unavailable" }
                .getOrElse { "ok=0\nerror=${it.javaClass.simpleName}" }

        private fun value(n: Double, unit: String): String = if (!n.isFinite() || n < 0) "不支持" else String.format(Locale.ROOT, "%.1f%s", n, unit)
        internal fun format(data: JSONObject): String = buildString {
            append("性能监视器 · ").append(data.optString("package")).append('\n')
            append("CPU 全核 ").append(value(data.optDouble("cpu", -1.0), "%"))
            append("  GPU ").append(value(data.optDouble("gpuMHz", -1.0), "MHz")).append('\n')
            append("屏幕FPS ").append(value(data.optDouble("fps", -1.0), ""))
            append("  电池 ").append(value(data.optDouble("batteryC", -1.0), "°C")).append('\n')
            append("线程 CPU（单核100%） · 3秒/次")
            val rows = data.optJSONArray("threads")
            val count = minOf(rows?.length() ?: 0, if (data.optBoolean("expanded")) 15 else 3)
            for (i in 0 until count) {
                val row = rows!!.getJSONObject(i)
                append('\n').append(value(row.optDouble("cpu", -1.0), "%"))
                    .append("  ").append(row.optInt("tid")).append("  ").append(row.optString("name"))
            }
            if (count == 0) append("\n等待可读的目标进程")
        }
    }
}
