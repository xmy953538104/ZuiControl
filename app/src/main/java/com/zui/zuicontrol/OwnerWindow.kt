package com.zui.zuicontrol

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Shared presentation only; no policy, producer or command ownership. */
internal object OwnerWindow {
    fun themed(base:Context):Context {
        val theme=base.getSharedPreferences("frontend",Context.MODE_PRIVATE).getString("theme","system").orEmpty()
        return if(theme=="system")base else base.createConfigurationContext(Configuration(base.resources.configuration).apply{
            uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if(FrontendTheme.dark(theme,false))Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
    }
    fun fullscreen(activity:Activity)=with(activity.window){
        statusBarColor=Color.TRANSPARENT;navigationBarColor=Color.TRANSPARENT
        decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val dark=dark(activity)
        if(Build.VERSION.SDK_INT>=30){
            setDecorFitsSystemWindows(false)
            insetsController?.show(WindowInsets.Type.systemBars())
            val mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            insetsController?.setSystemBarsAppearance(if(dark)0 else mask,mask)
        }else if(!dark){decorView.systemUiVisibility=decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR}
        isStatusBarContrastEnforced=false;isNavigationBarContrastEnforced=false
    }
    fun dark(context:Context):Boolean=FrontendTheme.dark(
        context.getSharedPreferences("frontend",Context.MODE_PRIVATE).getString("theme","system").orEmpty(),
        context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)
    fun inset(view:View){
        view.setOnApplyWindowInsetsListener{v,insets->
            if(Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            }else{v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)}
            insets
        }
        view.requestApplyInsets()
    }
}
