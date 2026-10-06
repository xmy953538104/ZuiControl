package com.zui.zuicontrol

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.zui.ZuiControlManager

/** Process-local view of the authoritative controls snapshot; never a sampler or policy writer. */
internal object ControlsState {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private var backend: ZuiControlManager? = null
    private var callback: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var epoch = 0L
    private var connecting=false
    var snapshot: String = ""
        private set
    private val reconnect = Runnable { connect() }
    fun observe(listener: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        if (backend == null) connect() else listener()
    }
    fun remove(listener: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            epoch++; handler.removeCallbacks(reconnect)
            val old=backend;val client=callback;val recipient=death
            FrontendTransport.reads.execute {
                runCatching { client?.let { old?.controls(false, it) } }
                runCatching { recipient?.let { old?.unlinkMonitorDeath(it) } }
            }
            connecting=false
            backend = null; callback = null; death = null; snapshot = ""
        }
    }
    private fun accept(text: String) {
        if (text == snapshot) return
        snapshot = text
        listeners.toList().forEach { it() }
    }
    private fun connect() {
        handler.removeCallbacks(reconnect)
        if (listeners.isEmpty() || connecting) return
        connecting=true
        val ticket = ++epoch
        val next = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1 || flags and IBinder.FLAG_ONEWAY == 0 || getCallingUid() != 1000) return false
                data.enforceInterface("android.zui.IControlsSnapshot")
                val text = data.readString() ?: return false
                if (text.length > 32768 || data.dataAvail() != 0) return false
                handler.post { if (ticket == epoch && listeners.isNotEmpty()) accept(text) }
                return true
            }
        }
        val died = IBinder.DeathRecipient { handler.post {
                if (ticket == epoch) {
                    epoch++; backend = null; callback = null; death = null;connecting=false
                    accept(""); handler.postDelayed(reconnect, 1000)
                }
        } }
        FrontendTransport.reads.execute {
            var manager:ZuiControlManager?=null
            val result=runCatching {
                val owner=checkNotNull(ZuiControlManager.get());manager=owner
                val initial=owner.controls(true,next)
                check(initial.startsWith("ok=1") || initial.contains("error=inactive_user"))
                owner.linkMonitorDeath(died);initial
            }
            if(result.isFailure){runCatching{manager?.controls(false,next)};runCatching{manager?.unlinkMonitorDeath(died)}}
            handler.post {
                if(ticket!=epoch || listeners.isEmpty()) {
                    FrontendTransport.reads.execute{runCatching{manager?.controls(false,next)};runCatching{manager?.unlinkMonitorDeath(died)}}
                    return@post
                }
                connecting=false
                result.onSuccess {
                    backend=manager;callback=next;death=died
                    // A newer callback may already have been delivered during registration.
                    if(snapshot.isEmpty())accept(it)
                }.onFailure {
                    epoch++;backend=null;callback=null;death=null;accept("")
                    handler.postDelayed(reconnect,5000)
                }
            }
        }
    }
}
