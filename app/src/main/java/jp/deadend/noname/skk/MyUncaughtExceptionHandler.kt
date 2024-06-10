package jp.deadend.noname.skk

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.PrintWriter
import java.lang.Thread.UncaughtExceptionHandler
import java.text.SimpleDateFormat
import java.util.Date
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build

internal class MyUncaughtExceptionHandler(val context: Context) : UncaughtExceptionHandler {
    private val mDefaultUEH: UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
    private val mVersionName: String

    init {
        val packInfo: PackageInfo
        try {
            packInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: NameNotFoundException) {
            e.printStackTrace()
            error("MyUncaughtExceptionHandler: name not found")
        }

        mVersionName = packInfo.versionName
    }

    override fun uncaughtException(th: Thread, t: Throwable) {
        try {
            saveState(t)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }

        mDefaultUEH?.uncaughtException(th, t)
    }

    @Throws(FileNotFoundException::class)
    private fun saveState(e: Throwable) {
        val d = Date()
        val df = SimpleDateFormat("yyyyMMddHHmm")
        val dateTimeStr = df.format(d)

        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, "SKK_strace_$dateTimeStr.txt")
        val pw = PrintWriter(FileOutputStream(file))

        pw.println("This is a crash report of SKK.")
        pw.println()
        pw.println("Device:  " + Build.DEVICE)
        pw.println("Model:   " + Build.MODEL)
        pw.println("SDK:     " + Build.VERSION.SDK_INT)
        pw.println("Version: " + mVersionName)
        pw.println()

        e.printStackTrace(pw)
        pw.close()
    }
}
