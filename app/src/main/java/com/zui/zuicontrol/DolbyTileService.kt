package com.zui.zuicontrol

import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DolbyTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        Settings.Global.putInt(contentResolver, DOLBY_STATE, if (isEnabled()) 0 else 1)
        updateTile()
    }

    private fun isEnabled(): Boolean =
        Settings.Global.getInt(contentResolver, DOLBY_STATE, 1) == 1

    private fun updateTile() {
        val enabled = isEnabled()
        qsTile?.apply {
            icon = Icon.createWithResource(this@DolbyTileService, R.drawable.ic_stat_zuicontrol)
            label = getString(R.string.dolby_tile_label)
            subtitle = getString(if (enabled) R.string.dolby_tile_on else R.string.dolby_tile_off)
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private companion object {
        const val DOLBY_STATE = "dlb_dap_state"
    }
}
