package com.zui.zuicontrol

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.ViewGroup
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared presentation only; no policy, producer or command ownership. */
internal object OwnerWindow {
    private data class Safe(val top:Float,val bottom:Float,val height:Int)
    private val safe=WeakHashMap<View,Safe>()
    fun safeContent(view:View,top:Float,bottom:Float=0f) {
        safe[view]=Safe(top,bottom,view.layoutParams?.height ?: -2)
    }
    fun themed(base:Context):Context {
        val theme=base.getSharedPreferences("frontend",Context.MODE_PRIVATE).getString("theme","system").orEmpty()
        return if(theme=="system")base else base.createConfigurationContext(Configuration(base.resources.configuration).apply{
            uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if(FrontendTheme.dark(theme,false))Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
    }
    /** Activity creation only. Theme changes use appearance without window setup. */
    fun fullscreen(activity:Activity)=with(activity.window){
        statusBarColor=Color.TRANSPARENT;navigationBarColor=Color.TRANSPARENT
        // Preserve the current bar ink while changing layout flags. The single
        // appearance update below owns the next ink; clearing it first flashes.
        val inkMask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decorView.systemUiVisibility=(decorView.systemUiVisibility and inkMask) or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if(Build.VERSION.SDK_INT>=30){
            setDecorFitsSystemWindows(false)
            insetsController?.show(WindowInsets.Type.systemBars())
        }
        isStatusBarContrastEnforced=false;isNavigationBarContrastEnforced=false
        updateSystemBarAppearance(activity,dark(activity))
    }
    /** Ink only: preserve the existing viewport, visibility, layout and insets. */
    fun updateSystemBarAppearance(activity:Activity,dark:Boolean)=with(activity.window){
        if(Build.VERSION.SDK_INT>=30){
            val mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            insetsController?.setSystemBarsAppearance(if(dark)0 else mask,mask)
        }else{
            val mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decorView.systemUiVisibility=(decorView.systemUiVisibility and mask.inv()) or if(dark)0 else mask
        }
    }
    /** Drawable overlay has no input/layout participation and ends transparent. */
    private fun linear(v:Int)=if(v/255.0<=.04045)v/255.0/12.92 else Math.pow((v/255.0+.055)/1.055,2.4)
    private fun channel(v:Double)=((if(v<=.0031308)12.92*v else 1.055*Math.pow(v,1/2.4)-.055)*255).roundToInt().coerceIn(0,255)
    fun blend(a:Int,b:Int,t:Double):Int {
        fun part(x:Int,y:Int)=channel(linear(x)*(1-t)+linear(y)*t)
        return Color.argb((Color.alpha(a)*(1-t)+Color.alpha(b)*t).roundToInt(),part(Color.red(a),Color.red(b)),part(Color.green(a),Color.green(b)),part(Color.blue(a),Color.blue(b)))
    }
    fun transitionSystemBars(activity:Activity,host:View,rail:View,master:View,
        source:IntArray,target:IntArray,dark:Boolean,frame:(Float,IntArray)->Unit,finished:()->Unit){
        val top=host.rootWindowInsets?.let{insets->
            if(Build.VERSION.SDK_INT>=30)insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
        } ?: 0
        val origin=IntArray(2);host.getLocationOnScreen(origin)
        val railBounds=Rect();val masterBounds=Rect()
        rail.getGlobalVisibleRect(railBounds);master.getGlobalVisibleRect(masterBounds)
        val ends=intArrayOf(railBounds.right-origin[0],masterBounds.right-origin[0],host.width)
        // Linear-light interpolation preserves Owner region hues. At luminance
        // .179 both black and white endpoint inks exceed 4.5:1 contrast.
        fun luminance(c:Int)=.2126*linear(Color.red(c))+.7152*linear(Color.green(c))+.0722*linear(Color.blue(c))
        val midpoint=IntArray(3){i->blend(source[i],target[i],(.179-luminance(source[i]))/(luminance(target[i])-luminance(source[i])))}
        val paint=Paint();var colors=source
        val bridge=object:Drawable(){
            override fun draw(canvas:Canvas){var left=0f;for(i in 0..2){paint.color=colors[i];canvas.drawRect(left,0f,ends[i].toFloat(),top.toFloat(),paint);left=ends[i].toFloat()}}
            override fun setAlpha(alpha:Int){}
            override fun setColorFilter(filter:ColorFilter?){}
            @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.OPAQUE
        }
        bridge.setBounds(0,0,host.width,top);host.overlay.add(bridge)
        fun traceCommit(kind:String,ms:Long){
            if(Build.VERSION.SDK_INT>=29 && host.isHardwareAccelerated && android.util.Log.isLoggable("OwnerRenderTrace",android.util.Log.VERBOSE))
                host.viewTreeObserver.registerFrameCommitCallback{OwnerRenderTrace.event(kind,"dark=$dark;playTime=$ms")}
        }
        var inkPending=false;var inkChanged=false;var inkChangedAt=0L;var railChanged=false
        ValueAnimator.ofFloat(0f,1f).apply{
            duration=280;interpolator=android.view.animation.LinearInterpolator()
            fun changeInk(){
                if(inkChanged)return
                inkChanged=true;inkChangedAt=currentPlayTime;updateSystemBarAppearance(activity,dark)
                OwnerRenderTrace.event("STATUS_INK_REQUEST","dark=$dark;playTime=$inkChangedAt")
                traceCommit("STATUS_INK_FRAME_COMMITTED",inkChangedAt)
            }
            addUpdateListener {animation->
                val ms=animation.currentPlayTime
                colors=when{
                    ms<65->IntArray(3){i->blend(source[i],midpoint[i],ms/65.0)}
                    ms<195->midpoint
                    else->IntArray(3){i->blend(midpoint[i],target[i],((ms-195)/85.0).coerceAtMost(1.0))}
                }
                // Anchor the ink request after the neutral App buffer is
                // submitted, so palette work cannot delay that same frame.
                // Continue the SAME animator and matching physical/App colors.
                val railReady=inkChanged && !(ms-inkChangedAt<30)
                colors[0]=if(railReady)target[0] else source[0]
                frame(animation.animatedFraction,colors)
                bridge.invalidateSelf()
                if(ms>=65 && !inkPending){
                    inkPending=true
                    if(Build.VERSION.SDK_INT>=29 && host.isHardwareAccelerated)
                        host.viewTreeObserver.registerFrameCommitCallback(::changeInk)
                    else changeInk()
                }
                if(railReady && !railChanged){
                    railChanged=true;OwnerRenderTrace.event("STATUS_RAIL_HANDOFF","dark=$dark;playTime=$ms")
                    traceCommit("STATUS_RAIL_FRAME_COMMITTED",ms)
                }
            }
            addListener(object:android.animation.AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:android.animation.Animator){changeInk();frame(1f,target);host.overlay.remove(bridge);finished()}
            })
            start()
        }
    }
    fun dark(context:Context):Boolean=FrontendTheme.dark(
        context.getSharedPreferences("frontend",Context.MODE_PRIVATE).getString("theme","system").orEmpty(),
        context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)
    fun inset(view:View){
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){v.requestApplyInsets()}
            override fun onViewDetachedFromWindow(v:View){}
        })
        view.setOnApplyWindowInsetsListener{v,insets->
            val top:Int;val bottom:Int
            if(Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top=bars.top;bottom=bars.bottom
            }else{top=insets.systemWindowInsetTop;bottom=insets.systemWindowInsetBottom}
            // Insets affect only existing content whitespace, never the Owner viewport.
            v.setPadding(0,0,0,0)
            val density=v.resources.displayMetrics.density
            val width=if(v.width>0)v.width else v.rootView.width
            val height=if(v.height>0)v.height else v.rootView.height
            val logicalScale=min(width/1040f,height/650f).coerceAtLeast(.01f)
            fun apply(target:View) {
                safe[target]?.let{s->
                    val extra=max(0f,top/logicalScale-s.top+2f)
                    val b=if(s.bottom>0f)max(s.bottom,bottom/logicalScale+2f)else target.paddingBottom/density
                    target.setPadding(target.paddingLeft,((s.top+extra)*density).roundToInt(),target.paddingRight,(b*density).roundToInt())
                    if(s.height>0)target.layoutParams=target.layoutParams.apply{this.height=s.height+(extra*density).roundToInt()}
                }
                if(target is ViewGroup)for(i in 0 until target.childCount)apply(target.getChildAt(i))
            }
            if(width>0 && height>0)apply(v)else v.post{v.requestApplyInsets()}
            v.post{OwnerGeometry.composition(v,top,bottom)}
            insets
        }
        view.requestApplyInsets()
    }
}
