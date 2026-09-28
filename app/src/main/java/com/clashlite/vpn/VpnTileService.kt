package com.clashlite.vpn

import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clashlite.MainActivity

/** 快捷设置磁贴：下拉通知栏一键开关 VPN（v2.0） */
class VpnTileService : TileService() {

    override fun onStartListening() {
        // 系统展示磁贴时同步一次状态
        updateTileState()
    }

    override fun onClick() {
        if (VpnRuntime.running.value) {
            startService(
                Intent(this, ClashVpnService::class.java).setAction(ClashVpnService.ACTION_STOP)
            )
            VpnRuntime.running.value = false
            VpnRuntime.statusText.value = "未连接"
        } else if (VpnService.prepare(this) == null) {
            // 已授权：直接连接
            startForegroundService(
                Intent(this, ClashVpnService::class.java).setAction(ClashVpnService.ACTION_START)
            )
            VpnRuntime.statusText.value = "连接中"
        } else {
            // 未授权：打开主界面完成授权流程
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        updateTileState()
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            tile.state = if (VpnRuntime.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    companion object {
        /** VPN 连接/断开后由服务调用，让系统刷新磁贴状态 */
        fun requestTileUpdate(context: android.content.Context) {
            requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
    }
}
