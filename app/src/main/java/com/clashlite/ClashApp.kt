package com.clashlite

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ClashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 恢复订阅自动更新的周期调度（WorkManager 自身持久化，此处保证一致）
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            val hours = com.clashlite.data.ProfilesRepository(this@ClashApp).autoUpdateHours.first()
            com.clashlite.work.AutoUpdateWorker.schedule(this@ClashApp, hours)
        }
    }

    companion object {
        lateinit var instance: ClashApp
            private set
    }
}
