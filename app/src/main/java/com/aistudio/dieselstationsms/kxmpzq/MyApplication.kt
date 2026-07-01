package com.aistudio.dieselstationsms.kxmpzq

import android.app.Application
import android.os.Build
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock

/**
 * MyApplication - محسّن ومصحح بالكامل
 *
 * التحسينات:
 * 1. Thread-Safety للتعامل مع الأعطال المتزامنة
 * 2. حد أقصى لعدد ملفات الأعطال (تنظيف تلقائي)
 * 3. التحقق من المساحة المتوفرة قبل الكتابة
 * 4. معالجة OutOfMemoryError بشكل آمن
 * 5. تنسيق JSON للتقارير (سهل التحليل)
 * 6. إضافة معلومات إضافية (الذاكرة، التخزين)
 */
class MyApplication : Application() {

    companion object {
        private const val TAG = "MyApplication"
        private const val CRASH_DIR = "crashes"
        private const val MAX_CRASH_FILES = 10
        private const val MIN_FREE_SPACE_MB = 5
        private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
        private val crashLock = ReentrantLock()
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
    }

    /**
     * إعداد معالج الأعطال العالمي
     */
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // التحقق من نوع الاستثناء للتعامل الخاص
            when (throwable) {
                is OutOfMemoryError -> handleOOMError(thread, throwable, defaultHandler)
                else -> handleNormalCrash(thread, throwable, defaultHandler)
            }
        }
    }

    /**
     * معالجة الأعطال العادية
     */
    private fun handleNormalCrash(
        thread: Thread,
        throwable: Throwable,
        defaultHandler: Thread.UncaughtExceptionHandler?
    ) {
        crashLock.lock()
        try {
            // التحقق من المساحة المتوفرة
            if (!hasEnoughSpace()) {
                Log.w(TAG, "Insufficient space for crash log")
                defaultHandler?.uncaughtException(thread, throwable)
                return
            }

            val crashDir = File(cacheDir, CRASH_DIR)
            crashDir.mkdirs()

            // تنظيف الملفات القديمة
            cleanupOldCrashes(crashDir)

            // إنشاء تقرير JSON مفصل
            val crashReport = buildCrashReport(thread, throwable)
            val fileName = "crash_${System.currentTimeMillis()}.json"
            val logFile = File(crashDir, fileName)

            // كتابة آمنة باستخدام FileWriter
            FileWriter(logFile).use { writer ->
                writer.write(crashReport)
                writer.flush()
            }

            Log.d(TAG, "Crash log saved: ${logFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log", e)
        } finally {
            crashLock.unlock()
        }

        // دائماً تفويض إلى المعالج الافتراضي
        defaultHandler?.uncaughtException(thread, throwable)
    }

    /**
     * معالجة OutOfMemoryError - استخدام أقل ذاكرة ممكن
     */
    private fun handleOOMError(
        thread: Thread,
        throwable: OutOfMemoryError,
        defaultHandler: Thread.UncaughtExceptionHandler?
    ) {
        try {
            // استخدام String بدلاً من StringBuilder لتوفير الذاكرة
            val simpleLog = "OOM at ${Date()}\nThread: ${thread.name}\n${throwable.message}"
            val crashDir = File(cacheDir, CRASH_DIR)
            crashDir.mkdirs()
            val logFile = File(crashDir, "oom_${System.currentTimeMillis()}.txt")
            logFile.writeText(simpleLog)
        } catch (e: Exception) {
            // تجاهل أي خطأ - لا نستطيع فعل المزيد
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    /**
     * بناء تقرير الأعطال بتنسيق JSON
     */
    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory

        return """
            {
                "timestamp": "${formatDate(Date())}",
                "thread": {
                    "name": "${escapeJson(thread.name)}",
                    "id": ${thread.id}
                },
                "exception": {
                    "type": "${throwable.javaClass.name}",
                    "message": "${escapeJson(throwable.message ?: "N/A")}",
                    "stacktrace": "${escapeJson(throwable.stackTraceToString())}"
                },
                "device": {
                    "manufacturer": "${escapeJson(Build.MANUFACTURER)}",
                    "model": "${escapeJson(Build.MODEL)}",
                    "brand": "${escapeJson(Build.BRAND)}",
                    "device": "${escapeJson(Build.DEVICE)}",
                    "hardware": "${escapeJson(Build.HARDWARE)}",
                    "android_version": "${Build.VERSION.RELEASE}",
                    "sdk_int": ${Build.VERSION.SDK_INT},
                    "fingerprint": "${escapeJson(Build.FINGERPRINT)}"
                },
                "memory": {
                    "total_mb": ${totalMemory / 1024 / 1024},
                    "free_mb": ${freeMemory / 1024 / 1024},
                    "used_mb": ${usedMemory / 1024 / 1024},
                    "max_mb": ${runtime.maxMemory() / 1024 / 1024}
                },
                "app": {
                    "package": "${packageName}",
                    "version": "${getAppVersion()}"
                }
            }
        """.trimIndent()
    }

    /**
     * التحقق من توفر مساحة كافية
     */
    private fun hasEnoughSpace(): Boolean {
        return try {
            val stat = StatFs(cacheDir.path)
            val availableBytes = stat.availableBytes
            availableBytes > MIN_FREE_SPACE_MB * 1024 * 1024
        } catch (e: Exception) {
            true // افتراضياً نسمح بالكتابة
        }
    }

    /**
     * تنظيف الملفات القديمة - الاحتفاظ بـ MAX_CRASH_FILES فقط
     */
    private fun cleanupOldCrashes(crashDir: File) {
        try {
            val files = crashDir.listFiles { f ->
                f.name.startsWith("crash_") || f.name.startsWith("oom_")
            } ?: return

            if (files.size > MAX_CRASH_FILES) {
                files.sortBy { it.lastModified() }
                files.take(files.size - MAX_CRASH_FILES).forEach { file ->
                    file.delete()
                    Log.d(TAG, "Deleted old crash log: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old crashes", e)
        }
    }

    /**
     * تنسيق التاريخ
     */
    private fun formatDate(date: Date): String {
        return SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(date)
    }

    /**
     * الحصول على إصدار التطبيق
     */
    private fun getAppVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * تهريب الأحرف الخاصة في JSON
     */
    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
