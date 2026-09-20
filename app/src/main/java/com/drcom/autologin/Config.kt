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
    val keepAlive: Boolean,
    val macType: Int               // 终端类型：0-不发送 1-PC 2-手机 3-平板
) {
    companion object {
        val DEFAULT = Config("172.16.80.3", 801, "", "", "", true, 30, "", false, 2)
        val SUFFIX_LABELS = listOf("校园用户（无后缀）", "移动 @yd", "电信 @dx", "联通 @lt")
        val SUFFIX_VALUES = listOf("", "@yd", "@dx", "@lt")
        val INTERVAL_OPTIONS = listOf(15, 30, 60, 120)
        val MAC_TYPE_LABELS = listOf("手机 (2)", "PC (1)", "平板 (3)", "不发送 (0)")
        val MAC_TYPE_VALUES = listOf(2, 1, 3, 0)
    }
}
