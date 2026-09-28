package com.clashlite.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.PeriodicWorkRequestBuilder
import com.clashlite.data.ProfilesRepository
import com.clashlite.core.SubscriptionUpdater
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** 订阅自动更新后台任务（WorkManager 周期调度） */
class AutoUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = ProfilesRepository(applicationContext)
        val profiles = repo.profiles.first()
        profiles.filter { it.url.isNotBlank() }.forEach { profile ->
            runCatching { SubscriptionUpdater.refreshProfile(applicationContext, profile.id, profile.url) }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "profile_auto_update"

        /** 按周期调度/取消订阅自动更新（hours<=0 表示关闭） */
        fun schedule(context: Context, hours: Int) {
            val wm = WorkManager.getInstance(context)
            if (hours <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutoUpdateWorker>(hours.toLong(), TimeUnit.HOURS).build(),
            )
        }
    }
}
