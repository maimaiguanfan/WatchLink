package io.github.watchlink.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.watchlink.R
import io.github.watchlink.validation.HealthHostControl

/**
 * Passive Quick Settings tile displaying live watch connection state, battery percentage,
 * and charging state queried on-demand from Huawei Health :PhoneService.
 * Single tap manually refreshes status; long press opens WatchLink (via QS_TILE_PREFERENCES).
 * Never runs as a persistent background service across reboots.
 */
class WatchLinkTileService : TileService() {
    @Volatile
    private var listening = false
    @Volatile
    private var lastReply: HealthHostControl.Reply? = null

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        render(refreshing = lastReply == null, reply = lastReply)
        queryWatchStatus(HealthHostControl.QUERY)
    }

    override fun onStopListening() {
        listening = false
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        render(refreshing = true, reply = lastReply)
        queryWatchStatus(HealthHostControl.REFRESH_WATCH_STATUS)
    }

    private fun queryWatchStatus(command: String) {
        HealthHostControl.send(applicationContext, command) { reply ->
            lastReply = reply
            if (listening) {
                render(refreshing = false, reply = reply)
            }
        }
    }

    private fun render(refreshing: Boolean, reply: HealthHostControl.Reply?) {
        val tile = qsTile ?: return
        val presentation = WatchTileFormatter.format(
            refreshing,
            reply?.reachable() == true,
            reply?.watchConnected() == true,
            reply?.watchName() ?: "",
            reply?.watchBattery() ?: -1,
            reply?.watchCharging() == true,
        )
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_watch)
        tile.label = presentation.label()
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = presentation.subtitle()
        }
        tile.state = if (presentation.state() == WatchTileFormatter.STATE_ACTIVE) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
