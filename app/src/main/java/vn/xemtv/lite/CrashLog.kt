package vn.xemtv.lite

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ghi lại lỗi làm app thoát để lần sau xem được nguyên nhân ngay trong Cài đặt (TV không có cách xem log). */
object CrashLog {
    private const val FILE = "crash.log"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(app.filesDir, FILE).writeText("$time  (luồng ${t.name})\n" + sw.toString().take(4000))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** Lý do Android ghi nhận cho lần thoát gần nhất, chỉ khi bất thường (Android 11 trở lên). */
    @SuppressLint("NewApi")
    private fun lastExit(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val i = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            val name = when (i.reason) {
                ApplicationExitInfo.REASON_CRASH -> "CRASH (lỗi Java)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (lỗi bộ giải mã/hệ thống)"
                ApplicationExitInfo.REASON_ANR -> "ANR (treo, không phản hồi)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (hết RAM, bị hệ thống tắt)"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (bị tiến trình khác tắt)"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                else -> return null      // thoát bình thường: không báo
            }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(i.timestamp))
            "Android ghi nhận: $name lúc $time" + (i.description?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
        } catch (e: Exception) { null }
    }

    /** Tóm tắt lần thoát bất thường gần nhất, hoặc null nếu không có. */
    fun summary(ctx: Context): String? {
        val crash = try {
            File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.lines()?.take(6)?.joinToString("\n")
        } catch (e: Exception) { null }
        val exit = lastExit(ctx)
        return listOfNotNull(exit, crash).joinToString("\n").ifBlank { null }
    }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) {}
    }
}
