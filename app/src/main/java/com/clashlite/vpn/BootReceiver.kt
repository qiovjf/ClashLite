package com.clashlite.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clashlite.data.ProfilesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** 开机自启（需用户在设置中开启；若此前已授予 VPN 权限可直接连接） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = runBlocking { ProfilesRepository(context).settings.first() }
        if (settings.autoBoot && settings.activeProfileId != null) {
            context.startForegroundService(
                Intent(context, ClashVpnService::class.java).setAction(ClashVpnService.ACTION_START)
            )
        }
    }
}
