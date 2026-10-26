package com.example.netbox

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.netbox.xray.VpnState
import com.example.netbox.xray.XrayVpnService

class NetBoxTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TILE_TOGGLE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                42,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = XrayVpnService.vpnState.value
        tile.state = if (state == VpnState.CONNECTED) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.netbox_vpn_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                VpnState.CONNECTED -> getString(R.string.vpn_tile_connected)
                VpnState.CONNECTING -> getString(R.string.vpn_tile_connecting)
                VpnState.DISCONNECTED -> getString(R.string.vpn_tile_disconnected)
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: android.content.Context) {
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context.applicationContext, NetBoxTileService::class.java)
            )
        }
    }
}
