package com.drcom.autologin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
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
 *
 * 所有请求都优先绑定 Wi-Fi 网络：国产 ROM 在 Wi-Fi 未认证（无互联网）时会把默认网络切到
 * 蜂窝数据，请求若走默认网络就永远到不了内网网关。
 */
object LoginEngine {

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) DrcomAutoLogin/1.0"
    private const val DEFAULT_TIMEOUT_MS = 10000
    private const val CHECK_TIMEOUT_MS = 8000
    private const val DISCOVER_TIMEOUT_MS = 5000
    private const val LOGIN_TIMEOUT_MS = 12000
    private const val MAC_FALLBACK = "000000000000"

    private val JSONP_GREEDY = Regex("\\((\\{.*\\})\\)", RegexOption.DOT_MATCHES_ALL)
    private val JSONP_LAZY = Regex("\\((\\{.*?\\})\\)", RegexOption.DOT_MATCHES_ALL)

    // -------------------------------------------------------------- 网络绑定

    /** 取当前 Wi-Fi 的 Network（用于把请求强制绑到 Wi-Fi，避免国产 ROM 在
     *  Wi-Fi 未认证时把默认网络切到蜂窝数据导致内网不可达）。拿不到返回 null。 */
    private fun wifiNetwork(ctx: Context): Network? {
        return try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 返回形如 "WIFI(wlan0)" / "默认网络" 的描述，仅用于日志。 */
    private fun networkLabel(ctx: Context): String {
        return try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = wifiNetwork(ctx) ?: return "默认网络"
            val name = cm.getLinkProperties(net)?.interfaceName
            if (name.isNullOrBlank()) "WIFI" else "WIFI($name)"
        } catch (_: Throwable) {
            "默认网络"
        }
    }

    /** 取 Wi-Fi 网络上的本机 IPv4 地址（仅用于日志，便于远程诊断）；拿不到返回 ""。 */
    private fun wifiIpv4(ctx: Context): String {
        return try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = wifiNetwork(ctx) ?: return ""
            cm.getLinkProperties(net)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.address?.hostAddress ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    // ---------------------------------------------------------------- HTTP

    /** HTTP 响应（状态码 + 正文）。只在网络层失败时抛 IOException。 */
    private data class HttpResult(val code: Int, val body: String)

    /** 发起 GET。优先绑定 Wi-Fi 网络（见 wifiNetwork 注释）。
     *  只要拿到了 HTTP 应答（含 4xx/5xx）就正常返回状态码与正文；
     *  只有网络层失败（连不上 / 超时 / 解析不了主机名）才抛 IOException。
     */
    private fun httpGet(ctx: Context, url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): HttpResult {
        val u = URL(url)
        val net = wifiNetwork(ctx)
        val conn = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Connection", "close")
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = try {
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Throwable) {
                ""
            }
            HttpResult(code, body)
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
    fun discoverIpMac(ctx: Context, host: String): Pair<String, String> {
        var ip = ""
        var mac = ""
        try {
            val txt = httpGet(ctx, "http://$host/drcom/chkstatus?callback=cb&jsVersion=4.X", DISCOVER_TIMEOUT_MS).body
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

    /** 网关探测结果。 */
    enum class ProbeState { ONLINE, OFFLINE, UNREACHABLE }

    data class ProbeResult(val state: ProbeState, val detail: String)

    /** 探测网关认证状态。
     *  - ONLINE       : 拿到 JSONP 且 result == 1
     *  - OFFLINE      : 拿到了 HTTP 应答（含 4xx/5xx）但未认证
     *  - UNREACHABLE  : 网络层失败（抛异常）
     *  detail 用于日志，例如 "HTTP 200, result=0" / "HTTP 404, 非 JSONP: <!DOCTYPE html>..."
     */
    fun probe(ctx: Context, host: String): ProbeResult {
        val url = "http://$host/drcom/chkstatus?callback=cb&jsVersion=4.X"
        return try {
            val res = httpGet(ctx, url, CHECK_TIMEOUT_MS)
            val json = extractJson(res.body)
            if (json == null) {
                ProbeResult(
                    ProbeState.OFFLINE,
                    "HTTP ${res.code}, 非 JSONP: ${res.body.take(120).replace("\n", " ")}"
                )
            } else {
                val r = json.optInt("result", -1)
                if (r == 1) ProbeResult(ProbeState.ONLINE, "HTTP ${res.code}, result=1")
                else ProbeResult(ProbeState.OFFLINE, "HTTP ${res.code}, result=$r")
            }
        } catch (t: Throwable) {
            ProbeResult(ProbeState.UNREACHABLE, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- 登录

    /** 返回 (是否成功, 服务端 msg 或本地诊断信息)。协议参数与 Windows 版完全一致，不要改。 */
    fun login(ctx: Context, cfg: Config, ip: String, mac: String): Pair<Boolean, String> {
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
        LogStore.log(
            ctx, "INFO",
            "登录请求: ${cfg.host}:${cfg.port}（网络=${networkLabel(ctx)}，本机IP=$ip，MAC=$mac）"
        )
        val res = try {
            httpGet(ctx, url, LOGIN_TIMEOUT_MS)
        } catch (t: Throwable) {
            val why = "请求失败: " + (t.message ?: t.javaClass.simpleName)
            LogStore.log(ctx, "ERROR", why)
            return false to why
        }
        LogStore.log(ctx, "INFO", "登录响应: HTTP ${res.code}, 前 200 字符: ${res.body.take(200).replace("\n", " ")}")
        val obj = extractJson(res.body)
        if (obj == null) {
            LogStore.log(ctx, "ERROR", "login 接口返回非 JSONP: HTTP ${res.code}, " + res.body.take(200).replace("\n", " "))
            return false to ("HTTP ${res.code}, 响应非 JSONP: " + res.body.take(120).replace("\n", " "))
        }
        val r = obj.optInt("result", -1)
        val msg = obj.optString("msg", "")
        LogStore.log(ctx, "INFO", "登录响应: result=$r msg=$msg")
        return (r == 1) to msg
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
        LogStore.log(ctx, "INFO", "========================================")
        LogStore.log(ctx, "INFO", "开始检查 (reason=$reason)")
        LogStore.log(ctx, "INFO", "使用网络: ${networkLabel(ctx)}   本机IP: ${wifiIpv4(ctx).ifEmpty { "未知" }}")

        LogStore.log(ctx, "INFO", "探测网关 http://${cfg.host}/drcom/chkstatus")
        val r = probe(ctx, cfg.host)
        if (r.state == ProbeState.UNREACHABLE) {
            LogStore.log(ctx, "WARN", "探测结果: UNREACHABLE (${r.detail})")
            LogStore.log(ctx, "WARN", "网关不可达，跳过本次检查")
            Prefs.saveStatus(
                ctx,
                networkReachable = false,
                online = null,
                lastError = "网关不可达: ${r.detail}"
            )
            return false
        }
        LogStore.log(ctx, "INFO", "探测结果: ${r.state} (${r.detail})")

        if (r.state == ProbeState.ONLINE) {
            LogStore.log(ctx, "INFO", "已在线")
            Prefs.saveStatus(ctx, networkReachable = true, online = true, lastError = null)
            return true
        }

        // OFFLINE：拿到了网关响应但未认证 —— 必须继续走登录流程，否则会「未认证 → 永远不认证」
        Prefs.saveStatus(ctx, networkReachable = true, online = false, lastError = null)

        if (cfg.password.isEmpty()) {
            LogStore.log(ctx, "WARN", "密码未设置，跳过登录")
            Prefs.saveStatus(ctx, networkReachable = true, online = false, lastError = "密码未设置")
            return false
        }

        val (ip, mac) = discoverIpMac(ctx, cfg.host)
        LogStore.log(ctx, "INFO", "正在登录 ${cfg.account}${cfg.suffix} ...")

        val (ok, msg) = login(ctx, cfg, ip, mac)
        if (ok) {
            LogStore.log(ctx, "INFO", "登录成功")
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
