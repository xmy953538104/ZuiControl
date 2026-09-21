package com.zui.zuicontrol

import android.os.SystemClock
import org.json.JSONObject
import java.io.PrintWriter

/** Opt-in, bounded diagnostics only; never used to select a control's state. */
internal object QuickControlTrace {
    private var until = 0L
    private val rows = ArrayDeque<JSONObject>()
    private var nextId = 0L
    @Synchronized fun enable(seconds: Int) { until = SystemClock.elapsedRealtime() + seconds.coerceIn(0,600)*1000L }
    @Synchronized fun begin(kind: String, value: String): Long {
        if (SystemClock.elapsedRealtime() >= until) return 0
        if (rows.size == 16) rows.removeFirst()
        val id = ++nextId
        rows.addLast(JSONObject().put("id",id).put("kind",kind).put("value",value)
            .put("T0",SystemClock.elapsedRealtimeNanos()))
        return id
    }
    @Synchronized fun mark(id: Long, phase: String, detail: String = "") {
        if (id == 0L || SystemClock.elapsedRealtime() >= until) return
        val row = rows.firstOrNull { it.optLong("id") == id } ?: return
        if (!row.has(phase)) row.put(phase,SystemClock.elapsedRealtimeNanos())
        if (detail.isNotEmpty()) row.put(phase+"Detail",detail)
    }
    @Synchronized fun target(id: Long, pkg: String) {
        rows.firstOrNull { it.optLong("id") == id }?.put("package",pkg)
        mark(id,"T1",pkg)
    }
    @Synchronized fun observed(pkg: String, rate: Int, mode: String, phase: String): Long {
        if (SystemClock.elapsedRealtime() >= until) return 0
        rows.lastOrNull()?.let { row ->
            if (row.optString("package") == pkg && row.optString("value") ==
                (if (row.optString("kind") == "Refresh") rate.toString() else mode)) {
                val id = row.getLong("id")
                // Uperf Settings publication occurs only after the daemon's durable rule write.
                mark(id,"T2","authoritative_profile_observed")
                mark(id,phase)
                return id
            }
        }
        return 0
    }
    @Synchronized fun dump(out: PrintWriter) { rows.forEach { out.println("quickTrace=$it") } }
}
