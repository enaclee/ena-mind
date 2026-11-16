package com.enamind.ai.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashGuard — страховочная сетка диагностики.
 *
 * Любое необработанное исключение в ЛЮБОМ потоке (в т.ч. OOM/StackOverflow в
 * корутинах движка) пишется в filesDir/enamind/crash_report.log (последние 3
 * краша). Лог показывается в Настройках → Диагностика и входит в экспортный ZIP —
 * если приложение упадёт снова, точная причина будет известна без logcat.
 *
 * После записи обработчик передаёт исключение системному обработчику —
 * приложение завершается как обычно (мы не скрываем краш).
 */
object CrashGuard {

    private const val MAX_REPORTS = 3
    const val FILE_NAME = "crash_report.log"

    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appCtx, thread, throwable)
            } catch (_: Throwable) {
                // даже если запись упала — не мешаем системному обработчику
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeReport(ctx: Context, thread: Thread, t: Throwable) {
        val dir = File(ctx.filesDir, "enamind").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("=== CRASH $stamp ===\n")
        sb.append("thread: ${thread.name} (id ${thread.id})\n")
        sb.append("app: ENA-MIND ${appVersion(ctx)} · android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("python_ready: ${PythonBridge.isReady()} init_error=${PythonBridge.lastInitError ?: "нет"}\n")
        sb.append("exception: ${t.javaClass.name}: ${t.message}\n")
        sb.append(t.stackTraceToString())
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 5) {
            sb.append("caused by: ${cause.javaClass.name}: ${cause.message}\n")
            sb.append(cause.stackTraceToString())
            cause = cause.cause
            depth++
        }
        sb.append("\n")
        // ротация: храним последние MAX_REPORTS
        val f = File(dir, FILE_NAME)
        val old = if (f.isFile) f.readText() else ""
        f.writeText(sb.toString() + trimReports(old))
    }

    private fun trimReports(text: String): String {
        val parts = text.split("(?=\n=== CRASH )".toRegex()).filter { it.isNotBlank() }
        return parts.takeLast(MAX_REPORTS - 1).joinToString("")
    }

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Последний отчёт (или null) — для экрана диагностики. */
    fun lastReport(ctx: Context): String? {
        val f = File(File(ctx.filesDir, "enamind"), FILE_NAME)
        return if (f.isFile && f.length() > 0) f.readText() else null
    }

    fun clear(ctx: Context) {
        File(File(ctx.filesDir, "enamind"), FILE_NAME).delete()
    }
}
