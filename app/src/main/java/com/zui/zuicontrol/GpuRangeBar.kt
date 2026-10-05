package com.zui.zuicontrol

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/** One inline interval; only the twelve supported OPPs can be emitted. */
@SuppressLint("ViewConstructor") // Programmatic range dependency; never inflated from XML.
class GpuRangeBar(context: Context, initial: GpuRanges.Range) : View(context) {
    private var currentRange = initial
    var range: GpuRanges.Range
        get() = currentRange
        set(value) { currentRange = value; describe(); invalidate() }
    var onCommit: (GpuRanges.Range) -> Unit = {}
    var onPreview: (GpuRanges.Range) -> Unit = {}
    private val owner = OwnerUi(context)
    var tone: Int = owner.accent
    private var shownMin = GpuRanges.opps.indexOf(initial.min).toFloat()
    private var shownMax = GpuRanges.opps.indexOf(initial.max).toFloat()
    private var motion: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unit = resources.displayMetrics.density
    private val trackCenterY = 15f * unit
    val preferredHeight: Int get() = (30f * unit + .5f).toInt()
    val chipTopMargin: Int get() = (trackCenterY - resources.getDimension(R.dimen.ui_chip_height) / 2).toInt().coerceAtLeast(0)
    private fun track() = GpuRanges.track(width.toFloat(), 0f, 12f * unit)
    private var minimumThumb = true
    private var beforeDrag = initial
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var trackingTouch = false
    private var dragging = false
    init { isFocusable = true; isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; describe() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(preferredHeight, heightMeasureSpec))
    }
    private fun x(mhz: Int) = track().x(mhz)
    private fun describe() {
        contentDescription = "GPU ${range.min} 至 ${range.max} MHz，调整${if (minimumThumb) "最小" else "最大"}值，点击切换端点"
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = trackCenterY
        fun pos(index: Float) = 12f*unit+(width-24f*unit)*index/GpuRanges.opps.lastIndex
        paint.strokeWidth = 10 * unit; paint.strokeCap = Paint.Cap.ROUND
        paint.color = owner.card2; canvas.drawLine(x(231), y, x(903), y, paint)
        paint.color = if(isEnabled)tone else owner.soft(tone,115);canvas.drawLine(pos(shownMin),y,pos(shownMax),y,paint)
        for (opp in GpuRanges.opps) {
            paint.color = if (opp > range.min && opp < range.max) 0x99ffffff.toInt() else owner.line2
            canvas.drawCircle(x(opp), y, 2 * unit, paint)
        }
        for ((i,index) in listOf(shownMin,shownMax).withIndex()) {
            val radius=12*unit*(if(dragging && (minimumThumb == (i==0)))1.12f else 1f)
            val xx=pos(index)
            if(isEnabled){paint.color=owner.soft(tone);canvas.drawCircle(xx,y,radius+6*unit,paint)}
            paint.color=if(isEnabled)tone else owner.soft(tone,140);canvas.drawCircle(xx,y,radius,paint)
            paint.color=if(isEnabled)0xffffffff.toInt() else 0x8cffffff.toInt();canvas.drawCircle(xx,y,radius-4*unit,paint)
        }
    }
    private fun preview(value: Int) {
        val next = if (minimumThumb) GpuRanges.Range(value.coerceAtMost(range.max), range.max)
            else GpuRanges.Range(range.min, value.coerceAtLeast(range.min))
        if (next != currentRange) {
            currentRange = next;onPreview(next);animateRange(next)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackingTouch = false; dragging = false
                // Labels/blank space belong to the containing scroll view, not the range.
                if (abs(event.y - trackCenterY) > trackCenterY) return false
                beforeDrag = range
                downX = event.x; downY = event.y; trackingTouch = true
                minimumThumb = if (range.min == range.max) event.x <= x(range.min)
                    else abs(event.x - x(range.min)) <= abs(event.x - x(range.max))
                requestFocus()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!trackingTouch) return false
                range = beforeDrag;resetRangeVisual();onPreview(range);trackingTouch = false; dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            // Take ownership only after horizontal intent; vertical scrolling never previews.
            MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return false
                if (!dragging) {
                    val dx = abs(event.x - downX); val dy = abs(event.y - downY)
                    if (dy > touchSlop && dy >= dx) { trackingTouch = false; return false }
                    if (dx <= touchSlop || dx <= dy) return true
                    dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                }
                preview(track().snap(event.x))
            }
            MotionEvent.ACTION_UP -> {
                if (!trackingTouch) return false
                preview(track().snap(event.x))
                val changed = range != beforeDrag
                trackingTouch = false; dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                describe()
                if (changed) onCommit(range)
                super.performClick() // Announce the selected thumb without toggling it.
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
            }
            else -> return false
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
        describe()
        if (range != old) onCommit(range)
        return true
    }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
        KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> performClick()
        else -> super.onKeyDown(code, event)
    }
    private fun animateRange(next: GpuRanges.Range) {
        motion?.cancel();val a=shownMin;val b=shownMax
        motion=ValueAnimator.ofFloat(0f,1f).apply {
            duration=300;interpolator=OwnerUi.spring;addUpdateListener{val f=it.animatedValue as Float
                shownMin=a+(GpuRanges.opps.indexOf(next.min)-a)*f;shownMax=b+(GpuRanges.opps.indexOf(next.max)-b)*f;invalidate()};start()
        }
    }
    private fun resetRangeVisual() {
        motion?.cancel();shownMin=GpuRanges.opps.indexOf(range.min).toFloat();shownMax=GpuRanges.opps.indexOf(range.max).toFloat();invalidate()
    }
    override fun onDetachedFromWindow() { motion?.cancel();super.onDetachedFromWindow() }
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
            AccessibilityNodeInfo.ACTION_CLICK -> performClick()
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step(1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> step(-1)
            else -> super.performAccessibilityAction(action, args)
        }
    }
}
