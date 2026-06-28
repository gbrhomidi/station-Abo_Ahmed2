package com.aistudio.dieselstationsms.kxmpzq

import android.app.Application
import java.io.File
import java.util.Date

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val logFile = File(getExternalFilesDir(null), "crash_log.txt")
            logFile.appendText("${Date()}: ${throwable.stackTraceToString()}\n")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
