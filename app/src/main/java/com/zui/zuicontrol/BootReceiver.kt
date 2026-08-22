package com.zui.zuicontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> {
                normalizeDolbyTile(context)
                ZuiControlQuickService.start(context)
            }
        }
    }

    private fun normalizeDolbyTile(context: Context) {
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, QS_TILES) ?: return
        val normalized = buildList {
            add(ZUI_DOLBY_TILE)
            current.split(',')
                .map(String::trim)
                .filter { it.isNotEmpty() && it != STOCK_DOLBY_TILE && it != ZUI_DOLBY_TILE }
                .forEach(::add)
        }.joinToString(",")
        if (normalized != current) {
            Settings.Secure.putString(resolver, QS_TILES, normalized)
        }
    }

    private companion object {
        const val QS_TILES = "sysui_qs_tiles"
        const val STOCK_DOLBY_TILE = "dolbyatmos"
        const val ZUI_DOLBY_TILE = "custom(com.zui.zuicontrol/.DolbyTileService)"
    }
}
