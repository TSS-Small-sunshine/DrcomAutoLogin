package com.drcom.autologin

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.random.Random

/**
 * Dr.COM 校园网登录引擎。
 *
 * 协议与 Windows 版 (联网_service.py) 完全一致，不要改动参数：
 *  - 在线检查: GET http://{host}/drcom/chkstatus?callback=cb&jsVersion=4.X      (端口 80)
 *  - 登录:     GET http://{host}:{port}/eportal/portal/login?callback=dr{RAND}&... (端口 801)
 */
object LoginEngine {

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) DrcomAutoLogin/1.0"
    private const val DEFAULT_TIMEOUT_MS = 10000
    private const val CHECK_TIMEOUT_MS = 8000
    private const val DISCOVER_TIMEOUT_MS = 5000
    private const val LOGIN_TIMEOUT_MS = 12000
    private const val MAC_FALLBACK = "000000000000"

    private val JSONP_GREEDY = Regex("\\((\\{.*\\})\\)", RegexOption.DOT_MULTILINE)
    private val JSONP_LAZY = Regex("\\((\\{.*?\\})\\)", RegexOption.DOT_MULTILINE)

    // ---------------------------------------------------------------- HTTP

    fun httpGet(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setInstanceFollowRedirects(true)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) "" else BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 本机信息

    /** UDP connect 拿本机 IP（不发包），失败返回 ""。 */
    fun localIpFor(host: String): String {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            sock.connect(InetSocketAddress(host, 80))
            sock.localAddress?.hostAddress ?: ""
        } catch (_: Throwable) {
            ""
        } finally {
            try {
                sock?.close()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    /** 先试 chkstatus 的 v4ip / olmac，IP 再 fallback localIpFor，MAC 再 fallback 000000000000。 */
    fun discoverIpMac(host: String): Pair<String, String> {
        var ip = ""
        var mac = ""
        try {
            val txt = httpGet("http://$host/drcom/chkstatus?callback=cb&jsVersion=4.X", DISCOVER_TIMEOUT_MS)
            val obj = extractJson(txt)
            if (obj != null) {
                ip = obj.optString("v4ip", "").trim()
                mac = obj.optString("olmac", "").trim()
                    .uppercase(Locale.US)
                    .replace(":", "")
                    .replace("-", "")
            }
        } catch (_: Throwable) {
            // 忽略，走 fallback
        }
        if (ip.isEmpty()) ip = localIpFor(host)
        if (mac.isEmpty()) mac = MAC_FALLBACK
        return ip to mac
    }

    // -------------------------------------------------------------- 在线检查

    /** true=已在线, false=未在线, null=网络不可达 / 解析失败。 */
    fun checkOnline(host: String): Boolean? {
        val url = "http://$host/drcom/chkstatus?callback=cb&jsVersion=4.X"
        val txt = try {
            httpGet(url, CHECK_TIMEOUT_MS)
        } catch (_: Throwable) {
            return null
        }
        val obj = extractJson(txt) ?: return null
        return when (obj.optInt("result", -1)) {
            1 -> true
            0 -> false
            else -> null
        }
    }

    // ---------------------------------------------------------------- 登录

    /** 返回 (是否成功, 服务端 msg 或本地诊断信息)。 */
    fun login(cfg: Config, ip: String, mac: String): Pair<Boolean, String> {
        val callback = "dr" + Random.nextInt(1000, 10000)
        val params = linkedMapOf(
            "callback" to callback,
            "login_method" to "1",
            "user_account" to (cfg.account + cfg.suffix),
            "user_password" to cfg.password,
            "wlan_user_ip" to ip,
            "wlan_user_ipv6" to "",
            "wlan_user_mac" to mac,
            "wlan_ac_ip" to "",
            "wlan_ac_name" to "",
            "terminal_type" to "1",
            "jsVersion" to "4.1.3",
            "lang" to "zh-cn",
            "v" to Random.nextInt(1000, 10000).toString()
        )
        val query = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val url = "http://" + cfg.host + ":" + cfg.port + "/eportal/portal/login?" + query
        val txt = try {
            httpGet(url, LOGIN_TIMEOUT_MS)
        } catch (t: Throwable) {
            return false to ("请求失败: " + (t.message ?: t.javaClass.simpleName))
        }
        val obj = extractJson(txt) ?: return false to ("login 接口返回非 JSONP: " + txt.take(80))
        val ok = obj.optInt("result", -1) == 1
        val msg = obj.optString("msg", "")
        return ok to msg
    }

    // ------------------------------------------------------------- 当前 SSID

    /** 读取当前 WiFi SSID；无权限 / 失败 / 未知返回 null。 */
    @Suppress("DEPRECATION")
    fun currentSsid(ctx: Context): String? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val info = wm.connectionInfo ?: return null
            val raw = info.ssid ?: return null
            val ssid = raw.trim().trim('"')
            if (ssid.isEmpty() || ssid == "<unknown ssid>" || ssid.equals("0x", true)) null else ssid
        } catch (_: Throwable) {
            null
        }
    }

    // -------------------------------------------------------------- 完整流程

    /** 查在线 → 在线直接返回 / 离线则登录。全程写 LogStore，返回最终是否在线。 */
    fun runOnce(ctx: Context, reason: String): Boolean {
        val cfg = Prefs.load(ctx)
        LogStore.log(ctx, "INFO", "开始检查 (reason=$reason)")

        val online = checkOnline(cfg.host)
        if (online == null) {
            val err = "网关不可达（可能不在校园网）"
            LogStore.log(ctx, "WARN", err)
            Prefs.saveStatus(ctx, networkReachable = false, online = null, lastError = err)
            return false
        }
        if (online) {
            LogStore.log(ctx, "INFO", "已在线")
            Prefs.saveStatus(ctx, networkReachable = true, online = true, lastError = null)
            return true
        }

        Prefs.saveStatus(ctx, networkReachable = true, online = false, lastError = null)

        if (cfg.password.isEmpty()) {
            LogStore.log(ctx, "WARN", "密码未设置")
            Prefs.saveStatus(ctx, networkReachable = true, online = false, lastError = "密码未设置")
            return false
        }

        val (ip, mac) = discoverIpMac(cfg.host)
        LogStore.log(ctx, "INFO", "本机 IP=$ip MAC=$mac")

        val (ok, msg) = login(cfg, ip, mac)
        if (ok) {
            LogStore.log(ctx, "INFO", "登录成功: $msg")
            Prefs.saveStatus(ctx, networkReachable = true, online = true, lastError = null)
        } else {
            LogStore.log(ctx, "ERROR", "登录失败: $msg")
            Prefs.saveStatus(ctx, networkReachable = true, online = false, lastError = msg)
        }
        return ok
    }

    // ---------------------------------------------------------------- 工具

    /** 从 JSONP（cb({...}) / dr1234({...})）中提取 JSON 对象；解析不了返回 null。 */
    fun extractJson(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        for (re in listOf(JSONP_GREEDY, JSONP_LAZY)) {
            val m = re.find(text) ?: continue
            try {
                return JSONObject(m.groupValues[1])
            } catch (_: Throwable) {
                // 换下一个正则重试
            }
        }
        return try {
            JSONObject(text.trim())
        } catch (_: Throwable) {
            null
        }
    }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }
}
