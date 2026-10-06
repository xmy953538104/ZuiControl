package com.zui.zuicontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import java.util.Locale

/** Process metadata cache; package events invalidate it, never policy events. */
internal object FrontendPackages {
    data class Entry(val info:ApplicationInfo,val label:String,val icon:Drawable)
    data class Inventory(val version:Long,val entries:List<Entry>)
    @Volatile var version=0L;private set
    @Volatile private var cached:Inventory?=null
    private var registered=false
    private val listeners=linkedSetOf<()->Unit>()
    fun observe(context:Context,listener:()->Unit) {
        listeners.add(listener)
        if(registered)return
        val filter=IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")}
        context.applicationContext.registerReceiver(object:BroadcastReceiver(){
            override fun onReceive(context:Context,intent:Intent){version++;cached=null;listeners.toList().forEach{it()}}
        },filter)
        registered=true
    }
    fun remove(listener:()->Unit){listeners.remove(listener)}
    @Suppress("DEPRECATION")
    fun read(context:Context):Inventory {
        cached?.takeIf{it.version==version}?.let{return it}
        val observed=version;val pm=context.packageManager
        val next=Inventory(observed,pm.getInstalledApplications(0).mapNotNull{info->runCatching{
            Entry(info,info.loadLabel(pm).toString(),info.loadIcon(pm))
        }.getOrNull()}.sortedBy{it.label.lowercase(Locale.ROOT)})
        if(observed==version)cached=next
        return next
    }
}
