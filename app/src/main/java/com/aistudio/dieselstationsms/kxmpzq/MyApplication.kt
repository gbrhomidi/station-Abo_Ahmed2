package com.aistudio.dieselstationsms.kxmpzq

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.util.Date

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // ✅ استخدام cacheDir بدلاً من ExternalStorage (آمن لـ Android 10+)
                val logFile = File(cacheDir, "crash_log.txt")
                logFile.appendText("${Date()}: ${throwable.stackTraceToString()}\n\n")
            } catch (e: Exception) {
                // في حال فشل الكتابة، نكتفي بالتسجيل في Logcat
                Log.e("CrashHandler", "Failed to write crash log", e)
            }
            // إعادة إطلاق الخطأ إلى المعالج الافتراضي
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
