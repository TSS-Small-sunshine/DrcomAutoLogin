package com.drcom.autologin

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class LoginWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val cfg = Prefs.load(ctx)
        val reason = inputData.getString(KEY_REASON) ?: "worker"

        // 手动触发无视「自动检查」开关
        if (!cfg.autoCheck && reason != Scheduler.REASON_MANUAL) {
            LogStore.log(ctx, "INFO", "自动检查已关闭，跳过 (reason=$reason)")
            return Result.success()
        }

        val filter = cfg.ssidFilter.trim()
        if (filter.isNotEmpty()) {
            val allow = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val ssid = LoginEngine.currentSsid(ctx)
            if (ssid == null) {
                LogStore.log(ctx, "WARN", "无法读取当前 SSID（可能缺少定位权限），继续执行")
            } else if (allow.none { it.equals(ssid, ignoreCase = true) }) {
                LogStore.log(ctx, "INFO", "当前 SSID=$ssid 不在白名单，跳过本次检查")
                return Result.success()
            }
        }

        try {
            LoginEngine.runOnce(ctx, reason)
        } catch (t: Throwable) {
            LogStore.log(ctx, "ERROR", "执行异常: " + (t.message ?: t.javaClass.simpleName))
        }
        // 失败靠下一次周期重试，不用 Result.retry（避免 backoff 越等越久）
        return Result.success()
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}
