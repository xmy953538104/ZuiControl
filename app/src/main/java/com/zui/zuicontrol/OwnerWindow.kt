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
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if(Build.VERSION.SDK_INT>=30){setDecorFitsSystemWindows(false);insetsController?.apply{hide(WindowInsets.Type.systemBars());systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}}
    }
}
