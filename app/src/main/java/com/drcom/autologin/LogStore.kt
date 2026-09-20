package com.drcom.autologin

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 极简文件日志：<filesDir>/drcom.log，最多保留 200 行。 */
object LogStore {

    private const val FILE_NAME = "drcom.log"
    private const val MAX_LINES = 200

    private val FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun log(ctx: Context, level: String, msg: String) {
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
