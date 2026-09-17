package com.zui.zuicontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/** One inline interval; only the twelve supported OPPs can be emitted. */
class GpuRangeBar(context: Context, initial: GpuRanges.Range) : View(context) {
    var range = initial
        set(value) { field = value; describe(); invalidate() }
    var onPreview: (GpuRanges.Range) -> Unit = {}
    var onCommit: (GpuRanges.Range) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unit = resources.displayMetrics.density
    private var minimumThumb = true
    private var beforeDrag = initial
    init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; describe() }
    private fun x(mhz: Int) = 20f * unit + (width - 40f * unit) *
        GpuRanges.opps.indexOf(mhz) / GpuRanges.opps.lastIndex
    private fun describe() {
        contentDescription = "GPU ${range.min} 至 ${range.max} MHz，调整${if (minimumThumb) "最小" else "最大"}值，点击切换端点"
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = height / 2f
        paint.strokeWidth = 4 * unit; paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xffd5dde5.toInt(); canvas.drawLine(x(231), y, x(903), y, paint)
        paint.color = 0xff3478b8.toInt(); canvas.drawLine(x(range.min), y, x(range.max), y, paint)
        for (opp in GpuRanges.opps) canvas.drawCircle(x(opp), y, 2 * unit, paint)
        for (opp in listOf(range.min, range.max)) {
            paint.color = 0xff3478b8.toInt(); canvas.drawCircle(x(opp), y, 10 * unit, paint)
            paint.color = 0xffffffff.toInt(); canvas.drawCircle(x(opp), y, 5 * unit, paint)
        }
    }
    private fun preview(value: Int) {
        range = if (minimumThumb) GpuRanges.Range(value.coerceAtMost(range.max), range.max)
            else GpuRanges.Range(range.min, value.coerceAtLeast(range.min))
        onPreview(range)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beforeDrag = range
                minimumThumb = if (range.min == range.max) event.x <= x(range.min)
                    else abs(event.x - x(range.min)) <= abs(event.x - x(range.max))
                requestFocus(); parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_CANCEL -> { range = beforeDrag; onPreview(range); parent?.requestDisallowInterceptTouchEvent(false); return true }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> Unit
            else -> return false
        }
        preview(GpuRanges.snap((event.x - 20 * unit) / (width - 40 * unit).coerceAtLeast(1f)))
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (range != beforeDrag) onCommit(range)
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick(); minimumThumb = !minimumThumb; describe()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED); return true
    }
    private fun step(delta: Int): Boolean {
        val old = range
        val index = GpuRanges.opps.indexOf(if (minimumThumb) range.min else range.max)
        preview(GpuRanges.opps[(index + delta).coerceIn(0, GpuRanges.opps.lastIndex)])
        if (range != old) onCommit(range)
        return true
    }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
        KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> performClick()
        else -> super.onKeyDown(code, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            0f, 11f, GpuRanges.opps.indexOf(if (minimumThumb) range.min else range.max).toFloat())
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        if (!isEnabled) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step(1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> step(-1)
            else -> super.performAccessibilityAction(action, args)
        }
    }
}
