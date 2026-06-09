package com.zui.perfctl

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

object PerfCtlRequest {
    fun send(
        context: Context,
        cmd: String,
        rate: Int? = null,
        pkg: String? = null,
        mode: String? = null,
        cpuMax: Int? = null,
        cpuMin: Int? = null,
        gpuMax: Int? = null,
        gpuMin: Int? = null,
    ) {
        val resolver = context.contentResolver
        val requestId = "${System.currentTimeMillis()}_${SystemClock.elapsedRealtimeNanos()}_$cmd"
        val requestText = listOf(
            requestId,
            cmd,
            rate?.toString().orEmpty(),
            pkg.orEmpty().replace("|", ""),
            mode.orEmpty().replace("|", ""),
            cpuMax?.toString().orEmpty(),
            cpuMin?.toString().orEmpty(),
            gpuMax?.toString().orEmpty(),
            gpuMin?.toString().orEmpty(),
        ).joinToString("|")
        Settings.System.putString(
            resolver,
            PerfCtlContract.KEY_REQUEST_TEXT,
            requestText,
        )
    }

}
