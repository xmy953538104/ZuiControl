package com.zui.zuicontrol

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
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
    /** One retained palette and standard bar ink, in the same UI frame. */
    fun transitionSystemBars(activity:Activity,host:View,target:View,dark:Boolean,palette:()->Unit,finished:()->Unit){
        palette()
        updateSystemBarAppearance(activity,dark)
        OwnerRenderTrace.event("THEME_PALETTE_AND_BAR_REQUEST","dark=$dark")
        host.postOnAnimation{finished()}
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
