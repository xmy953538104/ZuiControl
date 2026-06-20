package com.zui.zuicontrol

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Settings

object ZuiPpModeBridge {
    private val gameModeUri = Uri.parse("content://com.zui.performance.utils.GameModeProvider/contact")

    fun apply(context: Context, packageName: String, mode: Int): Boolean {
        if (!PackageNames.isValid(packageName) || mode !in 0..2) {
            publish(context, "state=invalid;package=$packageName;mode=$mode")
            return false
        }
        return runCatching {
            val rows = context.contentResolver.update(
                gameModeUri,
                ContentValues().apply { put("gameMode", mode.toString()) },
                null,
                null,
            )
            publish(context, "state=done;package=$packageName;mode=$mode;rows=$rows")
            true
        }.getOrElse {
            publish(context, "state=failed;package=$packageName;mode=$mode;error=${it.cleanMessage()}")
            false
        }
    }

    fun publishDenied(context: Context, uid: Int) {
        publish(context, "state=denied;uid=$uid")
    }

    private fun publish(context: Context, state: String) {
        Settings.System.putString(
            context.contentResolver,
            ZuiControlContract.KEY_PP_MODE_STATE,
            "time=${System.currentTimeMillis()};$state",
        )
    }

    private fun Throwable.cleanMessage(): String =
        (message ?: javaClass.simpleName).replace(';', ',').replace('\n', ' ')
}
