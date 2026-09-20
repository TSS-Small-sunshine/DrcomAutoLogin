package com.drcom.autologin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        LogStore.log(context, "INFO", "开机/更新触发: $action")

        val cfg = Prefs.load(context)
        if (cfg.autoCheck) {
            Scheduler.ensurePeriodic(context)
        }
        Scheduler.runNow(context, "boot")

        if (cfg.keepAlive) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            } catch (t: Throwable) {
                // Android 12+ 后台启动 FGS 可能被拒，不能崩
                LogStore.log(context, "WARN", "后台启动保活服务失败: " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }
}
