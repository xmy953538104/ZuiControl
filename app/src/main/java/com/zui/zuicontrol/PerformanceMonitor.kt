package com.zui.zuicontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.zui.ZuiControlManager
import org.json.JSONObject
import java.util.Locale

/** Rendering and explicit gestures only. No independent scalar/task sampling. */
class PerformanceMonitor(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: MonitorView? = null
    private var closed = false
    private var circle = false
    private var snapshot = JSONObject()
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1 || flags and IBinder.FLAG_ONEWAY == 0 || getCallingUid() != 1000) return false
            data.enforceInterface("android.zui.IMonitorSnapshot")
            val text = data.readString() ?: return false
            if (text.length > 32768) return false
            handler.post { if (!closed) render(text) }
            return true
        }
    }
    fun start() {
        if (Settings.canDrawOverlays(context)) runCatching { ZuiControlManager.get()?.monitor("register", "", false, false, callback) }
    }
    fun close() {
        closed = true
        runCatching { ZuiControlManager.get()?.monitor("register", "", false, false, null) }
        hide(); handler.removeCallbacksAndMessages(null)
    }
    fun toggle(action: String): String {
        if (action == "full") circle = recording()
        return command(action)
    }
    private fun recording() = snapshot.optString("recordState") in listOf("RECORDING", "PAUSED")
    private fun hide() {
        view?.cancelGesture()
        view?.let { runCatching { windows.removeViewImmediate(it) } }
        view = null
    }
    private fun render(text: String) {
        val next = runCatching { JSONObject(text) }.getOrNull() ?: return
        snapshot = next
        if (!next.optBoolean("active") || !Settings.canDrawOverlays(context)) { hide(); return }
        if (recording()) circle = true
        try {
            if (view == null) {
                val fresh = MonitorView(context)
                val params = WindowManager.LayoutParams(-2, -2,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        (if (next.optInt("mode") == 2) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (8 * context.resources.displayMetrics.density).toInt()
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                    title = "ZuiControl 性能监视器"
                }
                windows.addView(fresh, params); view = fresh
            }
            view?.update()
        } catch (_: RuntimeException) { hide(); command("off") }
    }
    private inner class MonitorView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gesture = MonitorGesture()
        private val down get() = gesture.down
        private var x0 = 0f
        private var y0 = 0f
        private val moved get() = gesture.cancelled
        private val completed get() = gesture.completed
        private val metric get() = gesture.metric
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val commit = Runnable {
            if (circle && !recording() && gesture.complete(SystemClock.elapsedRealtime())) {
                val reply = command("recordStart")
                if (reply.startsWith("ok=1")) {
                    snapshot.put("recordState", "RECORDING")
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                invalidate()
            }
        }
        init {
            isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            gesture.release(SystemClock.elapsedRealtime())
        }
        fun update() {
            val now = SystemClock.elapsedRealtime()
            gesture.cycle(now)
            contentDescription = if (snapshot.optInt("mode") == 2) "FPS ${number(snapshot.optDouble("fps", -1.0))}"
                else if (circle) "性能监视器 圆形 ${snapshot.optString("recordState")} ${circleText()}"
                else "性能监视器 长条 ${format(snapshot)}"
            requestLayout(); invalidate()
        }
        private fun circleText(): String = when (metric) {
            0 -> "${number(snapshot.optDouble("fps", -1.0))}\nFPS"
            1 -> "${number(snapshot.optDouble("quietC", -1.0))}°\nquiet"
            else -> "${number(snapshot.optDouble("powerW", -1.0))}\nW"
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val fps = snapshot.optInt("mode") == 2
            setMeasuredDimension(((if (fps) 62 else if (circle) 62 else 276) * density).toInt(),
                ((if (fps) 28 else if (circle) 62 else 32) * density).toInt())
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fps = snapshot.optInt("mode") == 2
            paint.style = Paint.Style.FILL; paint.color = Color.argb(210, 23, 35, 54)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
            paint.color = Color.rgb(238, 243, 255); paint.textSize = 12 * density; paint.textAlign = Paint.Align.CENTER
            val text = if (fps) number(snapshot.optDouble("fps", -1.0)) else if (circle) circleText() else format(snapshot)
            val lines = text.split('\n')
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, width / 2f, height / 2f + (index - (lines.size - 1) / 2f) * 16 * density - (paint.ascent() + paint.descent()) / 2, paint)
            }
            if (!fps && circle && (down >= 0 || recording())) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density
                paint.color = if (snapshot.optString("recordState") == "PAUSED") 0xFFE6BC67.toInt() else 0xFF79A9FF.toInt()
                val progress = if (recording()) 1f else ((SystemClock.elapsedRealtime() - down) / 2000f).coerceIn(0f, 1f)
                canvas.drawArc(RectF(2*density,2*density,width-2*density,height-2*density),-90f,360f*progress,false,paint)
                if (down >= 0 && !completed && !moved) postInvalidateOnAnimation()
            }
        }
        fun cancelGesture() {
            if (down >= 0 && !completed) command("cancelArm")
            handler.removeCallbacks(commit); gesture.cancel()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (snapshot.optInt("mode") == 2) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0=event.rawX;y0=event.rawY
                    gesture.press(SystemClock.elapsedRealtime())
                    if (circle && !recording() && command("arm").startsWith("ok=1")) handler.postDelayed(commit,2000)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.rawX-x0)>slop || kotlin.math.abs(event.rawY-y0)>slop) cancelGesture()
                MotionEvent.ACTION_CANCEL -> cancelGesture()
                MotionEvent.ACTION_UP -> {
                    if (!moved && !completed && down >= 0 && circle && !recording()
                        && SystemClock.elapsedRealtime()-down >= 2000) commit.run()
                    val short = gesture.shortRelease()
                    handler.removeCallbacks(commit)
                    if (short) performClick()
                    gesture.release(SystemClock.elapsedRealtime());update()
                }
            }
            return true
        }
        override fun performClick(): Boolean {
            super.performClick()
            if (snapshot.optInt("mode") == 2) return false
            if (recording()) {
                if (command("recordStop").startsWith("ok=1")) snapshot.put("recordState","IDLE")
            } else { command("cancelArm"); circle=!circle }
            update()
            return true
        }
    }
    companion object {
        fun command(action: String, pkg: String = "", enabled: Boolean = false, expanded: Boolean = false): String =
            runCatching { ZuiControlManager.get()?.monitor(action,pkg,enabled,expanded,null) ?: "ok=0\nerror=service_unavailable" }
                .getOrElse { "ok=0\nerror=${it.javaClass.simpleName}" }
        private fun number(n: Double) = if (!n.isFinite() || n<0) "--" else String.format(Locale.ROOT,"%.1f",n)
        internal fun format(data: JSONObject) = "FPS ${number(data.optDouble("fps",-1.0))}   ${number(data.optDouble("powerW",-1.0))} W   quiet ${number(data.optDouble("quietC",-1.0))}°"
    }
}
