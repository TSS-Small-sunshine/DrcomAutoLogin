package com.drcom.autologin

data class Config(
    val host: String,
    val port: Int,
    val account: String,
    val suffix: String,
    val password: String,
    val autoCheck: Boolean,
    val intervalMinutes: Int,
    val ssidFilter: String,        // 逗号分隔的 SSID 白名单；空=不过滤
    val keepAlive: Boolean
) {
    companion object {
        val DEFAULT = Config("172.16.80.3", 801, "", "", "", true, 30, "", false)
        val SUFFIX_LABELS = listOf("校园用户（无后缀）", "移动 @yd", "电信 @dx", "联通 @lt")
        val SUFFIX_VALUES = listOf("", "@yd", "@dx", "@lt")
        val INTERVAL_OPTIONS = listOf(15, 30, 60, 120)
    }
}
