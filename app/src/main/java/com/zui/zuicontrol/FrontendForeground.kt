package com.zui.zuicontrol

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Foreground means at least one started UI Activity, never a service or onPause. */
internal class FrontendForegroundState {
    private var started=0
    fun start(){started++}
    fun stop(configurationChange:Boolean,ownedFlow:Boolean):Boolean {
        started=(started-1).coerceAtLeast(0)
        return started==0 && !configurationChange && !ownedFlow
    }
}

/** Process-local session retention; drafts and navigation still live only in FrontendSession. */
internal object FrontendForeground : Application.ActivityLifecycleCallbacks {
    private var registered=false
    private val foreground=FrontendForegroundState()
    private var retained:FrontendSession?=null
    fun obtain(application:Application,user:Int,factory:()->FrontendSession):FrontendSession {
        if(!registered){application.registerActivityLifecycleCallbacks(this);registered=true}
        if(retained?.userId!=user){retained?.close();retained=factory()}
        return checkNotNull(retained)
    }
    override fun onActivityStarted(activity:Activity){foreground.start()}
    override fun onActivityStopped(activity:Activity){
        val session=retained ?: return
        if(foreground.stop(activity.isChangingConfigurations,session.ownedExternalFlow)){
            session.returnHome();OwnerRenderTrace.event("HOME_RESET","${session.section}/${session.selected}/${session.analysisPage}/dirty=${session.dirty}")
        }else OwnerRenderTrace.event("CONTEXT_RETAINED",activity.javaClass.simpleName)
    }
    override fun onActivityCreated(activity:Activity,savedInstanceState:Bundle?)=Unit
    override fun onActivityResumed(activity:Activity)=Unit
    override fun onActivityPaused(activity:Activity)=Unit
    override fun onActivitySaveInstanceState(activity:Activity,outState:Bundle)=Unit
    override fun onActivityDestroyed(activity:Activity)=Unit
}
