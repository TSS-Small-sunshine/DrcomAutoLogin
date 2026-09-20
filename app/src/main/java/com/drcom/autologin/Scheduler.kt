package com.drcom.autologin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object Scheduler {

    const val WORK_NAME = "drcom_periodic"
    const val NOW_WORK_NAME = "drcom_now"
    const val REASON_MANUAL = "manual"

    private const val MIN_INTERVAL_MINUTES = 15

    private fun constraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /** 注册/更新周期任务（WorkManager 最小周期 15 分钟，小于则夹到 15）。 */
    fun ensurePeriodic(ctx: Context) {
        val cfg = Prefs.load(ctx)
        val minutes = if (cfg.intervalMinutes < MIN_INTERVAL_MINUTES) MIN_INTERVAL_MINUTES else cfg.intervalMinutes
        val req = PeriodicWorkRequestBuilder<LoginWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setInputData(workDataOf(LoginWorker.KEY_REASON to "periodic"))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    /** 立刻跑一次（OneTimeWork，唯一名 drcom_now，REPLACE）。 */
    fun runNow(ctx: Context, reason: String) {
        val req = OneTimeWorkRequestBuilder<LoginWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(LoginWorker.KEY_REASON to reason))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }
}
