package com.tangyuan.trackpad

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 状态栏下拉里的快捷开关。
 */
class TrackpadTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        if (TrackpadService.isRunning) {
            startService(
                Intent(this, TrackpadService::class.java).setAction(TrackpadService.ACTION_STOP)
            )
        } else {
            if (!Settings.canDrawOverlays(this)) {
                openApp()
                return
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, TrackpadService::class.java)
            )
        }

        // startForegroundService 的 onCreate 是异步的，等一下再刷新图标状态
        handler.postDelayed({ refreshTile() }, 260L)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.state = if (TrackpadService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (TrackpadService.isRunning) "已开启" else "已关闭"
        }
        tile.label = "虚拟触控板"
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 21, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
