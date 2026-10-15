package com.zui.zuicontrol

import android.os.SystemClock
import android.util.Log
import android.view.View
import org.json.JSONObject

/** Opt-in operation timings only; no persistent state or background work. */
internal object ResponseTrace {
    private const val TAG = "ZuiResponse"
    private val operation = ThreadLocal<String>()
    fun begin(kind: String): String = "${android.os.Process.myPid()}-${SystemClock.elapsedRealtimeNanos()}-$kind".also { event(it,"INTENT") }
    fun event(id: String, phase: String, detail: String = "") {
        if(id.isNotEmpty() && Log.isLoggable(TAG,Log.VERBOSE)) Log.i(TAG,JSONObject().put("id",id).put("phase",phase)
            .put("ns",SystemClock.elapsedRealtimeNanos()).put("detail",detail).toString())
    }
    fun mark(phase: String, detail: String = "") = event(operation.get().orEmpty(),phase,detail)
    fun <T> during(id: String, task: () -> T): T {
        val old=operation.get();operation.set(id)
        try{return task()}finally{operation.set(old)}
    }
    fun drawn(view: View, id: String, phase: String, detail:String="", valid:()->Boolean={true}) {
        if(!Log.isLoggable(TAG,Log.VERBOSE))return
        val observer=view.viewTreeObserver
        val listener=object:android.view.ViewTreeObserver.OnDrawListener {
            var done=false
            override fun onDraw(){if(!done){done=true;if(valid())event(id,phase,detail);view.post{if(observer.isAlive)observer.removeOnDrawListener(this)}}}
        }
        observer.addOnDrawListener(listener)
    }
}
