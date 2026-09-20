package com.drcom.autologin

import android.content.Context
import android.content.SharedPreferences

/** 运行状态快照，供主界面状态卡片展示。 */
data class RuntimeStatus(
    val networkReachable: Boolean?,
    val online: Boolean?,
    val lastCheckAt: Long,
    val lastError: String?
)

object Prefs {

    private const val FILE = "drcom_prefs"

    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_SUFFIX = "suffix"
    private const val KEY_PASSWORD = "password"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_INTERVAL = "interval_minutes"
    private const val KEY_SSID_FILTER = "ssid_filter"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_MAC_TYPE = "mac_type"

    private const val KEY_STATUS_NETWORK = "status_network"
    private const val KEY_STATUS_ONLINE = "status_online"
    private const val KEY_STATUS_LAST_CHECK = "status_last_check"
    private const val KEY_STATUS_LAST_ERROR = "status_last_error"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val p = sp(ctx)
        val d = Config.DEFAULT
        val port = p.getInt(KEY_PORT, d.port)
        val interval = p.getInt(KEY_INTERVAL, d.intervalMinutes)
        val macType = p.getInt(KEY_MAC_TYPE, d.macType)
        return Config(
            host = p.getString(KEY_HOST, d.host)?.takeIf { it.isNotBlank() } ?: d.host,
            port = if (port in 1..65535) port else d.port,
            account = p.getString(KEY_ACCOUNT, d.account) ?: d.account,
            suffix = p.getString(KEY_SUFFIX, d.suffix) ?: d.suffix,
            password = p.getString(KEY_PASSWORD, d.password) ?: d.password,
            autoCheck = p.getBoolean(KEY_AUTO_CHECK, d.autoCheck),
            intervalMinutes = if (interval > 0) interval else d.intervalMinutes,
            ssidFilter = p.getString(KEY_SSID_FILTER, d.ssidFilter) ?: d.ssidFilter,
            keepAlive = p.getBoolean(KEY_KEEP_ALIVE, d.keepAlive),
            macType = if (macType in 0..3) macType else d.macType
        )
    }

    fun save(ctx: Context, cfg: Config) {
        sp(ctx).edit()
            .putString(KEY_HOST, cfg.host)
            .putInt(KEY_PORT, cfg.port)
            .putString(KEY_ACCOUNT, cfg.account)
            .putString(KEY_SUFFIX, cfg.suffix)
            .putString(KEY_PASSWORD, cfg.password)
            .putBoolean(KEY_AUTO_CHECK, cfg.autoCheck)
            .putInt(KEY_INTERVAL, cfg.intervalMinutes)
            .putString(KEY_SSID_FILTER, cfg.ssidFilter)
            .putBoolean(KEY_KEEP_ALIVE, cfg.keepAlive)
            .putInt(KEY_MAC_TYPE, cfg.macType)
            .apply()
    }

    fun saveStatus(
        ctx: Context,
        networkReachable: Boolean?,
        online: Boolean?,
        lastError: String?,
        lastCheckAt: Long = System.currentTimeMillis()
    ) {
        val e = sp(ctx).edit()
        putTriState(e, KEY_STATUS_NETWORK, networkReachable)
        putTriState(e, KEY_STATUS_ONLINE, online)
        e.putLong(KEY_STATUS_LAST_CHECK, lastCheckAt)
        if (lastError == null) {
            e.remove(KEY_STATUS_LAST_ERROR)
        } else {
            e.putString(KEY_STATUS_LAST_ERROR, lastError)
        }
        e.apply()
    }

    fun readStatus(ctx: Context): RuntimeStatus {
        val p = sp(ctx)
        return RuntimeStatus(
            networkReachable = readTriState(p, KEY_STATUS_NETWORK),
            online = readTriState(p, KEY_STATUS_ONLINE),
            lastCheckAt = p.getLong(KEY_STATUS_LAST_CHECK, 0L),
            lastError = p.getString(KEY_STATUS_LAST_ERROR, null)
        )
    }

    private fun putTriState(e: SharedPreferences.Editor, key: String, v: Boolean?) {
        if (v == null) e.remove(key) else e.putBoolean(key, v)
    }

    private fun readTriState(p: SharedPreferences, key: String): Boolean? =
        if (p.contains(key)) p.getBoolean(key, false) else null
}
