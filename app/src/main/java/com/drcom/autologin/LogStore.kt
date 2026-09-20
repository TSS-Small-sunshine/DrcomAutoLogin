package com.drcom.autologin

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 极简文件日志：<filesDir>/drcom.log，最多保留 200 行。 */
object LogStore {

    private const val FILE_NAME = "drcom.log"
    private const val MAX_LINES = 200

    /** 统一 logcat tag，便于 `adb logcat -s DrcomAutoLogin:V` 一键过滤。 */
    private const val LOG_TAG = "DrcomAutoLogin"

    private val FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun log(ctx: Context, level: String, msg: String) {
        // 先把每条 LogStore 写入映射一份到 android.util.Log，tag 统一为 LOG_TAG，
        // 这样 adb logcat -s DrcomAutoLogin:V 能直接抓到所有 LogStore 调用方写的日志，
        // 而无须每个调用方自己再写一份 android.util.Log。
        try {
            when (level) {
                "INFO" -> Log.i(LOG_TAG, msg)
                "WARN" -> Log.w(LOG_TAG, msg)
                "ERROR" -> Log.e(LOG_TAG, msg)
                else -> Log.d(LOG_TAG, msg)
            }
        } catch (_: Throwable) {
            // logcat 输出失败不能影响主流程
        }
        try {
            val line = "[" + FORMAT.format(Date()) + "] [" + level + "] " + msg
            val lines = readLines(file(ctx)).toMutableList()
            lines.add(line)
            val kept = if (lines.size > MAX_LINES) lines.subList(lines.size - MAX_LINES, lines.size) else lines
            file(ctx).writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
        } catch (_: Throwable) {
            // 日志失败不能影响主流程
        }
    }

    @Synchronized
    fun read(ctx: Context, maxLines: Int = 100): String {
        return try {
            val lines = readLines(file(ctx))
            val tail = if (lines.size > maxLines) lines.subList(lines.size - maxLines, lines.size) else lines
            tail.joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            file(ctx).writeText("", Charsets.UTF_8)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun readLines(f: File): List<String> {
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    }
}
