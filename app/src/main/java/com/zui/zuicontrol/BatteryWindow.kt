package com.zui.zuicontrol

import org.json.JSONObject

/** Retained window intent; power ticks never request a health read. */
internal class BatteryWindow {
    var requestId = 0L; private set
    @Volatile var isOpen = false; private set
    var pending = false; private set
    var health: JSONObject? = null; private set
    var error = ""; private set
    var changed: (() -> Unit)? = null

    fun open(): Boolean {
        if (isOpen) return false
        requestId++; isOpen = true; pending = true; health = null; error = ""
        return true
    }
    fun accepts(id: Long) = isOpen && requestId == id && pending
    fun complete(id: Long, value: Result<JSONObject>) {
        if (!accepts(id)) return
        pending = false
        health = value.getOrNull(); error = value.exceptionOrNull()?.message.orEmpty()
        changed?.invoke()
    }
    fun close() { isOpen = false; pending = false; changed = null }
}
