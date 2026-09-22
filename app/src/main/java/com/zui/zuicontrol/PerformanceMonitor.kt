package com.zui.zuicontrol

import android.content.Context
import android.hardware.display.DisplayManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Binder
import android.os.Build
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
import android.view.WindowInsets
import android.zui.ZuiControlManager
import org.json.JSONObject
import java.util.Locale
import java.io.PrintWriter

/** Rendering and explicit gestures only. No independent scalar/task sampling. */
class PerformanceMonitor(private val context: Context, private val onModeChanged: () -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var view: MonitorView? = null
    private var attached = false
    private var started = false
    private val generation = ++nextGeneration
    private var adds = 0L
    private var removes = 0L
    private var updates = 0L
    private var insetsCallbacks = 0L
    private var transitions = 0L
    private var measures = 0L
    private var draws = 0L
    private var visualState = "HIDDEN"
    private var stableSafeTopInset = 0
    private var traceUntil = 0L
    private val events = ArrayDeque<String>()
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (attached && view?.display?.displayId == id) environmentChanged("display")
        }
    }
    private var closed = false
    private var circle = false
    private var circleX: Int? = null
    private var circleY: Int? = null
    private var circleBounds = Rect()
    private var positionWrites = 0L
    private val positions by lazy { context.getSharedPreferences("monitor_position", Context.MODE_PRIVATE) }
    private var snapshot = JSONObject()
    private val livePower = LivePower()
    private var displayPower = -1.0
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
        if (closed || !Settings.canDrawOverlays(context)) return
        if (!started) {
            displays.registerDisplayListener(displayListener, handler)
            started = true
        }
        runCatching { ZuiControlManager.get()?.monitor("register", "", false, false, callback) }
    }
    fun close() {
        closed = true
        runCatching { ZuiControlManager.get()?.monitor("register", "", false, false, null) }
        if (started) displays.unregisterDisplayListener(displayListener)
        hide(); handler.removeCallbacksAndMessages(null)
    }
    fun toggle(action: String): String {
        if (action == "full") circle = recording()
        return command(action)
    }
    private fun recording() = snapshot.optString("recordState") in listOf("RECORDING", "PAUSED")
    private fun hide() {
        view?.cancelGesture()
        if (attached) {
            view?.let { windows.removeViewImmediate(it) }
            attached = false; removes++; event("remove")
        }
        state("HIDDEN")
        livePower.clear(); displayPower = -1.0
    }
    private fun safeTop(insets: WindowInsets?): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or
                WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar())?.top ?: 0
        } else {
            @Suppress("DEPRECATION")
            maxOf(insets?.systemWindowInsetTop ?: 0, insets?.displayCutout?.safeInsetTop ?: 0)
        }
    private fun absoluteY() = context.resources.getDimensionPixelSize(R.dimen.monitor_top_inset)
    private fun refreshCircleBounds() {
        val old = circleBounds
        val size = context.resources.getDimensionPixelSize(R.dimen.monitor_touch_diameter)
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(insets.left, insets.top,
                maxOf(insets.left, metrics.bounds.width() - insets.right - size),
                maxOf(insets.top, metrics.bounds.height() - insets.bottom - size))
        } else {
            val pixels = android.graphics.Point()
            @Suppress("DEPRECATION")
            windows.defaultDisplay.getRealSize(pixels)
            @Suppress("DEPRECATION")
            val insets = view?.rootWindowInsets?.systemWindowInsets ?: android.graphics.Insets.NONE
            Rect(insets.left, insets.top, maxOf(insets.left, pixels.x - insets.right - size),
                maxOf(insets.top, pixels.y - insets.bottom - size))
        }
        if (next == old) return
        if (circleX != null && circleY != null && !old.isEmpty) {
            circleX = next.left + ((circleX!! - old.left).toFloat() / old.width() * next.width()).toInt()
            circleY = next.top + ((circleY!! - old.top).toFloat() / old.height() * next.height()).toInt()
        }
        circleBounds = next
        circleX = circleX?.coerceIn(next.left, next.right)
        circleY = circleY?.coerceIn(next.top, next.bottom)
    }
    private fun ensureCirclePosition() {
        refreshCircleBounds()
        if (circleX != null && circleY != null) return
        val x = positions.getFloat("circle_x", Float.NaN)
        val y = positions.getFloat("circle_y", Float.NaN)
        if (x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) {
            circleX = circleBounds.left + (x * circleBounds.width()).toInt()
            circleY = circleBounds.top + (y * circleBounds.height()).toInt()
        } else {
            circleX = circleBounds.centerX()
            circleY = absoluteY().coerceIn(circleBounds.top, circleBounds.bottom)
        }
    }
    private fun persistCirclePosition() {
        val x = (circleX!! - circleBounds.left).toFloat() / maxOf(1, circleBounds.width())
        val y = (circleY!! - circleBounds.top).toFloat() / maxOf(1, circleBounds.height())
        positions.edit().putFloat("circle_x", x).putFloat("circle_y", y).apply()
        positionWrites++; event("drag persisted x=$circleX y=$circleY writes=$positionWrites")
    }
    private fun positionWindow(reason: String) {
        val target = view ?: return
        if (!attached) return
        val layout = target.layoutParams as WindowManager.LayoutParams
        if (circle && circleX == null) ensureCirclePosition()
        val gravity = Gravity.TOP or if (circle) Gravity.LEFT else Gravity.CENTER_HORIZONTAL
        val x = if (circle) circleX!! else 0
        val y = if (circle) circleY!! else absoluteY()
        if (layout.gravity == gravity && layout.x == x && layout.y == y) return
        layout.gravity = gravity; layout.x = x; layout.y = y
        windows.updateViewLayout(target, layout); updates++
        event("layout reason=$reason x=$x y=$y")
    }
    fun environmentChanged(reason: String = "configuration") {
        // Owner's original R5 top margin; optional caption clearance is not a product requirement.
        stableSafeTopInset = 0
        val previous = Rect(circleBounds)
        refreshCircleBounds()
        if (circleBounds != previous) view?.cancelGesture()
        positionWindow(reason)
    }
    private fun state(next: String) {
        if (next == visualState) return
        event("state $visualState->$next"); visualState = next; transitions++
    }
    private fun event(text: String) {
        if (SystemClock.elapsedRealtime() >= traceUntil) return
        if (events.size == 96) events.removeFirst()
        events.addLast("${SystemClock.elapsedRealtime()} $text")
    }
    fun trace(seconds: Int) { traceUntil = SystemClock.elapsedRealtime() + seconds.coerceIn(0,600)*1000L }
    fun dump(out: PrintWriter) {
        val root = view
        val layout = root?.layoutParams as? WindowManager.LayoutParams
        out.println("overlayGeneration=$generation rootIdentity=${root?.let(System::identityHashCode)} attached=$attached state=$visualState")
        out.println("overlayAdds=$adds removes=$removes updates=$updates insetsCallbacks=$insetsCallbacks transitions=$transitions")
        out.println("overlayMeasures=$measures draws=$draws")
        out.println("overlaySafeTop=$stableSafeTopInset y=${layout?.y} token=${root?.windowToken} title=${layout?.title}")
        out.println("overlayX=${layout?.x} width=${root?.width} height=${root?.height} gravity=${layout?.gravity} circleX=$circleX circleY=$circleY positionWrites=$positionWrites")
        root?.dumpGesture(out)
        events.forEach { out.println("overlayEvent=$it") }
    }
    private fun render(text: String) {
        val next = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (next.has("mode") && next.optInt("mode") != snapshot.optInt("mode")) onModeChanged()
        if (next.optString("package") != snapshot.optString("package")) livePower.clear()
        displayPower = livePower.add(next.optDouble("powerW", -1.0))
        snapshot = next
        if (!next.optBoolean("active") || !Settings.canDrawOverlays(context)) { hide(); return }
        if (recording()) circle = true
        try {
            val fresh = view ?: MonitorView(context).also {
                view = it
                it.setOnApplyWindowInsetsListener { target, insets ->
                    insetsCallbacks++
                    val layout = target.layoutParams as WindowManager.LayoutParams
                    event("insets relativeTop=${safeTop(insets)} stableTop=$stableSafeTopInset oldY=${layout.y} newY=${layout.y} update=false")
                    insets
                }
            }
            if (!attached) {
                environmentChanged("attach")
                val params = WindowManager.LayoutParams(-2, -2,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        (if (next.optInt("mode") == 2) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
                    PixelFormat.TRANSLUCENT).apply {
                    if (circle) ensureCirclePosition()
                    gravity = Gravity.TOP or if (circle) Gravity.LEFT else Gravity.CENTER_HORIZONTAL
                    // Absolute display coordinates, matching the original top-center placement.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
                    x = if (circle) circleX!! else 0
                    y = if (circle) circleY!! else absoluteY()
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                    title = "ZuiControl 性能监视器"
                }
                windows.addView(fresh, params); attached = true; adds++; event("add y=${params.y}")
            }
            view?.update()
        } catch (_: RuntimeException) { hide(); command("off") }
    }
    private inner class MonitorView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun dimen(id: Int) = resources.getDimension(id)
        private val unitScale = resources.getFraction(R.fraction.monitor_unit_scale, 1, 1)
        // Physical millimetres use xdpi, never logical density/dp.
        private val sideExtension = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_MM, 0.5f, resources.displayMetrics)
        private val barPadding get() = dimen(R.dimen.monitor_bar_padding) + sideExtension
        private val unitSize get() = if (circle && snapshot.optInt("mode") != 2) valueSize * unitScale else dimen(R.dimen.monitor_unit_text)
        private var metrics = emptyList<Pair<String, String>>()
        private var valueSize = dimen(R.dimen.monitor_value_text)
        private val gesture = MonitorGesture()
        private val down get() = gesture.down
        private var x0 = 0f
        private var y0 = 0f
        private var dragX = 0
        private var dragY = 0
        private var armed = false
        private val metric get() = gesture.metric
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var touchSequence = 0L
        private var lastUpTime = -1L
        private var lastCircleDrawTime = -1L
        private var drawnState = ""
        private val showArm = Runnable { if (armed && gesture.arming(SystemClock.elapsedRealtime())) update() }
        private val commit = Runnable {
            if (armed && circle && !recording() && gesture.complete(SystemClock.elapsedRealtime())) {
                val reply = command("recordStart")
                event("recordStart sequence=$touchSequence ok=${reply.startsWith("ok=1")}")
                if (!reply.startsWith("ok=1")) command("cancelArm")
                armed = false
                if (reply.startsWith("ok=1")) {
                    snapshot.put("recordState", "RECORDING")
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                update()
            }
        }
        init {
            isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            gesture.release(SystemClock.elapsedRealtime())
        }
        fun update() {
            val now = SystemClock.elapsedRealtime()
            gesture.cycle(now)
            val all = listOf(number(snapshot.optDouble("fps", -1.0)) to "FPS",
                number(snapshot.optDouble("quietC", -1.0)) to "°C", number(displayPower) to "W")
            val previous = metrics
            metrics = if (snapshot.optInt("mode") == 2) listOf(all[0])
                else if (circle) listOf(all[metric]) else all
            contentDescription = if (snapshot.optInt("mode") == 2) "FPS ${number(snapshot.optDouble("fps", -1.0))}"
                else "性能监视器 ${if (circle) "圆形" else "长条"} ${snapshot.optString("recordState")} " +
                    metrics.joinToString("   ") { "${it.first} ${it.second}" }
            val nextState = when { recording() -> "RECORDING"; circle && armed && gesture.arming(now) -> "ARMING"; circle -> "CIRCLE"; else -> "LONG_BAR" }
            val stateChanged = nextState != visualState
            val shapeChanged = (visualState == "LONG_BAR") != (nextState == "LONG_BAR")
            state(nextState)
            if (previous != metrics || shapeChanged) {
                val widthChanged = previous.sumOf { metricWidth(it).toDouble() } !=
                    metrics.sumOf { metricWidth(it).toDouble() }
                fitValueSize()
                if (previous.isEmpty() || shapeChanged || (!circle && widthChanged)) requestLayout()
                invalidate()
            }
            if (stateChanged) invalidate()
            positionWindow("shape")
        }
        private fun numberWidth(metric: Pair<String, String>): Float {
            paint.textSize = valueSize
            // Reserve numeric space: changing digits must not resize the overlay and start OEM animations.
            return if (circle && snapshot.optInt("mode") != 2) paint.measureText(metric.first)
                else maxOf(paint.measureText(metric.first), paint.measureText(if (metric.second == "FPS") "888.8" else "88.8"))
        }
        private fun metricWidth(metric: Pair<String, String>): Float {
            val value = numberWidth(metric)
            paint.textSize = unitSize
            return value + dimen(R.dimen.monitor_unit_gap) + paint.measureText(metric.second)
        }
        private fun fitValueSize() {
            val round = circle && snapshot.optInt("mode") != 2
            valueSize = dimen(if (round) R.dimen.monitor_circle_text else R.dimen.monitor_value_text)
            if (round && metrics.isNotEmpty()) {
                val available = dimen(R.dimen.monitor_circle_diameter) - 6 * density
                val measured = metricWidth(metrics[0])
                if (measured > available) valueSize *= available / measured
            }
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measures++
            fitValueSize()
            val round = circle && snapshot.optInt("mode") != 2
            val content = metrics.sumOf { metricWidth(it).toDouble() }.toFloat() +
                (metrics.size - 1).coerceAtLeast(0) * dimen(R.dimen.monitor_metric_gap)
            val w = if (round) dimen(R.dimen.monitor_touch_diameter) else content + 2 * barPadding
            val h = dimen(if (round) R.dimen.monitor_touch_diameter else R.dimen.monitor_bar_height)
            setMeasuredDimension(kotlin.math.ceil(w).toInt(), kotlin.math.ceil(h).toInt())
        }
        override fun onDraw(canvas: Canvas) {
            draws++
            super.onDraw(canvas)
            if (drawnState != visualState) {
                drawnState = visualState
                if (visualState == "CIRCLE") lastCircleDrawTime = SystemClock.uptimeMillis()
                event("draw state=$visualState sequence=$touchSequence uptime=${SystemClock.uptimeMillis()} lastUp=$lastUpTime")
            }
            val fps = snapshot.optInt("mode") == 2
            val round = circle && !fps
            val inset = if (round) (width - dimen(R.dimen.monitor_circle_diameter)) / 2 else 0f
            val radius = if (round) dimen(R.dimen.monitor_circle_diameter) / 2 else dimen(R.dimen.monitor_bar_radius)
            val bounds = RectF(inset, inset, width - inset, height - inset)
            paint.clearShadowLayer(); paint.style = Paint.Style.FILL
            paint.color = context.getColor(R.color.monitor_glass)
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = density * 0.5f
            paint.color = context.getColor(R.color.monitor_border)
            bounds.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.LEFT
            paint.setShadowLayer(1.5f * density, 0f, density * 0.5f, Color.argb(190, 0, 0, 0))
            paint.textSize = valueSize
            val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2
            var x = if (round && metrics.isNotEmpty()) (width - metricWidth(metrics[0])) / 2 else barPadding
            metrics.forEach { (value, unit) ->
                val slot = numberWidth(value to unit)
                paint.textSize = valueSize; paint.color = context.getColor(R.color.monitor_value)
                canvas.drawText(value, x + slot - paint.measureText(value), baseline, paint)
                x += slot + dimen(R.dimen.monitor_unit_gap)
                paint.textSize = unitSize; paint.color = context.getColor(R.color.monitor_unit)
                canvas.drawText(unit, x, baseline, paint)
                x += paint.measureText(unit) + dimen(R.dimen.monitor_metric_gap)
            }
            if (!fps && circle && ((armed && gesture.arming(SystemClock.elapsedRealtime())) || recording())) {
                paint.clearShadowLayer(); paint.style = Paint.Style.STROKE
                paint.strokeWidth = dimen(R.dimen.monitor_ring_width)
                paint.color = if (snapshot.optString("recordState") == "PAUSED") 0xFFE6BC67.toInt() else context.getColor(R.color.ui_accent)
                val progress = if (recording()) 1f else ((SystemClock.elapsedRealtime() - down) / 2000f).coerceIn(0f, 1f)
                val ringInset = inset - dimen(R.dimen.monitor_ring_gap)
                canvas.drawArc(RectF(ringInset,ringInset,width-ringInset,height-ringInset),-90f,360f*progress,false,paint)
                if (armed && gesture.phase == MonitorGesture.Phase.PENDING) postInvalidateOnAnimation()
            }
        }
        fun dumpGesture(out: PrintWriter) {
            out.println("overlayGesture=${gesture.phase} touchSlop=$slop touchSequence=$touchSequence lastUpUptime=$lastUpTime lastCircleDrawUptime=$lastCircleDrawTime armed=$armed")
        }
        private fun cancelArming() {
            this@PerformanceMonitor.handler.removeCallbacks(showArm)
            this@PerformanceMonitor.handler.removeCallbacks(commit)
            if (armed) { command("cancelArm"); armed = false }
        }
        private fun movePointer(event: MotionEvent) {
            val dx = event.rawX - x0; val dy = event.rawY - y0
            if (gesture.phase != MonitorGesture.Phase.DRAGGING && dx * dx + dy * dy <= slop * slop) return
            if (gesture.drag()) {
                cancelArming()
                circleX = (dragX + dx.toInt()).coerceIn(circleBounds.left, circleBounds.right)
                circleY = (dragY + dy.toInt()).coerceIn(circleBounds.top, circleBounds.bottom)
                positionWindow("drag")
                state(if (recording()) "RECORDING" else "CIRCLE")
                invalidate()
            } else cancelGesture()
        }
        fun cancelGesture() {
            // View.handler is null after detach; the controller owns this timer.
            cancelArming(); gesture.cancel()
            if (attached) state(if (recording()) "RECORDING" else if (circle) "CIRCLE" else "LONG_BAR")
            invalidate()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (snapshot.optInt("mode") == 2) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelArming()
                    x0=event.rawX;y0=event.rawY
                    touchSequence++
                    gesture.press(SystemClock.elapsedRealtime(), circle, recording())
                    if (circle) {
                        ensureCirclePosition(); dragX = circleX!!; dragY = circleY!!
                        // The server independently enforces 2 seconds; only the visible ring waits 250 ms.
                        if (!recording()) armed = command("arm").startsWith("ok=1")
                        if (armed) {
                            this@PerformanceMonitor.handler.postDelayed(showArm, 251)
                            this@PerformanceMonitor.handler.postDelayed(commit, 2000)
                        }
                    }
                    this@PerformanceMonitor.event("touch DOWN sequence=$touchSequence circle=$circle x=$x0 y=$y0 uptime=${event.eventTime}")
                    update()
                }
                MotionEvent.ACTION_POINTER_DOWN -> cancelGesture()
                MotionEvent.ACTION_MOVE -> movePointer(event)
                MotionEvent.ACTION_CANCEL -> cancelGesture()
                MotionEvent.ACTION_UP -> {
                    lastUpTime = event.eventTime
                    movePointer(event)
                    if (armed && SystemClock.elapsedRealtime() - down >= 2000) commit.run()
                    val action = gesture.release(SystemClock.elapsedRealtime())
                    cancelArming()
                    this@PerformanceMonitor.event("touch UP sequence=$touchSequence action=$action uptime=$lastUpTime")
                    when (action) {
                        MonitorGesture.Release.DRAG_END -> persistCirclePosition()
                        MonitorGesture.Release.TO_CIRCLE, MonitorGesture.Release.TO_BAR,
                        MonitorGesture.Release.STOP_RECORDING -> performClick()
                        MonitorGesture.Release.NONE -> Unit
                    }
                    update()
                }
            }
            return true
        }
        override fun performClick(): Boolean {
            super.performClick()
            if (snapshot.optInt("mode") == 2) return false
            // Accessibility activation also consumes any pending pointer before changing the shape.
            gesture.release(SystemClock.elapsedRealtime()); cancelArming()
            if (recording()) {
                if (command("recordStop").startsWith("ok=1")) snapshot.put("recordState","IDLE")
            } else circle=!circle
            update()
            return true
        }
    }
    companion object {
        private var nextGeneration = 0L
        fun command(action: String, pkg: String = "", enabled: Boolean = false, expanded: Boolean = false): String =
            runCatching { ZuiControlManager.get()?.monitor(action,pkg,enabled,expanded,null) ?: "ok=0\nerror=service_unavailable" }
                .getOrElse { "ok=0\nerror=${it.javaClass.simpleName}" }
        private fun number(n: Double) = if (!n.isFinite() || n<0) "--" else String.format(Locale.ROOT,"%.1f",n)
    }
}
