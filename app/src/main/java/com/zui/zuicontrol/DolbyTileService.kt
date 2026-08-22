package com.zui.zuicontrol

import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DolbyTileService : TileService() {
    private val stateObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateTile()
        }
    }
    private var observing = false

    override fun onStartListening() {
        super.onStartListening()
        if (!observing) {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(DOLBY_STATE),
                false,
                stateObserver,
            )
            observing = true
        }
        updateTile()
    }

    override fun onStopListening() {
        if (observing) {
            contentResolver.unregisterContentObserver(stateObserver)
            observing = false
        }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val nextState = if (isEnabled()) 0 else 1
        Settings.System.putInt(contentResolver, DOLBY_HEADSET_STATE, nextState)
        Settings.Global.putInt(contentResolver, DOLBY_STATE, nextState)
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
        const val DOLBY_HEADSET_STATE = "recordDolbySwitchStatusWidthHeadSet"
        const val DOLBY_STATE = "dlb_dap_state"
    }
}
