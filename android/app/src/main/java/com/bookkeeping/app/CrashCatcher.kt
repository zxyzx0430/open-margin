package com.bookkeeping.app

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 捕获未处理异常：先把堆栈落盘，再拉起 CrashActivity 展示。
 *
 * 为什么需要：release 包闪退时用户只能描述“一打开就退”，
 * 没有堆栈就只能靠猜。这里把堆栈同时写进应用私有目录和系统下载目录，
 * 用户要么在 CrashActivity 里一键复制，要么在文件管理器里找到 txt 分享出来。
 */
object CrashCatcher {
    private const val FILE_NAME = "crash-last.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = runCatching { buildReport(app, error) }.getOrNull()
            if (report != null) {
                runCatching { File(app.filesDir, FILE_NAME).writeText(report) }
                runCatching { writeToDownloads(app, report) }
                runCatching { show(app, report) }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun buildReport(app: Application, error: Throwable): String {
        val version = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("时间: $time")
            appendLine("版本: $version")
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
                    "(SDK ${Build.VERSION.SDK_INT})"
            )
            appendLine("线程: ${Thread.currentThread().name}")
            appendLine()
            append(Log.getStackTraceString(error))
        }
    }

    /** Android 10+ 写到系统下载目录，文件管理器可直接看到并分享。 */
    private fun writeToDownloads(app: Application, report: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val name = "qingheng-crash-" +
            SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun show(app: Application, report: String) {
        val intent = Intent(app, CrashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(CrashActivity.EXTRA_REPORT, report)
        app.startActivity(intent)
    }
}
