package com.drcom.autologin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
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

// 本校园环境（福建农业职业技术学院）的门户与 AC 地址，用于门户参数探测；其它学校需修改。
// 例：AC/网关自身为 http://172.16.80.2/ 与 http://172.16.80.3/（门户页 http://172.16.80.3/a79.htm）。
private const val PORTAL_HOST = "172.16.80.3"
private const val PORTAL_AC_HOST = "172.16.80.2"

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

    // 真实移动端 Chrome UA：门户用 util.getTermType() 按 UA 判断设备类型，自定义串会被识别成未知终端
    // （网页版登录 PortalLoginActivity 的 WebView 也用同一个 UA，避免两处硬编码）
    internal const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; 22127RK46C) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
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
            HttpResult(code, readBody(conn, code))
        } finally {
            conn.disconnect()
        }
    }

    /** 读响应正文（2xx 走 inputStream，其余走 errorStream）；读不到返回 ""。 */
    private fun readBody(conn: HttpURLConnection, code: Int): String {
        return try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Throwable) {
            ""
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

    /** chkstatus 正文里的兜底取值（正文不是合法 JSONP 时用）。
     *  键名与 a41.js:1183-1184 / 1206-1207 完全一致：IP = v46ip,ss5,v4ip,ss3；MAC = ss4,olmac。 */
    private val IP_KEYS_RE = Regex("\"(v46ip|ss5|v4ip|ss3)\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val MAC_KEYS_RE = Regex("\"(ss4|olmac)\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    /** IP 候选的取值优先级（照抄 a41.js:1183 / 1206 的 `||` 链顺序）。 */
    private val IP_KEY_ORDER = listOf("v46ip", "ss5", "v4ip", "ss3")

    /** MAC 候选的取值优先级（照抄 a41.js:1184 / 1207 的 `||` 链顺序）。 */
    private val MAC_KEY_ORDER = listOf("ss4", "olmac")

    /** 日志要打印的原始字段（与门户 chkstatus 返回的键同名）。 */
    private val RAW_KEY_ORDER = listOf("v46ip", "ss5", "v4ip", "ss3", "ss4", "olmac")

    /** 把 Dr.COM 的 16 进制 IP（如 "c1a85517"）转成点分十进制（193.168.85.23）。
     *  支持 8 位十六进制；其它长度或非法字符返回 ""。等价于 a41.js:858 的 util.hex16ToString。 */
    private fun hex16ToIp(hex: String): String {
        val h = hex.trim().lowercase().removePrefix("0x")
        if (h.length != 8 || !h.all { it in "0123456789abcdef" }) return ""
        return try {
            val v = h.toLong(16)
            "${(v shr 24) and 0xFFL}.${(v shr 16) and 0xFFL}.${(v shr 8) and 0xFFL}.${v and 0xFFL}"
        } catch (_: Throwable) {
            ""
        }
    }

    /** IP 候选是否可用：非空、非 "null"、非全零占位，且是合法 IPv4。 */
    private fun isUsableIp(raw: String): Boolean {
        val v = raw.trim()
        if (v.isEmpty() || v.equals("null", true)) return false
        if (v == "0.0.0.0" || v == "000.000.000.000") return false
        if (!isValidIpv4(v)) return false
        return v.split('.').any { it.toInt() != 0 }
    }

    /** 取某个候选键对应的可用 IP：ss3 走 hex16ToIp 转换，其余按原值；不可用返回 ""。 */
    private fun usableIpOf(key: String, raw: String): String {
        val v = if (key == "ss3") hex16ToIp(raw) else raw.trim()
        return if (isUsableIp(v)) v else ""
    }

    /** 取某个候选键对应的可用 MAC（去掉 - 和 : 并大写）；空 / "null" / 全零占位返回 ""。 */
    private fun usableMacOf(raw: String): String {
        val v = normalizeMac(raw)
        if (v.isEmpty() || v.equals("null", true) || v == MAC_FALLBACK) return ""
        return v
    }

    /** 日志用：原始字段的值，没有就返回 "(无)"。 */
    private fun rawFieldText(raw: Map<String, String>, key: String): String {
        val v = raw[key]?.trim().orEmpty()
        return if (v.isEmpty()) "(无)" else v
    }

    /** 本机信息探测结果。ipSource / macSource 记录命中的候选键名；
     *  一级都没命中时分别是 "本机网卡" / "兜底"。 */
    data class LocalIpMac(
        val ip: String,
        val mac: String,
        val ipSource: String,
        val macSource: String
    )

    /** 按门户的精确优先级从 chkstatus 取 wlan_user_ip / wlan_user_mac。
     *
     *  优先级照抄 a41.js:1183-1184（及 chkstatus 分支 1206-1207）：
     *    IP  : v46ip → ss5 → v4ip → hex16ToIp(ss3) → 本机网卡
     *    MAC : ss4 → olmac → 000000000000
     *  注意：**不判断 result**——未认证（result=0）时正文里同样可能带着网关视角的
     *  v46ip / ss5 / ss3 / ss4 / olmac，那正是登录最需要的参数，必须无条件尝试取值。 */
    fun discoverIpMac(ctx: Context, host: String): LocalIpMac {
        var body = ""
        try {
            body = httpGet(ctx, "http://$host/drcom/chkstatus?callback=cb&jsVersion=4.X", DISCOVER_TIMEOUT_MS).body
        } catch (_: Throwable) {
            // 忽略，走 fallback
        }

        // 两套取值源同时准备：JSON 对象优先，原始正文正则补位（JSONP 被换成 HTML 时也能抠出）
        val raw = linkedMapOf<String, String>()
        val obj = extractJson(body)
        if (obj != null) {
            for (k in RAW_KEY_ORDER) {
                val v = obj.optString(k, "").trim()
                if (v.isNotEmpty()) raw[k] = v
            }
        }
        for (m in IP_KEYS_RE.findAll(body)) {
            val k = m.groupValues[1].lowercase(Locale.US)
            if (raw[k].isNullOrEmpty()) raw[k] = m.groupValues[2].trim()
        }
        for (m in MAC_KEYS_RE.findAll(body)) {
            val k = m.groupValues[1].lowercase(Locale.US)
            if (raw[k].isNullOrEmpty()) raw[k] = m.groupValues[2].trim()
        }

        // 按门户的 || 链顺序挑第一个合法值
        var ip = ""
        var ipSource = ""
        for (key in IP_KEY_ORDER) {
            val v = usableIpOf(key, raw[key] ?: "")
            if (v.isNotEmpty()) {
                ip = v
                ipSource = if (key == "ss3") "ss3十六进制" else key
                break
            }
        }
        var mac = ""
        var macSource = ""
        for (key in MAC_KEY_ORDER) {
            val v = usableMacOf(raw[key] ?: "")
            if (v.isNotEmpty()) {
                mac = v
                macSource = key
                break
            }
        }

        LogStore.log(
            ctx, "INFO",
            "chkstatus 取值: ip=${ip.ifEmpty { "(无)" }}${if (ip.isEmpty()) "" else "(来源=$ipSource)"} " +
                "mac=${mac.ifEmpty { "(无)" }}${if (mac.isEmpty()) "" else "(来源=$macSource)"} | " +
                "原始字段 " + RAW_KEY_ORDER.joinToString(" ") { "$it=${rawFieldText(raw, it)}" }
        )

        // 兜底：chkstatus 什么都给不出时，用本机网卡 IP / 全零 MAC（与门户的 || 链一致）
        if (ip.isEmpty()) {
            val local = localIpFor(host).trim()
            if (isUsableIp(local)) {
                ip = local
                ipSource = "本机网卡"
            } else {
                ipSource = "(未取到)"
            }
        }
        if (mac.isEmpty()) {
            mac = MAC_FALLBACK
            macSource = "兜底"
        }
        return LocalIpMac(ip, mac, ipSource, macSource)
    }

    // -------------------------------------------------------------- 在线检查

    /** 网关探测结果。 */
    enum class ProbeState { ONLINE, OFFLINE, UNREACHABLE }

    data class ProbeResult(val state: ProbeState, val detail: String)

    /** 探测网关认证状态。
     *  - ONLINE       : 拿到 JSONP 且 result == 1
     *  - OFFLINE      : 拿到了 HTTP 应答（含 4xx/5xx）但未认证
     *  - UNREACHABLE  : 网络层失败（抛异常）
     *  detail 用于日志，例如 "HTTP 200, result=0, 正文: cb({...})" / "HTTP 404, 非 JSONP: <!DOCTYPE html>..."
     */
    fun probe(ctx: Context, host: String): ProbeResult {
        val url = "http://$host/drcom/chkstatus?callback=cb&jsVersion=4.X"
        return try {
            val res = httpGet(ctx, url, CHECK_TIMEOUT_MS)
            val json = extractJson(res.body)
            if (json == null) {
                ProbeResult(
                    ProbeState.OFFLINE,
                    "HTTP ${res.code}, 非 JSONP: ${snippet(res.body)}"
                )
            } else {
                val r = json.optInt("result", -1)
                if (r == 1) {
                    ProbeResult(ProbeState.ONLINE, "HTTP ${res.code}, result=1")
                } else {
                    // 关键诊断：未认证时正文里可能就带着网关视角的 v4ip / olmac，必须打出来
                    ProbeResult(ProbeState.OFFLINE, "HTTP ${res.code}, result=$r, 正文: ${snippet(res.body)}")
                }
            }
        } catch (t: Throwable) {
            ProbeResult(ProbeState.UNREACHABLE, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------- 强制门户参数

    /** 网关视角的门户参数（从强制门户的 Location 头或门户页正文里解析）。
     *  internal：网页版登录（PortalLoginActivity）补参数时复用同一套解析结果。 */
    internal data class PortalParams(
        val userIp: String,
        val mac: String,      // 已归一化为 12 位大写十六进制（去掉 - 和 :）
        val acIp: String,
        val acName: String
    )

    /** 参数名兼容表：不同厂商固件命名不一致，按顺序取第一个非空值。 */
    private val IP_NAMES = arrayOf("wlanuserip", "wlan_user_ip", "userip", "user_ip")
    private val MAC_NAMES = arrayOf("mac", "wlanusermac", "wlan_user_mac")
    private val AC_IP_NAMES = arrayOf("wlanacip", "wlan_ac_ip", "acip")
    private val AC_NAME_NAMES = arrayOf("wlancname", "wlanacname", "wlan_ac_name", "acname")

    // 正文里的参数（覆盖 meta refresh / JS 变量 / hidden input 三种形态）
    private val PORTAL_PARAM_RE = Regex(
        """(wlan_?user_?ip|user_?ip)\s*[=:]\s*['"]?([0-9]{1,3}(?:\.[0-9]{1,3}){3})""",
        RegexOption.IGNORE_CASE
    )
    private val PORTAL_MAC_RE = Regex(
        """(mac|wlan_?user_?mac)\s*[=:]\s*['"]?([0-9A-Fa-f]{2}(?:[-:]?[0-9A-Fa-f]{2}){5})""",
        RegexOption.IGNORE_CASE
    )
    private val PORTAL_AC_IP_RE = Regex(
        """(wlan_?ac_?ip|acip)\s*[=:]\s*['"]?([0-9]{1,3}(?:\.[0-9]{1,3}){3})""",
        RegexOption.IGNORE_CASE
    )
    private val PORTAL_AC_NAME_RE = Regex(
        """(wlan_?ac_?name|wlancname|acname)\s*[=:]\s*['"]?([A-Za-z0-9_\-\.]{2,64})""",
        RegexOption.IGNORE_CASE
    )

    /** 日志用片段：前 300 字符 + 换行替换成空格。 */
    private fun snippet(text: String, max: Int = 300): String =
        text.take(max).replace("\n", " ").replace("\r", " ")

    /** 是否合法 IPv4（0-255.0-255.0-255.0-255）。 */
    private fun isValidIpv4(s: String): Boolean {
        if (s.isBlank()) return false
        val parts = s.trim().split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
        }
    }

    /** 归一化 MAC：去掉 - 和 :，转大写。 */
    private fun normalizeMac(raw: String): String =
        raw.trim().uppercase(Locale.US).replace(":", "").replace("-", "")

    /** 来源 1/2：从 query 串（Location 的 ?后面部分，或绝对化后的重定向地址）解析 4 个参数。
     *  IP 非合法 IPv4 视为未命中，返回 null。 */
    private fun paramsFromQuery(query: String?): PortalParams? {
        if (query.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse("http://portal.local/?$query")
            fun pick(names: Array<String>): String {
                for (n in names) {
                    val v = uri.getQueryParameter(n)?.trim().orEmpty()
                    if (v.isNotEmpty()) return v
                }
                return ""
            }
            val userIp = pick(IP_NAMES)
            if (!isValidIpv4(userIp)) return null
            PortalParams(
                userIp,
                normalizeMac(pick(MAC_NAMES)),
                pick(AC_IP_NAMES),
                pick(AC_NAME_NAMES)
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 来源 3：从响应正文里正则提取 4 个参数（meta refresh / JS 变量 / hidden input）。
     *  IP 非合法 IPv4 视为未命中，返回 null。 */
    private fun paramsFromBody(body: String): PortalParams? {
        if (body.isBlank()) return null
        val userIp = PORTAL_PARAM_RE.find(body)?.groupValues?.get(2)?.trim().orEmpty()
        if (!isValidIpv4(userIp)) return null
        val mac = PORTAL_MAC_RE.find(body)?.groupValues?.get(2)?.trim().orEmpty()
        val acIp = PORTAL_AC_IP_RE.find(body)?.groupValues?.get(2)?.trim().orEmpty()
        val acName = PORTAL_AC_NAME_RE.find(body)?.groupValues?.get(2)?.trim().orEmpty()
        return PortalParams(userIp, normalizeMac(mac), acIp, acName)
    }

    /** 触发强制门户重定向，按列表顺序依次尝试 12 个探测地址，从
     *  Location 头 / 绝对化后的重定向地址 / 响应正文 三个来源解析
     *  wlanuserip / mac / wlanacip / wlancname。任一命中即返回；全部失败返回 null。
     *  每个探测地址无论成功失败都写一条完整日志，便于远程诊断。
     *  全程只读，不改变任何服务端状态。
     *  internal：网页版登录（PortalLoginActivity）补齐网关参数时复用。 */
    internal fun discoverPortalParams(ctx: Context): PortalParams? {
        val probes = listOf(
            // 小米自带强制门户探测地址（本机是小米，系统自己就用这个，最可能被网关拦截）
            "http://connect.rom.miui.com/generate_204",
            // 常见系统的强制门户探测地址
            "http://www.msftconnecttest.com/connecttest.txt",
            "http://captive.apple.com/hotspot-detect.html",
            "http://detectportal.firefox.com/success.txt",
            "http://connectivitycheck.platform.hicloud.com/generate_204",
            // 普通外网 HTTP 站点（未认证时必然被网关拦截）
            "http://www.baidu.com/",
            "http://www.qq.com/",
            // 拿不到 DNS 也能命中的公网 IP
            "http://1.1.1.1/",
            "http://223.5.5.5/",
            // 网关与 AC 自身（本校园：http://172.16.80.2/ 与 http://172.16.80.3/a79.htm）
            "http://$PORTAL_AC_HOST/",
            "http://$PORTAL_HOST/",
            "http://$PORTAL_HOST/a79.htm"
        )
        for ((idx, probe) in probes.withIndex()) {
            val no = idx + 1
            try {
                val u = URL(probe)
                val net = wifiNetwork(ctx)
                val conn = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false      // 关键：不要跟，要读 Location
                conn.connectTimeout = 3000                // 12 个地址，最坏 36 秒；绝大多数会立即返回
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location")
                    val body = readBody(conn, code)
                    val body300 = snippet(body)
                    var line = "门户探测 #$no $probe → HTTP $code | Location=${loc ?: "(无)"}"
                    if (code == 200 && body300.isNotEmpty()) line += " | 正文前 300: $body300"
                    LogStore.log(ctx, "INFO", line)

                    // 来源 1/2：Location 头；若是相对路径，先用 URL(base, loc) 拼成绝对 URL 再解析
                    var hit: PortalParams? = null
                    var from = ""
                    if (!loc.isNullOrBlank()) {
                        val absLoc = try {
                            URL(u, loc).toString()
                        } catch (_: Throwable) {
                            loc
                        }
                        val byLocation = paramsFromQuery(absLoc.substringAfter('?', ""))
                        if (byLocation != null) {
                            hit = byLocation
                            from = "#$no Location"
                        }
                    }
                    // 来源 3：响应正文
                    if (hit == null) {
                        val byBody = paramsFromBody(body)
                        if (byBody != null) {
                            hit = byBody
                            from = "#$no 正文"
                        }
                    }
                    val found = hit
                    if (found != null) {
                        // 渲染效果示例: 门户参数(来自 #1 Location): ... / 门户参数(来自 #3 正文): ...
                        LogStore.log(
                            ctx, "INFO",
                            "门户参数(来自 $from): wlanuserip=${found.userIp} mac=${found.mac} " +
                                "wlanacip=${found.acIp} wlancname=${found.acName}"
                        )
                        return found
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                LogStore.log(ctx, "INFO", "门户探测 #$no $probe → 异常 ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        LogStore.log(ctx, "INFO", "门户探测: ${probes.size} 个地址均未命中")
        return null
    }

    // ---------------------------------------------------------------- 登录

    /** 终端类型的日志文本：如 `2(手机)`；0 表示不带该参数，打印 `不发送`。
     *  标签取自 Config.MAC_TYPE_LABELS，避免两处硬编码。 */
    private fun macTypeText(macType: Int): String {
        if (macType <= 0) return "不发送"
        val i = Config.MAC_TYPE_VALUES.indexOf(macType)
        if (i < 0) return macType.toString()
        val label = Config.MAC_TYPE_LABELS[i].removeSuffix(" (${Config.MAC_TYPE_VALUES[i]})")
        return "$macType($label)"
    }

    /** 返回 (是否成功, 服务端 msg 或本地诊断信息)。协议参数与 Windows 版完全一致，不要改。 */
    fun login(
        ctx: Context, cfg: Config,
        wlanUserIp: String, wlanUserMac: String,
        wlanAcIp: String, wlanAcName: String
    ): Pair<Boolean, String> {
        val callback = "dr" + Random.nextInt(1000, 10000)
        val params = linkedMapOf(
            "callback" to callback,
            "login_method" to "1",
            "user_account" to (cfg.account + cfg.suffix),
            "user_password" to cfg.password,
            "wlan_user_ip" to wlanUserIp,
            "wlan_user_ipv6" to "",
            "wlan_user_mac" to wlanUserMac,
            "wlan_ac_ip" to wlanAcIp,
            "wlan_ac_name" to wlanAcName,
            "jsVersion" to "4.1.3",
            "lang" to "zh-cn",
            "v" to Random.nextInt(1000, 10000).toString()
        )
        // 终端类型（门户 a41.js:423 的 mac_type 字段名）：AC 据此归类终端并套用该类型的在线数上限。
        // 0 = 不带该参数（保持旧行为）。放在 wlan_ac_name 之后，其余参数不动。
        if (cfg.macType > 0) {
            params["mac_type"] = cfg.macType.toString()
        }
        val query = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val url = "http://" + cfg.host + ":" + cfg.port + "/eportal/portal/login?" + query
        LogStore.log(
            ctx, "INFO",
            "登录请求: ${cfg.host}:${cfg.port}（网络=${networkLabel(ctx)}，本机IP=$wlanUserIp，MAC=$wlanUserMac，" +
                "终端类型=${macTypeText(cfg.macType)}）"
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

        // 优先用网关视角的参数（强制门户重定向里带的）；拿不到再退回本机探测
        val portal = discoverPortalParams(ctx)
        val local = discoverIpMac(ctx, cfg.host)
        val userIp = portal?.userIp?.takeIf { it.isNotEmpty() } ?: local.ip
        val userMac = portal?.mac?.takeIf { it.isNotEmpty() } ?: local.mac
        val acIp = portal?.acIp ?: ""
        val acName = portal?.acName ?: ""

        val srcLabel = if (portal != null) "门户重定向" else "本机兜底"
        LogStore.log(
            ctx, "INFO",
            "登录参数(来源=$srcLabel): ip=$userIp mac=$userMac " +
                "acIp=${acIp.ifEmpty { "(空)" }} acName=${acName.ifEmpty { "(空)" }}"
        )
        // 一眼看出最终 wlan_user_ip / wlan_user_mac 是哪一级取到的
        val ipFrom = if (portal?.userIp?.isNotEmpty() == true) "门户重定向" else local.ipSource
        val macFrom = if (portal?.mac?.isNotEmpty() == true) "门户重定向" else local.macSource
        LogStore.log(
            ctx, "INFO",
            "最终提交参数来源: wlan_user_ip=$userIp(来源=$ipFrom) wlan_user_mac=$userMac(来源=$macFrom)"
        )
        LogStore.log(ctx, "INFO", "正在登录 ${cfg.account}${cfg.suffix} ...")

        // 首选：网页版登录（WebView 跑门户页面自己的 JS，复刻浏览器行为）。
        // 实测 HTTP 直连 /eportal/portal/login 会被 AC 一律归成 PC 终端（「PC终端在线数已上限」），
        // 只有门户页面自己提交出来的会话才会被正确归类。后台启动 Activity 需要悬浮窗权限，
        // 拿不到权限或启动失败时（返回 false）继续走下面的 HTTP 登录兜底，行为不劣于以前。
        if (LoginWorker.tryStartPortalLogin(ctx, userIp, userMac, acIp, acName)) {
            LogStore.log(ctx, "INFO", "WebView 已接管本次登录，跳过 HTTP 登录")
            Prefs.saveStatus(ctx, networkReachable = true, online = false, lastError = null)
            return false
        }

        val (ok, msg) = login(ctx, cfg, userIp, userMac, acIp, acName)
        if (ok) {
            LogStore.log(ctx, "INFO", "登录成功")
            Prefs.saveStatus(ctx, networkReachable = true, online = true, lastError = null)
        } else {
            LogStore.log(ctx, "ERROR", "登录失败: $msg")
            Prefs.saveStatus(ctx, networkReachable = true, online = false, lastError = msg)
            if (msg.contains("终端")) {
                LogStore.log(ctx, "WARN", "提示：可能是终端类型不被接受，请在配置里换一个「终端类型」再试")
            }
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
