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
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupDiagnostics
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/**
 * ═══════════════════════════════════════════════════════════════
 * MyApplication - التطبيق الرئيسي لمحطة أبو أحمد
 * ═══════════════════════════════════════════════════════════════
 *
 * الإصدار 5.0
 *
 * المسؤوليات:
 *
 * 1. تهيئة Application الأساسية.
 * 2. تهيئة StartupDiagnostics مبكرًا.
 * 3. تسجيل Crash عام للتطبيق.
 * 4. تسجيل Crash خاص بـ Startup عند الحاجة.
 * 5. إنشاء Notification Channels.
 * 6. تهيئة EncryptedSharedPreferences.
 * 7. توفير وظائف مساعدة للتخزين والتشخيص.
 *
 * الحدود المعمارية:
 *
 * - لا ينشئ InitializationPipeline.
 * - لا ينشئ StartupPolicyFactory.
 * - لا يشغّل SMSService مباشرة.
 * - لا يعتمد على EventBus.
 * - لا يعتمد على Coroutine.
 * - لا يعتمد على StartupCoordinator.
 *
 * تشغيل مراحل Startup والخدمات يتم من طبقة Startup المخصصة.
 *
 * StartupDiagnostics مستقلة عن MyApplication، ولذلك يمكن
 * استخدامها في مرحلة مبكرة جدًا من دورة التشغيل.
 */
class MyApplication : Application() {

    companion object {

        private const val TAG = "MyApplication"

        /*
         * Crash reports.
         */
        private const val CRASH_DIR = "crashes"

        private const val MAX_CRASH_FILES = 10

        private const val MIN_FREE_SPACE_MB = 5L

        private const val DATE_FORMAT =
            "yyyy-MM-dd HH:mm:ss.SSS"

        /*
         * Encrypted preferences.
         */
        private const val PREFS_NAME = "secure_prefs"

        private const val MASTER_KEY_ALIAS =
            "my_app_master_key"

        /*
         * Notification channel.
         */
        const val SMS_NOTIFICATION_CHANNEL_ID =
            "station_sms_channel"

        /*
         * Singleton.
         */
        @Volatile
        private var instance: MyApplication? = null

        /*
         * Crash-file lock.
         */
        private val crashLock = ReentrantLock()

        /**
         * الحصول على Instance التطبيق.
         */
        fun getInstance(): MyApplication {
            return instance
                ?: throw IllegalStateException(
                    "Application not initialized"
                )
        }

        /**
         * الحصول على Application Context.
         */
        fun getAppContext(): Context {
            return getInstance().applicationContext
        }

        /**
         * الحصول على SharedPreferences مشفرة.
         *
         * إذا تعذر إنشاء EncryptedSharedPreferences،
         * يتم استخدام SharedPreferences عادية كـ fallback
         * حتى لا يؤدي فشل طبقة التشفير إلى إسقاط التطبيق.
         */
        fun getEncryptedPreferences(): SharedPreferences {

            val context = getAppContext()

            return try {

                val masterKey =
                    MasterKey.Builder(context)
                        .setKeyScheme(
                            MasterKey.KeyScheme.AES256_GCM
                        )
                        .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences
                        .PrefKeyEncryptionScheme
                        .AES256_SIV,
                    EncryptedSharedPreferences
                        .PrefValueEncryptionScheme
                        .AES256_GCM
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to create encrypted preferences; " +
                        "using fallback preferences",
                    e
                )

                context.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
            }
        }

        /**
         * تجزئة SHA-256.
         *
         * تستخدم فقط عندما يحتاج أحد أجزاء التطبيق
         * إلى بصمة غير قابلة للعكس للنص.
         */
        fun hashString(input: String): String {

            return try {

                val digest =
                    MessageDigest.getInstance("SHA-256")

                val hash =
                    digest.digest(
                        input.toByteArray(
                            Charsets.UTF_8
                        )
                    )

                hash.joinToString("") {
                    "%02x".format(it)
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Hashing failed",
                    e
                )

                /*
                 * لا نرمي Exception من helper عام.
                 *
                 * ملاحظة:
                 * المستهلك الذي يتطلب SHA-256 حقيقيًا يجب ألا يعتمد
                 * على fallback هذا كقيمة أمنية.
                 */
                input
            }
        }
    }

    /**
     * تهيئة Application.
     */
    override fun onCreate() {
        super.onCreate()

        instance = this

        /*
         * ═══════════════════════════════════════════════════════
         * 1. Startup Diagnostics
         * ═══════════════════════════════════════════════════════
         *
         * يجب تهيئتها مبكرًا.
         *
         * لا تعتمد على MyApplication داخليًا.
         */
        initializeStartupDiagnostics()

        /*
         * ═══════════════════════════════════════════════════════
         * 2. Global Crash Handler
         * ═══════════════════════════════════════════════════════
         */
        setupCrashHandler()

        /*
         * ═══════════════════════════════════════════════════════
         * 3. Notification Channels
         * ═══════════════════════════════════════════════════════
         */
        createNotificationChannels()

        /*
         * ═══════════════════════════════════════════════════════
         * 4. Secure Preferences
         * ═══════════════════════════════════════════════════════
         */
        initializeEncryptedPreferences()

        Log.i(
            TAG,
            "Application initialized successfully"
        )
    }

    /**
     * تهيئة StartupDiagnostics.
     *
     * هذه العملية Best-Effort.
     *
     * أي فشل هنا يجب ألا يمنع Application من الإقلاع.
     */
    private fun initializeStartupDiagnostics() {

        try {

            StartupDiagnostics
                .getInstance(this)
                .initialize()

            Log.d(
                TAG,
                "StartupDiagnostics initialized"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "StartupDiagnostics initialization failed",
                e
            )
        }
    }

    /**
     * تهيئة EncryptedSharedPreferences مبكرًا.
     *
     * لا نحتفظ بنسخة static من SharedPreferences هنا
     * حتى لا نخلق state عالميًا غير ضروري.
     */
    private fun initializeEncryptedPreferences() {

        try {

            getEncryptedPreferences()

            Log.d(
                TAG,
                "Encrypted preferences initialized"
            )

        } catch (e: Exception) {

            /*
             * getEncryptedPreferences() يحتوي أصلًا على fallback،
             * لكن نحمي Application أيضًا من أي خطأ غير متوقع.
             */
            Log.e(
                TAG,
                "Encrypted preferences initialization failed",
                e
            )
        }
    }

    /**
     * إنشاء Notification Channels.
     */
    private fun createNotificationChannels() {

        if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        try {

            val channel =
                NotificationChannel(
                    SMS_NOTIFICATION_CHANNEL_ID,
                    "Station SMS Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "قناة إشعارات خدمة الرسائل النصية"

                    setShowBadge(false)
                }

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as? NotificationManager

            manager?.createNotificationChannel(channel)

            Log.d(
                TAG,
                "Notification channel initialized"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to create notification channel",
                e
            )
        }
    }

    /**
     * إعداد Global UncaughtExceptionHandler.
     *
     * مهم جدًا:
     *
     * هذا Handler لا يستبدل Handler النظام بصورة دائمة
     * دون الاحتفاظ بالـ original handler.
     *
     * بعد كتابة التقرير، يتم تمرير Crash إلى handler الأصلي.
     */
    private fun setupCrashHandler() {

        val defaultHandler =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler {
                thread,
                throwable ->

            try {

                when (throwable) {

                    is OutOfMemoryError -> {

                        handleOOMCrash(
                            thread = thread,
                            throwable = throwable
                        )
                    }

                    else -> {

                        handleNormalCrash(
                            thread = thread,
                            throwable = throwable
                        )
                    }
                }

            } catch (handlerError: Throwable) {

                /*
                 * لا نسمح لـ Crash Handler نفسه
                 * بإحداث Crash ثانوي.
                 */
                try {

                    Log.e(
                        TAG,
                        "Crash handler failed",
                        handlerError
                    )

                } catch (_: Throwable) {
                    // Last-resort: ignore.
                }

            } finally {

                /*
                 * StartupDiagnostics مستقل عن MyApplication.
                 *
                 * إذا كان Startup نشطًا، نسجل Crash الخاص به أيضًا.
                 */
                try {

                    val diagnostics =
                        StartupDiagnostics.peek()

                    if (
                        diagnostics != null &&
                        diagnostics.isStartupActive()
                    ) {

                        diagnostics.recordCrash(
                            threadName = thread.name,
                            throwable = throwable
                        )
                    }

                } catch (_: Throwable) {
                    /*
                     * Diagnostics must never cause a second crash.
                     */
                }

                /*
                 * تمرير Crash إلى النظام/Handler السابق.
                 */
                try {

                    defaultHandler?.uncaughtException(
                        thread,
                        throwable
                    )

                } catch (_: Throwable) {

                    /*
                     * لا يوجد شيء آمن يمكن فعله هنا.
                     */
                }
            }
        }
    }

    /**
     * معالجة Crash عادي.
     */
    private fun handleNormalCrash(
        thread: Thread,
        throwable: Throwable
    ) {

        /*
         * لا نحاول تنفيذ عمليات ثقيلة إذا كانت المساحة غير كافية.
         */
        if (!hasEnoughCrashStorage()) {

            Log.w(
                TAG,
                "Insufficient storage for crash report"
            )

            return
        }

        crashLock.lock()

        try {

            val crashDir =
                File(
                    cacheDir,
                    CRASH_DIR
                )

            if (!ensureDirectory(crashDir)) {

                Log.w(
                    TAG,
                    "Unable to create crash directory"
                )

                return
            }

            cleanupOldCrashFiles(
                crashDir
            )

            val crashReport =
                buildCrashReport(
                    thread = thread,
                    throwable = throwable
                )

            val fileName =
                "crash_${System.currentTimeMillis()}.json"

            val logFile =
                File(
                    crashDir,
                    fileName
                )

            FileWriter(logFile).use { writer ->

                writer.write(
                    crashReport
                )

                writer.flush()
            }

            Log.d(
                TAG,
                "Crash report saved: " +
                    logFile.absolutePath
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to save crash report",
                e
            )

        } finally {

            crashLock.unlock()
        }
    }

    /**
     * معالجة OutOfMemoryError.
     *
     * هنا نتجنب:
     *
     * - JSON كبير.
     * - stacktraceToString().
     * - عمليات File.walk().
     * - أي allocation غير ضروري.
     */
    private fun handleOOMCrash(
        thread: Thread,
        throwable: OutOfMemoryError
    ) {

        try {

            val crashDir =
                File(
                    cacheDir,
                    CRASH_DIR
                )

            if (!ensureDirectory(crashDir)) {
                return
            }

            val logFile =
                File(
                    crashDir,
                    "oom_${System.currentTimeMillis()}.txt"
                )

            val message =
                buildString {

                    append("OOM Crash\n")
                    append("time=")
                    append(Date())
                    append('\n')

                    append("thread=")
                    append(thread.name)
                    append('\n')

                    append("message=")
                    append(
                        throwable.message ?: "OutOfMemoryError"
                    )
                    append('\n')
                }

            logFile.writeText(
                message
            )

        } catch (_: Throwable) {
            /*
             * OOM path must be best-effort only.
             */
        }
    }

    /**
     * بناء Crash Report بصيغة JSON.
     *
     * لا نستخدم مكتبة JSON هنا حتى يبقى Crash Handler
     * قليل الاعتماديات قدر الإمكان.
     */
    private fun buildCrashReport(
        thread: Thread,
        throwable: Throwable
    ): String {

        val runtime =
            Runtime.getRuntime()

        val totalMemory =
            runtime.totalMemory()

        val freeMemory =
            runtime.freeMemory()

        val usedMemory =
            totalMemory - freeMemory

        val maxMemory =
            runtime.maxMemory()

        val version =
            getAppVersion()

        return """
            {
                "timestamp": "${escapeJson(formatDate(Date()))}",
                "thread": {
                    "name": "${escapeJson(thread.name)}",
                    "id": ${thread.id}
                },
                "exception": {
                    "type": "${escapeJson(throwable.javaClass.name)}",
                    "message": "${escapeJson(throwable.message ?: "N/A")}",
                    "stacktrace": "${escapeJson(safeStackTrace(throwable))}"
                },
                "device": {
                    "manufacturer": "${escapeJson(Build.MANUFACTURER)}",
                    "brand": "${escapeJson(Build.BRAND)}",
                    "model": "${escapeJson(Build.MODEL)}",
                    "device": "${escapeJson(Build.DEVICE)}",
                    "hardware": "${escapeJson(Build.HARDWARE)}",
                    "android_version": "${escapeJson(Build.VERSION.RELEASE ?: "unknown")}",
                    "sdk_int": ${Build.VERSION.SDK_INT},
                    "fingerprint": "${escapeJson(Build.FINGERPRINT ?: "unknown")}"
                },
                "memory": {
                    "total_mb": ${totalMemory / 1024 / 1024},
                    "free_mb": ${freeMemory / 1024 / 1024},
                    "used_mb": ${usedMemory / 1024 / 1024},
                    "max_mb": ${maxMemory / 1024 / 1024}
                },
                "storage": {
                    "available_mb": ${getAvailableStorageMb()}
                },
                "database": {
                    "size_bytes": ${getDatabaseSize()}
                },
                "app": {
                    "package": "${escapeJson(packageName)}",
                    "version": "${escapeJson(version)}"
                }
            }
        """.trimIndent()
    }

    /**
     * الحصول على StackTrace بأفضل جهد.
     */
    private fun safeStackTrace(
        throwable: Throwable
    ): String {

        return try {

            throwable.stackTraceToString()

        } catch (_: Throwable) {

            "Unable to obtain stacktrace"
        }
    }

    /**
     * التحقق من وجود مساحة كافية لتقرير Crash.
     */
    private fun hasEnoughCrashStorage(): Boolean {

        return try {

            val stat =
                StatFs(
                    cacheDir.absolutePath
                )

            val available =
                stat.availableBytes

            available >
                MIN_FREE_SPACE_MB *
                    1024L *
                    1024L

        } catch (_: Exception) {

            /*
             * فشل قياس المساحة لا يمنع محاولة التسجيل.
             */
            true
        }
    }

    /**
     * الحصول على المساحة المتاحة.
     */
    fun getAvailableStorageMb(): Long {

        return try {

            val stat =
                StatFs(
                    filesDir.absolutePath
                )

            stat.availableBytes /
                1024L /
                1024L

        } catch (_: Exception) {

            -1L
        }
    }

    /**
     * إنشاء مجلد بأمان.
     */
    private fun ensureDirectory(
        directory: File
    ): Boolean {

        return try {

            if (directory.exists()) {

                return directory.isDirectory
            }

            directory.mkdirs() ||
                directory.exists()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to create directory: " +
                    directory.absolutePath,
                e
            )

            false
        }
    }

    /**
     * تنظيف Crash reports القديمة.
     *
     * يتم الاحتفاظ بأحدث MAX_CRASH_FILES فقط.
     */
    private fun cleanupOldCrashFiles(
        crashDir: File
    ) {

        try {

            val files =
                crashDir.listFiles { file ->

                    file.isFile &&
                        (
                            file.name.startsWith(
                                "crash_"
                            ) ||
                            file.name.startsWith(
                                "oom_"
                            )
                        )
                } ?: return

            if (
                files.size <=
                MAX_CRASH_FILES
            ) {
                return
            }

            files.sortBy {
                it.lastModified()
            }

            val deleteCount =
                files.size -
                    MAX_CRASH_FILES

            for (
                file in
                files.take(deleteCount)
            ) {

                try {

                    if (file.delete()) {

                        Log.d(
                            TAG,
                            "Deleted old crash report: " +
                                file.name
                        )
                    }

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "Unable to delete crash report: " +
                            file.name,
                        e
                    )
                }
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to cleanup crash reports",
                e
            )
        }
    }

    /**
     * الحصول على حجم قاعدة البيانات.
     *
     * DatabaseHelper هو المسؤول عن إنشاء وإدارة قاعدة البيانات،
     * وهذه الدالة للقراءة والتشخيص فقط.
     */
    fun getDatabaseSize(): Long {

        return try {

            val databaseFile =
                applicationContext.getDatabasePath(
                    "diesel_station.db"
                )

            if (
                databaseFile.exists() &&
                databaseFile.isFile
            ) {

                databaseFile.length()

            } else {

                0L
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to determine database size",
                e
            )

            0L
        }
    }

    /**
     * الحصول على حجم Cache.
     */
    fun getCacheSize(): Long {

        return try {

            calculateDirectorySize(
                cacheDir
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to calculate cache size",
                e
            )

            0L
        }
    }

    /**
     * حساب حجم مجلد بصورة آمنة.
     */
    private fun calculateDirectorySize(
        directory: File
    ): Long {

        if (
            !directory.exists() ||
            !directory.isDirectory
        ) {
            return 0L
        }

        var total = 0L

        try {

            val files =
                directory.listFiles()
                    ?: return 0L

            for (file in files) {

                try {

                    total += if (
                        file.isDirectory
                    ) {

                        calculateDirectorySize(
                            file
                        )

                    } else {

                        file.length()
                    }

                } catch (_: Exception) {
                    /*
                     * تجاهل ملف واحد تالف أو غير قابل للقراءة.
                     */
                }
            }

        } catch (_: Exception) {
            return total
        }

        return total
    }

    /**
     * تنظيف Cache.
     *
     * ملاحظة:
     *
     * لا يتم استدعاؤها تلقائيًا من onCreate().
     *
     * هذا يمنع حذف ملفات تشخيص Startup أثناء التشغيل.
     */
    fun clearCache(): Boolean {

        return try {

            val directory =
                cacheDir

            if (!directory.exists()) {
                return false
            }

            val success =
                directory.deleteRecursively()

            /*
             * إعادة إنشاء cache directory حتى يبقى
             * Android قادرًا على استخدامه بصورة طبيعية.
             */
            if (!directory.exists()) {
                directory.mkdirs()
            }

            success

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to clear cache",
                e
            )

            false
        }
    }

    /**
     * الحصول على إصدار التطبيق.
     */
    private fun getAppVersion(): String {

        return try {

            @Suppress("DEPRECATION")
            val packageInfo =
                packageManager.getPackageInfo(
                    packageName,
                    0
                )

            packageInfo.versionName
                ?: "unknown"

        } catch (e: Exception) {

            "unknown"
        }
    }

    /**
     * تنسيق التاريخ.
     */
    private fun formatDate(
        date: Date
    ): String {

        return try {

            SimpleDateFormat(
                DATE_FORMAT,
                Locale.US
            ).format(date)

        } catch (_: Exception) {

            date.time.toString()
        }
    }

    /**
     * Escape بسيط وآمن لقيم JSON.
     */
    private fun escapeJson(
        text: String
    ): String {

        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
    }
}