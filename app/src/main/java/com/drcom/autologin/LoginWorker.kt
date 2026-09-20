package com.drcom.autologin

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
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
        Log.d(TAG, "LoginWorker.doWork 启动 reason=$reason")

        // 手动触发无视「自动检查」开关
        if (!cfg.autoCheck && reason != Scheduler.REASON_MANUAL) {
            LogStore.log(ctx, "INFO", "自动检查已关闭，跳过 (reason=$reason)")
            Log.i(TAG, "LoginWorker 自动检查已关闭，跳过 (reason=$reason)")
            return Result.success()
        }

        val filter = cfg.ssidFilter.trim()
        if (filter.isNotEmpty()) {
            val allow = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val ssid = LoginEngine.currentSsid(ctx)
            if (ssid == null) {
                LogStore.log(ctx, "WARN", "无法读取当前 SSID（可能缺少定位权限），继续执行")
                Log.w(TAG, "LoginWorker 无法读取当前 SSID（可能缺少定位权限），继续执行")
            } else if (allow.none { it.equals(ssid, ignoreCase = true) }) {
                LogStore.log(ctx, "INFO", "当前 SSID=$ssid 不在白名单，跳过本次检查")
                Log.i(TAG, "LoginWorker SSID=$ssid 不在白名单 $allow, 跳过本次检查")
                return Result.success()
            }
        }

        try {
            // 离线时 runOnce 会先尝试启动网页版登录（见 tryStartPortalLogin），
            // 启动不了才退回 HTTP 登录。
            Log.d(TAG, "LoginWorker 调用 LoginEngine.runOnce")
            LoginEngine.runOnce(ctx, reason)
            Log.d(TAG, "LoginWorker LoginEngine.runOnce 返回")
        } catch (t: Throwable) {
            LogStore.log(ctx, "ERROR", "执行异常: " + (t.message ?: t.javaClass.simpleName))
            Log.e(TAG, "LoginWorker.runOnce 异常: " + (t.message ?: t.javaClass.simpleName), t)
        }
        // 失败靠下一次周期重试，不用 Result.retry（避免 backoff 越等越久）
        Log.d(TAG, "LoginWorker.doWork 完成 reason=$reason")
        return Result.success()
    }

    companion object {
        const val KEY_REASON = "reason"

        /** 统一 logcat tag：`adb logcat -s DrcomAutoLogin:V`。 */
        private const val TAG = "DrcomAutoLogin"

        /** 离线时尝试用「网页版登录」（WebView 跑门户页面 JS）接管本次登录。
         *
         *  为什么要有它：AC 的终端归类（PC / 手机）不取决于 HTTP 参数，实测 App 直接请求
         *  `/eportal/portal/login` 会被一律归成 PC 终端，只有浏览器同款流程才被正确归类。
         *
         *  Android 10+ 后台启动 Activity 需要「显示在其他应用上层（悬浮窗）」权限；
         *  没有权限时**不尝试**启动，只写一条日志，由调用方继续走原有 HTTP 登录兜底。
         *
         *  @return true 表示已成功发起启动请求（登录结果由 PortalLoginActivity 自己写状态）
         */
        fun tryStartPortalLogin(
            ctx: Context,
            userIp: String,
            userMac: String,
            acIp: String,
            acName: String
        ): Boolean {
            if (!Settings.canDrawOverlays(ctx)) {
                LogStore.log(
                    ctx, "WARN",
                    "WebView 未授予悬浮窗权限，后台无法启动网页版登录；请打开 App 手动登录或授予该权限"
                )
                Log.w(TAG, "LoginWorker.tryStartPortalLogin 未授予悬浮窗权限, 跳过启动")
                return false
            }
            return try {
                val intent = PortalLoginActivity
                    .buildIntent(ctx, userIp, userMac, acIp, acName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                LogStore.log(ctx, "INFO", "WebView 已启动网页版登录")
                Log.i(TAG, "LoginWorker.tryStartPortalLogin 已启动 PortalLoginActivity (userIp=$userIp userMac=$userMac acIp=$acIp acName=$acName)")
                true
            } catch (t: Throwable) {
                LogStore.log(ctx, "WARN", "WebView 启动网页版登录失败: " + (t.message ?: t.javaClass.simpleName))
                Log.e(TAG, "LoginWorker.tryStartPortalLogin 启动失败: " + (t.message ?: t.javaClass.simpleName), t)
                false
            }
        }
    }
}
