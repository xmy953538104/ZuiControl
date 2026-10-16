package com.zui.zuicontrol

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Keeps the prior page until the replacement is measured; at most two trees, no page cache. */
internal class OwnerPageHost(context: Context) : FrameLayout(context) {
    private var current: View? = null
    private var sequence = 0L
    private var animator: ValueAnimator? = null
    private var transitioning = false
    fun show(next: View, navigation: Boolean, distance: Int) {
        val ticket = ++sequence
        animator?.cancel(); animator = null
        val old = current
        old?.translationY = 0f
        for (i in childCount - 1 downTo 0) if (getChildAt(i) !== old) removeViewAt(i)
        current = next
        transitioning = navigation && old != null && isLaidOut && ValueAnimator.areAnimatorsEnabled()
        next.translationY = if (transitioning) distance.toFloat() else 0f
        addView(next, LayoutParams(-1, -1))
        if (!transitioning) {
            old?.let(::removeView)
            next.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{
                override fun onPreDraw():Boolean{
                    next.viewTreeObserver.removeOnPreDrawListener(this)
                    if(ticket==sequence)OwnerRenderTrace.event("PAGE_SWAP_READY","sequence=$ticket;children=$childCount;y=${next.translationY}")
                    return true
                }
            })
            return
        }
        next.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                next.viewTreeObserver.removeOnPreDrawListener(this)
                if (ticket != sequence) return true
                OwnerRenderTrace.event("PAGE_TRANSITION_START", "sequence=$ticket;children=$childCount")
                animator = ValueAnimator.ofFloat(distance.toFloat(), 0f).apply {
                    duration = 200; interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                    addUpdateListener { if (ticket == sequence) next.translationY = (it.animatedValue as Float).roundToInt().toFloat() }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (ticket != sequence) return
                            next.translationY = 0f; old?.let(::removeView); transitioning = false; animator = null
                            OwnerRenderTrace.event("PAGE_TRANSITION_END", "sequence=$ticket;children=$childCount;y=${next.translationY}")
                        }
                    })
                    start()
                }
                return true
            }
        })
    }
    fun release() {
        sequence++; animator?.cancel(); animator = null; transitioning = false; current = null; removeAllViews()
    }
    override fun onInterceptTouchEvent(event: MotionEvent) = transitioning || super.onInterceptTouchEvent(event)
    override fun onTouchEvent(event: MotionEvent) = transitioning || super.onTouchEvent(event)
    override fun onDetachedFromWindow() {
        sequence++; animator?.cancel(); animator=null; transitioning=false
        current?.translationY=0f
        for(i in childCount-1 downTo 0)if(getChildAt(i)!==current)removeViewAt(i)
        super.onDetachedFromWindow()
    }
}
