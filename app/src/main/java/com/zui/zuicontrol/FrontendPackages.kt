package com.zui.zuicontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.Locale

/** Process metadata cache; package events invalidate it, never policy events. */
internal object FrontendPackages {
    data class Entry(val info:ApplicationInfo,val label:String,val labelLoaded:Boolean=false)
    data class Inventory(val version:Long,val entries:List<Entry>)
    @Volatile var version=0L;private set
    @Volatile private var cached:Inventory?=null
    private var registered=false
    private val listeners=linkedSetOf<()->Unit>()
    private val icons=LruCache<String,Drawable.ConstantState>(24)
    fun observe(context:Context,listener:()->Unit) {
        listeners.add(listener)
        if(registered)return
        val filter=IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")}
        context.applicationContext.registerReceiver(object:BroadcastReceiver(){
            override fun onReceive(context:Context,intent:Intent){
                intent.data?.schemeSpecificPart?.let{icons.remove(it)}
                synchronized(this@FrontendPackages){version++;cached=null}
                listeners.toList().forEach{it()}
            }
        },filter)
        context.applicationContext.registerComponentCallbacks(object:ComponentCallbacks2 {
            override fun onConfigurationChanged(config:Configuration){}
            override fun onLowMemory(){icons.evictAll()}
            override fun onTrimMemory(level:Int){if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)icons.evictAll()}
        })
        registered=true
    }
    fun remove(listener:()->Unit){listeners.remove(listener)}
    fun icon(context:Context,entry:Entry):Drawable {
        icons.get(entry.info.packageName)?.let{return it.newDrawable(context.resources).mutate()}
        val drawable=entry.info.loadIcon(context.packageManager)
        drawable.constantState?.let{icons.put(entry.info.packageName,it);return it.newDrawable(context.resources).mutate()}
        return drawable
    }
    @Suppress("DEPRECATION")
    fun read(context:Context,wanted:Set<String> = emptySet()):Inventory {
        val observed=version;val pm=context.packageManager
        val base=cached?.takeIf{it.version==observed} ?: run {
            ResponseTrace.mark("INVENTORY_NAMES_STARTED")
            val names=Inventory(observed,pm.getInstalledApplications(0).map{Entry(it,it.packageName)}.sortedBy{it.info.packageName})
            ResponseTrace.mark("INVENTORY_NAMES_READY","count=${names.entries.size}")
            synchronized(this){cached?.takeIf{it.version==observed} ?: names.also{if(observed==version)cached=it}}
        }
        val missing=base.entries.filter{it.info.packageName in wanted && !it.labelLoaded}
        if(missing.isEmpty())return base
        ResponseTrace.mark("INVENTORY_LABELS_STARTED","count=${missing.size}")
        val updates=missing.associate{entry->entry.info.packageName to entry.copy(
            label=runCatching{entry.info.loadLabel(pm).toString()}.getOrDefault(entry.info.packageName),labelLoaded=true)}
        val next=synchronized(this){
            val current=cached?.takeIf{it.version==observed} ?: base
            Inventory(observed,current.entries.map{updates[it.info.packageName] ?: it}.sortedBy{it.label.lowercase(Locale.ROOT)})
                .also{if(observed==version)cached=it}
        }
        ResponseTrace.mark("INVENTORY_LABELS_READY","count=${missing.size}")
        return next
    }
}
