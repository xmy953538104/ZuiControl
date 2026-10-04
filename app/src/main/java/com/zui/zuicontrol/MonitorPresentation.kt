package com.zui.zuicontrol

import org.json.JSONObject

/** Process-local presentation fan-out of the already authenticated QuickService stream. */
object MonitorPresentation {
    private var user = -1
    private var latest: JSONObject? = null
    private val listeners = linkedSetOf<(JSONObject?) -> Unit>()
    fun publish(snapshot: JSONObject?) {
        user = ZuiControlClient.currentUserId()
        latest = snapshot
        listeners.toList().forEach { listener -> runCatching { listener(snapshot) } }
    }
    fun observe(listener: (JSONObject?) -> Unit) {
        listeners += listener
        listener(if (user == ZuiControlClient.currentUserId()) latest else null)
    }
    fun remove(listener: (JSONObject?) -> Unit) { listeners -= listener }
}
