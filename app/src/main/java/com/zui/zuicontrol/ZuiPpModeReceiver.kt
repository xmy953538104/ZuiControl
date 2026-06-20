package com.zui.zuicontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process

class ZuiPpModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ZuiControlContract.ACTION_APPLY_PP_MODE) {
            return
        }
        val uid = sentUid()
        if (!trustedUid(uid)) {
            ZuiPpModeBridge.publishDenied(context, uid)
            return
        }
        val packageName = intent.getStringExtra(ZuiControlContract.EXTRA_PACKAGE).orEmpty()
        val mode = intent.getIntExtra(ZuiControlContract.EXTRA_PP_MODE, -1)
        ZuiPpModeBridge.apply(context, packageName, mode)
    }

    private fun sentUid(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getSentFromUid()
        } else {
            Process.SYSTEM_UID
        }

    private fun trustedUid(uid: Int): Boolean =
        uid == Process.ROOT_UID ||
            uid == Process.SYSTEM_UID ||
            uid == Process.SHELL_UID ||
            uid == Process.myUid()
}
