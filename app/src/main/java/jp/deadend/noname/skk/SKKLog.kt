package jp.deadend.noname.skk

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SKKLog {
    private const val TAG = "SKK"
    private const val LOG_BUFFER_SIZE = 32
    private val logBuffer = ArrayDeque<String>(LOG_BUFFER_SIZE)

    private var versionName: String? = null

    fun init(context: Context) {
        versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { saveCrashReport(context, throwable) }.onFailure { it.printStackTrace() }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun d(msg: String, tr: Throwable? = null) = log(android.util.Log.DEBUG, msg, tr)
    fun w(msg: String, tr: Throwable? = null) = log(android.util.Log.WARN, msg, tr)
    fun e(msg: String, tr: Throwable? = null) = log(android.util.Log.ERROR, msg, tr)

    private fun log(priority: Int, msg: String, tr: Throwable?) {
        val throwable = tr ?: Throwable()
        val stackTrace = throwable.stackTrace
        val caller = stackTrace.find {
            val name = it.className
            !name.startsWith(SKKLog.javaClass.name)
        }?.let { "${it.fileName}:${it.lineNumber}: " }.orEmpty()

        val isAutoThrowable = (tr == null || tr.javaClass == Throwable::class.java) &&
                throwable.message == null && throwable.cause == null
        val logLine = if (isAutoThrowable) {
            caller + msg
        } else {
            caller + msg + "\n" + android.util.Log.getStackTraceString(throwable)
        }

        when (priority) {
            android.util.Log.DEBUG -> if (BuildConfig.DEBUG) android.util.Log.d(TAG, logLine)
            android.util.Log.WARN -> android.util.Log.w(TAG, logLine)
            android.util.Log.ERROR -> {
                android.util.Log.e(TAG, logLine)
                if (BuildConfig.DEBUG) throw throwable
            }
        }

        if (runCatching { skkPrefs.logPrivacy }.getOrDefault(false)) synchronized(logBuffer) {
            logBuffer.addLast(logLine)
            if (logBuffer.size > LOG_BUFFER_SIZE) logBuffer.removeFirst()
        }
    }

    private fun saveCrashReport(context: Context, e: Throwable) {
        val d = Date()
        val dateTimeStr = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(d)
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, "SKK_strace_$dateTimeStr.txt")

        PrintWriter(FileOutputStream(file).buffered()).use { pw ->
            pw.println("This is a crash report of SKK.")
            pw.println()
            pw.println("Date:    $d")
            pw.println("Device:  ${Build.DEVICE}")
            pw.println("Model:   ${Build.MODEL}")
            pw.println("SDK:     ${Build.VERSION.SDK_INT}")
            pw.println("Version: $versionName")
            pw.println()
            pw.println("Recent logs:")
            synchronized(logBuffer) {
                if (logBuffer.isEmpty()) pw.println("(disabled or empty)")
                else logBuffer.forEach { pw.println(it) }
            }
            pw.println()
            e.printStackTrace(pw)
        }
    }

    internal fun recentLog() = synchronized(logBuffer) {
        if (logBuffer.isNotEmpty()) logBuffer.joinToString("\n", "\n-- 現在のログ --\n")
        else ""
    }
}
