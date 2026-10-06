package com.zui.zuicontrol

import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in proof of actual shell instances, attached counts and frame continuity. */
internal object OwnerRenderTrace {
    private const val TAG="OwnerRenderTrace"
    private var constructions=0
    private var frame=0L
    private fun enabled()=Log.isLoggable(TAG,Log.VERBOSE)
    fun event(kind:String,page:String=""){if(enabled())Log.v(TAG,JSONObject().put("kind",kind).put("page",page).put("uptime",android.os.SystemClock.uptimeMillis()).toString())}
    fun construct(vararg views:View){
        constructions++;event("SHELL_CONSTRUCTED")
        val root=views.first()
        (root as android.view.ViewGroup).setOnHierarchyChangeListener(object:android.view.ViewGroup.OnHierarchyChangeListener{
            override fun onChildViewAdded(parent:View,child:View){event("PHYSICAL_CHILD_ADDED")}
            override fun onChildViewRemoved(parent:View,child:View){event("PHYSICAL_CHILD_REMOVED")}
        })
        views.forEach{view->view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){event("SHELL_ATTACHED",v.javaClass.simpleName)}
            override fun onViewDetachedFromWindow(v:View){event("SHELL_DETACHED",v.javaClass.simpleName)}
        })}
        root.viewTreeObserver.addOnDrawListener{if(enabled()){
            frame++;Log.v(TAG,JSONObject().put("kind","frame").put("frame",frame).put("constructions",constructions)
                .put("uptime",android.os.SystemClock.uptimeMillis()).put("identities",JSONArray(views.map{System.identityHashCode(it)}))
                .put("attached",JSONArray(views.map{it.isAttachedToWindow})).put("children",JSONArray(views.map{(it as? android.view.ViewGroup)?.childCount ?: 0}))
                .put("alpha",JSONArray(views.map{it.alpha})).put("bounds",JSONArray(views.map{JSONArray(listOf(it.left,it.top,it.right,it.bottom))})).toString())
        }}
    }
    fun bind(vararg views:View){if(enabled())Log.v(TAG,JSONObject().put("kind","LOCAL_BIND").put("uptime",android.os.SystemClock.uptimeMillis()).put("identities",JSONArray(views.map{System.identityHashCode(it)})).toString())}
}
