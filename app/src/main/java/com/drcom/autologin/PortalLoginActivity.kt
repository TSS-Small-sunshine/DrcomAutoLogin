package com.drcom.autologin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

/**
 * 「网页版登录」：透明、无界面，用 WebView 跑门户自己的页面 + JS，复刻手机浏览器的认证流程。
 *
 * 为什么必须这么做：AC 对终端的归类（PC / 手机）不取决于我们能控制的 HTTP 参数——同一台手机、
 * 同一张网卡、同一份 DHCP 指纹下，手机浏览器登录认证页成功，而 App 直接请求
 * `/eportal/portal/login` 始终被归成 PC 终端（提示「该账号PC终端在线数已上限」）。
 * 唯一可靠的解法是让门户页面自己的 JS 去提交登录。
 *
 * 流程：
 *  1. 组装门户 URL（`a79.htm`，带 mac / wlanacip / wlancname / wlanuserip，与浏览器落到认证页的 URL 形态一致）
 *  2. WebView 加载该 URL（透明 Activity，用户看不到界面，但 WebView 必须真正 attach 到窗口）
 *  3. 页面加载完成后注入 JS 填表并提交
 *  4. 每 2 秒探测一次是否在线（LoginEngine.probe），最多等 30 秒
 *  5. 写 Prefs 运行状态并 finish()
 *
 * 全程写 LogStore，日志前缀统一为 `WebView`，便于在「运行日志」里过滤。
 */
class PortalLoginActivity : Activity() {

    private var host: String = Config.DEFAULT.host
    private var port: Int = Config.DEFAULT.port
    private var account: String = ""
    private var suffix: String = ""
    private var password: String = ""

    private var userIp = ""
    private var userMac = ""
    private var acIp = ""
    private var acName = ""

    private var web: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var injectCount = 0      // 已注入 JS 的次数（最多 MAX_INJECT 次）
    private var verifying = false    // 是否已进入轮询校验
    private var done = false         // 是否已收尾（一次性）

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "PortalLoginActivity.onCreate 启动 (v1.6.1-debug)")

        val extras = intent
        val cfg = Prefs.load(this)
        host = extras.getStringExtra(EXTRA_HOST)?.trim().orEmpty().ifEmpty { cfg.host }
        port = extras.getIntExtra(EXTRA_PORT, cfg.port)
        account = extras.getStringExtra(EXTRA_ACCOUNT)?.takeIf { it.isNotEmpty() } ?: cfg.account
        suffix = extras.getStringExtra(EXTRA_SUFFIX) ?: cfg.suffix
        password = extras.getStringExtra(EXTRA_PASSWORD)?.takeIf { it.isNotEmpty() } ?: cfg.password
        userIp = extras.getStringExtra(EXTRA_USER_IP)?.trim().orEmpty()
        userMac = extras.getStringExtra(EXTRA_USER_MAC)?.trim().orEmpty()
        acIp = extras.getStringExtra(EXTRA_AC_IP)?.trim().orEmpty()
        acName = extras.getStringExtra(EXTRA_AC_NAME)?.trim().orEmpty()

        LogStore.log(this, "INFO", "WebView 启动网页版登录: 门户=$host:$port 账号=$account$suffix")
        Log.i(TAG, "PortalLoginActivity 收到启动参数: 门户=$host:$port 账号=$account$suffix userIp=${userIp.ifEmpty { "(空)" }} userMac=${userMac.ifEmpty { "(空)" }} acIp=${acIp.ifEmpty { "(空)" }} acName=${acName.ifEmpty { "(空)" }}")

        if (account.isEmpty() || password.isEmpty()) {
            LogStore.log(this, "ERROR", "WebView 账号或密码为空，无法网页版登录")
            Log.e(TAG, "PortalLoginActivity 账号或密码为空，提前结束")
            Prefs.saveStatus(
                this,
                networkReachable = true,
                online = false,
                lastError = "WebView 登录失败：账号或密码为空"
            )
            finish()
            return
        }

        if (userIp.isEmpty() || userMac.isEmpty() || acIp.isEmpty() || acName.isEmpty()) {
            LogStore.log(this, "INFO", "WebView 网关参数不完整，后台探测中…")
            Log.w(TAG, "PortalLoginActivity 网关参数不完整，后台探测中…")
            resolveParamsInBackground()
        } else {
            Log.d(TAG, "PortalLoginActivity 网关参数已就绪，直接 startWebView")
            startWebView()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "PortalLoginActivity.onDestroy done=$done verifying=$verifying")
        handler.removeCallbacksAndMessages(null)
        try {
            web?.stopLoading()
            web?.destroy()
        } catch (_: Throwable) {
            // 清理失败不影响收尾
        }
        web = null
        super.onDestroy()
    }

    // -------------------------------------------------------------- 参数补齐

    /** 缺参数时在后台线程补齐：优先按门户重定向（与 HTTP 登录路径同一套逻辑）取网关视角的
     *  wlanuserip / mac / wlanacip / wlancname，再退回 chkstatus + 本机网卡。
     *  网络请求绝不能在主线程做（会抛 NetworkOnMainThreadException）。 */
    private fun resolveParamsInBackground() {
        Log.d(TAG, "PortalLoginActivity.resolveParamsInBackground 启动后台探测线程")
        val ctx = applicationContext
        val targetHost = host
        Thread {
            try {
                val portal = try {
                    LoginEngine.discoverPortalParams(ctx)
                } catch (t: Throwable) {
                    LogStore.log(ctx, "WARN", "WebView 门户参数探测失败: " + (t.message ?: t.javaClass.simpleName))
                    Log.w(TAG, "PortalLoginActivity.discoverPortalParams 失败: " + (t.message ?: t.javaClass.simpleName))
                    null
                }
                if (portal != null) {
                    if (userIp.isEmpty()) userIp = portal.userIp
                    if (userMac.isEmpty()) userMac = portal.mac
                    if (acIp.isEmpty()) acIp = portal.acIp
                    if (acName.isEmpty()) acName = portal.acName
                }
                if (userIp.isEmpty() || userMac.isEmpty()) {
                    val local = LoginEngine.discoverIpMac(ctx, targetHost)
                    if (userIp.isEmpty()) userIp = local.ip
                    if (userMac.isEmpty()) userMac = local.mac
                }
                LogStore.log(
                    ctx, "INFO",
                    "WebView 网关参数: ip=${userIp.ifEmpty { "(空)" }} mac=${userMac.ifEmpty { "(空)" }} " +
                        "acIp=${acIp.ifEmpty { "(空)" }} acName=${acName.ifEmpty { "(空)" }}"
                )
                Log.i(TAG, "PortalLoginActivity 网关参数补齐: ip=$userIp mac=$userMac acIp=$acIp acName=$acName")
            } catch (t: Throwable) {
                LogStore.log(ctx, "WARN", "WebView 网关参数补齐异常: " + (t.message ?: t.javaClass.simpleName))
                Log.w(TAG, "PortalLoginActivity 网关参数补齐异常: " + (t.message ?: t.javaClass.simpleName))
            }
            handler.post { if (!done && !isFinishing) startWebView() }
        }.start()
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun startWebView() {
        if (done || isFinishing) return
        val url = portalUrl()
        LogStore.log(this, "INFO", "WebView 门户 URL: $url")
        Log.d(TAG, "PortalLoginActivity.startWebView URL: $url")

        CookieManager.getInstance().setAcceptCookie(true)

        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.settings.userAgentString = LoginEngine.USER_AGENT
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "WebView.onPageStarted: ${url.orEmpty()}")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "WebView.onPageFinished: ${url.orEmpty()}")
                onPageDone(url.orEmpty())
            }
        }

        web = w
        // 透明 Activity：不设 GONE / 不设不可见，WebView 必须真正 attach 到窗口才会执行 JS
        setContentView(w)
        Log.d(TAG, "PortalLoginActivity WebView 已 setContentView, 准备 loadUrl")

        w.loadUrl(url)
        Log.d(TAG, "PortalLoginActivity WebView.loadUrl 已调用")

        // 兜底：页面一直加载不出来（或表单注入多次都失败）时，也要走校验并收尾，避免留下一个
        // 永远不结束的透明 Activity。
        handler.postDelayed({
            if (!done && !verifying) {
                LogStore.log(this, "WARN", "WebView ${OVERALL_TIMEOUT_MS / 1000} 秒内未完成提交，转入在线校验")
                Log.w(TAG, "PortalLoginActivity ${OVERALL_TIMEOUT_MS / 1000} 秒兜底超时，转入在线校验")
                startVerify()
            }
        }, OVERALL_TIMEOUT_MS)
    }

    /** 门户 URL：形态与手机浏览器落到认证页的 URL 一致（mac 为 `E25B-367B-8DAC` 形式）。 */
    private fun portalUrl(): String =
        "http://$host/a79.htm?mac=${dashMac(userMac)}&rul=&wlanacip=$acIp&wlancname=$acName&wlanuserip=$userIp"

    /** 12 位十六进制 MAC → 每 4 位用 `-` 连接（E25B367B8DAC → E25B-367B-8DAC）；
     *  长度不对时原样返回。 */
    private fun dashMac(mac: String): String {
        val hex = mac.trim().uppercase(Locale.US).replace("-", "").replace(":", "")
        if (hex.length != 12) return mac.trim()
        return hex.chunked(4).joinToString("-")
    }

    // -------------------------------------------------------------- 页面回调

    /** 每次页面加载完成都会进来：先记日志，再判断是否需要注入 / 进入校验。 */
    private fun onPageDone(url: String) {
        LogStore.log(this, "INFO", "WebView 加载完成: $url")
        if (done || verifying) return

        // 已经不在认证页（不含 a79.htm，或跳到了 3.htm 之类的结果页）→ 判为可能成功
        if (!url.contains(PORTAL_PAGE, true) || url.contains("3.htm", true)) {
            LogStore.log(this, "INFO", "WebView 已离开认证页，开始校验在线状态")
            Log.d(TAG, "PortalLoginActivity 已离开认证页 (url=$url), 开始在线校验")
            startVerify()
            return
        }

        if (injectCount >= MAX_INJECT) {
            LogStore.log(this, "WARN", "WebView 注入已达 $MAX_INJECT 次仍停在认证页，等待兜底收尾")
            Log.w(TAG, "PortalLoginActivity 注入已达上限 $MAX_INJECT 次，等待兜底收尾")
            return
        }
        injectCount++
        val view = web ?: return
        val js = buildJs()
        Log.d(TAG, "PortalLoginActivity.evaluateJavascript 准备注入 (第 $injectCount 次)")
        view.evaluateJavascript(js) { result ->
            val text = result?.trim()?.removeSurrounding("\"") ?: ""
            LogStore.log(this, "INFO", "WebView 注入结果: $text（第 $injectCount 次）")
            Log.i(TAG, "PortalLoginActivity.evaluateJavascript 返回: $text (第 $injectCount 次)")
            if (text.contains("CLICKED") || text.contains("SUBMITTED")) {
                Log.d(TAG, "PortalLoginActivity 注入已触发提交，进入在线校验")
                startVerify()
            }
        }
    }

    // ---------------------------------------------------------------- 注入 JS

    /** 把账号 / 密码填进门户表单并提交。
     *  密码里可能含 `#`、`'`、`"`、反斜杠等，这里做的是 **JS 字符串转义**（不是 URL 编码）。 */
    private fun buildJs(): String =
        JS_TEMPLATE.trim()
            .replace("__ACCOUNT__", jsEscape(account + suffix))
            .replace("__PASSWORD__", jsEscape(password))

    /** JS 单引号字符串转义：反斜杠 / 单引号 / 双引号 / 换行 / 制表 / 行分隔符。 */
    private fun jsEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 校验

    /** 注入 / 提交后每 2 秒探测一次在线状态，最多等 VERIFY_TIMEOUT_MS。 */
    private fun startVerify() {
        if (verifying || done) return
        verifying = true
        LogStore.log(this, "INFO", "WebView 开始校验在线状态（最多 ${VERIFY_TIMEOUT_MS / 1000} 秒）")
        Log.i(TAG, "PortalLoginActivity.startVerify 启动在线校验 (最多 ${VERIFY_TIMEOUT_MS / 1000} 秒)")
        val ctx = applicationContext
        val targetHost = host
        Thread {
            val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
            var online = false
            var attempts = 0
            while (System.currentTimeMillis() < deadline) {
                attempts++
                val r = try {
                    LoginEngine.probe(ctx, targetHost)
                } catch (_: Throwable) {
                    null
                }
                Log.d(TAG, "PortalLoginActivity.probe 第 $attempts 次: ${r?.state} (${r?.detail})")
                if (r != null && r.state == LoginEngine.ProbeState.ONLINE) {
                    online = true
                    break
                }
                try {
                    Thread.sleep(VERIFY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            val ok = online
            Log.i(TAG, "PortalLoginActivity.probe 结束: online=$ok attempts=$attempts")
            handler.post { finishWith(ok) }
        }.start()
    }

    /** 收尾：写状态 + 日志 + finish()（只执行一次）。 */
    private fun finishWith(online: Boolean) {
        if (done) return
        done = true
        if (online) {
            LogStore.log(this, "INFO", "WebView 登录成功")
            Log.i(TAG, "PortalLoginActivity.finishWith: 登录成功, 准备 finish()")
            Prefs.saveStatus(this, networkReachable = true, online = true, lastError = null)
        } else {
            LogStore.log(this, "ERROR", "WebView 登录超时未生效")
            Log.e(TAG, "PortalLoginActivity.finishWith: 登录超时未生效, 准备 finish()")
            Prefs.saveStatus(this, networkReachable = true, online = false, lastError = "WebView 登录超时")
        }
        finish()
    }

    companion object {

        private const val PORTAL_PAGE = "a79.htm"
        private const val MAX_INJECT = 3
        private const val VERIFY_INTERVAL_MS = 2000L
        private const val VERIFY_TIMEOUT_MS = 30000L
        private const val OVERALL_TIMEOUT_MS = 60000L

        /** 统一 logcat tag：`adb logcat -s DrcomAutoLogin:V`。 */
        private const val TAG = "DrcomAutoLogin"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_SUFFIX = "suffix"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_USER_IP = "user_ip"
        const val EXTRA_USER_MAC = "user_mac"
        const val EXTRA_AC_IP = "ac_ip"
        const val EXTRA_AC_NAME = "ac_name"

        /** 构造启动 Intent（配置项从 Prefs 取，调用方不必自己拼）。
         *  四个网关视角参数允许留空，Activity 会自己在后台补齐。 */
        fun buildIntent(
            ctx: Context,
            userIp: String = "",
            userMac: String = "",
            acIp: String = "",
            acName: String = ""
        ): Intent {
            val cfg = Prefs.load(ctx)
            return Intent(ctx, PortalLoginActivity::class.java)
                .putExtra(EXTRA_HOST, cfg.host)
                .putExtra(EXTRA_PORT, cfg.port)
                .putExtra(EXTRA_ACCOUNT, cfg.account)
                .putExtra(EXTRA_SUFFIX, cfg.suffix)
                .putExtra(EXTRA_PASSWORD, cfg.password)
                .putExtra(EXTRA_USER_IP, userIp)
                .putExtra(EXTRA_USER_MAC, userMac)
                .putExtra(EXTRA_AC_IP, acIp)
                .putExtra(EXTRA_AC_NAME, acName)
        }

        /** 注入模板：覆盖多种可能的字段名 / 结构。
         *  `__ACCOUNT__` / `__PASSWORD__` 在注入前替换成 JS 转义后的字符串。 */
        private val JS_TEMPLATE = """
            (function () {
              function setVal(el, v) {
                if (!el) return false;
                var proto = Object.getPrototypeOf(el);
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                if (desc && desc.set) { desc.set.call(el, v); } else { el.value = v; }
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return true;
              }
              var u = document.querySelector('input[name="DDDDD"], input[name="user_account"], input[name="username"], input[type="text"]');
              var p = document.querySelector('input[name="upass"], input[name="user_password"], input[name="password"], input[type="password"]');
              var okU = setVal(u, '__ACCOUNT__');
              var okP = setVal(p, '__PASSWORD__');
              var btn = document.querySelector('#login, .login-btn, button[type="submit"], input[type="submit"], .loginButton, #btnLogin');
              if (btn) { btn.click(); return 'CLICKED'; }
              var f = (u && u.form) || document.querySelector('form');
              if (f) { f.submit(); return 'SUBMITTED'; }
              return 'u=' + okU + ',p=' + okP + ',noButton';
            })()
        """.trimIndent()
    }
}
