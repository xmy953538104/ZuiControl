package com.zui.zuicontrol

import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ZuiControlTileService : TileService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        updateTile(ZuiControlClient.editableDisplayHz())
    }

    override fun onClick() {
        super.onClick()
        val current = ZuiControlClient.editableDisplayHz()
        val next = nextRate(current)
        updateTile(next)
        Thread {
            val reply = ZuiControlClient.setCurrentSceneDisplayHz(next)
            val refreshed = if (reply.ok) {
                ZuiControlClient.editableDisplayHz() ?: next
            } else {
                current
            }
            handler.post { updateTile(refreshed) }
        }.start()
    }

    private fun nextRate(current: Int?): Int {
        val rates = ZuiControlContract.rates
        val index = rates.indexOf(current).takeIf { it >= 0 } ?: rates.indexOf(120)
        return rates[(index + 1) % rates.size]
    }

    private fun updateTile(rate: Int?) {
        val tile = qsTile ?: return
        val displayRate = rate ?: 120
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_zuicontrol)
        tile.label = "ZuiControl"
        tile.subtitle = "${displayRate}Hz"
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
