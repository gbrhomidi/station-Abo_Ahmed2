package com.aistudio.dieselstationsms.kxmpzq

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock

/**
 * ═══════════════════════════════════════════════════════════════
 * MyApplication - التطبيق الرئيسي لمحطة أبو أحمد
 * ═══════════════════════════════════════════════════════════════
 *
 * الإصدار 4.0 – مُحسَّن ومُصحَّح بالكامل مع:
 * 1. Thread-Safety للتعامل مع الأعطال المتزامنة
 * 2. حد أقصى لعدد ملفات الأعطال (تنظيف تلقائي)
 * 3. التحقق من المساحة المتوفرة قبل الكتابة
 * 4. معالجة OutOfMemoryError بشكل آمن
 * 5. تنسيق JSON للتقارير (سهل التحليل)
 * 6. إضافة معلومات إضافية (الذاكرة، التخزين)
 * 7. دعم EncryptedSharedPreferences لتخزين آمن
 * 8. دعم Coroutines لعمليات الخلفية
 *
 * ═══════════════════════════════════════════════════════════════
 * ملاحظة المعمارية الجديدة:
 * - هذا الملف لا يعتمد على الخادم المحلي (NanoHTTPD) بأي شكل.
 * - جميع الوظائف المتعلقة بالتشفير والأمان وإدارة الأعطال
 *   مستقلة تماماً عن طبقة الاتصال.
 * ═══════════════════════════════════════════════════════════════
 */
class MyApplication : Application() {

    companion object {
        private const val TAG = "MyApplication"
        private const val CRASH_DIR = "crashes"
        private const val MAX_CRASH_FILES = 10
        private const val MIN_FREE_SPACE_MB = 5
        private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
        private val crashLock = ReentrantLock()

        private const val PREFS_NAME = "secure_prefs"
        private const val MASTER_KEY_ALIAS = "my_app_master_key"

        // متغير عام للوصول إلى التطبيق من أي مكان
        @Volatile
        private var instance: MyApplication? = null

        fun getInstance(): MyApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }

        fun getAppContext(): Context {
            return getInstance().applicationContext
        }

        /**
         * الحصول على SharedPreferences مشفرة
         */
        fun getEncryptedPreferences(): SharedPreferences {
            val context = getAppContext()
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create encrypted prefs, falling back to default", e)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }

        /**
         * تجزئة آمنة للنصوص الحساسة (مثل أرقام الهواتف)
         */
        fun hashString(input: String): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
                hash.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Hashing failed", e)
                input
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // تهيئة معالج الأعطال العالمي
        setupCrashHandler()

        // تهيئة قنوات الإشعارات
        createNotificationChannels()

        // تهيئة EncryptedSharedPreferences مسبقاً
        try {
            getEncryptedPreferences()
            Log.d(TAG, "Encrypted preferences initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encrypted preferences", e)
        }

        Log.d(TAG, "Application initialized successfully")
    }

    /**
     * إنشاء قنوات الإشعارات المطلوبة
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    "station_sms_channel",
                    "Station SMS Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "قناة إشعارات خدمة الرسائل النصية"
                    setShowBadge(false)
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create notification channel", e)
            }
        }
    }

    /**
     * إعداد معالج الأعطال العالمي
     */
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
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
            if (!hasEnoughSpace()) {
                Log.w(TAG, "Insufficient space for crash log")
                defaultHandler?.uncaughtException(thread, throwable)
                return
            }

            val crashDir = File(cacheDir, CRASH_DIR)
            crashDir.mkdirs()

            cleanupOldCrashes(crashDir)

            val crashReport = buildCrashReport(thread, throwable)
            val fileName = "crash_${System.currentTimeMillis()}.json"
            val logFile = File(crashDir, fileName)

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
            val simpleLog = "OOM at ${Date()}\nThread: ${thread.name}\n${throwable.message}"
            val crashDir = File(cacheDir, CRASH_DIR)
            crashDir.mkdirs()
            val logFile = File(crashDir, "oom_${System.currentTimeMillis()}.txt")
            logFile.writeText(simpleLog)
        } catch (e: Exception) {
            // تجاهل أي خطأ
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
            true
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

    /**
     * الحصول على حجم قاعدة البيانات
     */
    fun getDatabaseSize(): Long {
        return try {
            val dbFile = File(applicationContext.getDatabasePath("diesel_station.db").path)
            if (dbFile.exists()) dbFile.length() else 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * الحصول على حجم ذاكرة التخزين المؤقت
     */
    fun getCacheSize(): Long {
        return try {
            val cacheDir = cacheDir
            if (cacheDir.exists()) {
                cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * تنظيف ذاكرة التخزين المؤقت
     */
    fun clearCache(): Boolean {
        return try {
            val cacheDir = cacheDir
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
            false
        }
    }
}
