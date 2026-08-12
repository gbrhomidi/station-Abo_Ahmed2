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
 * 1. تهيئة Application العامة.
 * 2. تهيئة StartupDiagnostics بصورة مبكرة وآمنة.
 * 3. تسجيل Crash Handler عالمي.
 * 4. تسجيل Crash الخاص بـ Startup عند الحاجة.
 * 5. إنشاء Notification Channels.
 * 6. توفير EncryptedSharedPreferences.
 * 7. توفير أدوات Hashing آمنة.
 * 8. توفير معلومات التخزين والذاكرة.
 * 9. إدارة ملفات Crash Logs.
 *
 * المبادئ:
 *
 * - لا يعتمد StartupDiagnostics على MyApplication.
 * - MyApplication يمكنه استخدام StartupDiagnostics دون اعتماد عكسي.
 * - فشل التشخيص لا يجب أن يؤدي إلى Crash إضافي.
 * - Crash Handler لا يرمي Exceptions.
 * - OutOfMemoryError يعالج بمسار منخفض التكلفة.
 * - عمليات الكتابة إلى Crash Logs Best-Effort.
 * - جميع العمليات الحساسة محمية من الاستثناءات.
 */
class MyApplication : Application() {

    companion object {

        private const val TAG = "MyApplication"

        /**
         * مجلد Crash Logs.
         */
        private const val CRASH_DIR = "crashes"

        /**
         * الحد الأقصى لعدد ملفات Crash.
         */
        private const val MAX_CRASH_FILES = 10

        /**
         * الحد الأدنى للمساحة الحرة المطلوبة للكتابة.
         */
        private const val MIN_FREE_SPACE_MB = 5L

        /**
         * تنسيق التاريخ.
         */
        private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"

        /**
         * قفل مشترك لعمليات Crash Logging.
         */
        private val crashLock = ReentrantLock()

        /**
         * اسم SharedPreferences المشفرة.
         */
        private const val PREFS_NAME = "secure_prefs"

        /**
         * Singleton للـ Application.
         */
        @Volatile
        private var instance: MyApplication? = null

        /**
         * الحصول على Instance.
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
         * ═══════════════════════════════════════════════════════
         * EncryptedSharedPreferences
         * ═══════════════════════════════════════════════════════
         *
         * يتم إنشاء MasterKey بصورة آمنة.
         *
         * في حال فشل التشفير، نستخدم SharedPreferences عادية
         * كـ fallback حتى لا يؤدي فشل طبقة التشفير إلى إسقاط
         * التطبيق أثناء Startup.
         *
         * ملاحظة:
         *
         * البيانات شديدة الحساسية لا ينبغي الاعتماد على fallback
         * غير المشفر لها.
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
                        "using fallback SharedPreferences",
                    e
                )

                context.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
            }
        }

        /**
         * ═══════════════════════════════════════════════════════
         * SHA-256 Hash
         * ═══════════════════════════════════════════════════════
         *
         * يستخدم لتجزئة قيم مثل أرقام الهواتف عند الحاجة.
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
                 * لا نعيد القيمة الأصلية كحل أمني مثالي،
                 * لكن نحافظ على التوافق مع السلوك السابق.
                 */
                input
            }
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Application Startup
     * ═══════════════════════════════════════════════════════════
     */
    override fun onCreate() {

        super.onCreate()

        /*
         * يجب تسجيل instance أولًا لأن بقية أدوات Application
         * تعتمد عليه.
         */
        instance = this

        /*
         * StartupDiagnostics مستقل عن MyApplication،
         * لذلك يمكن تهيئته مبكرًا جدًا.
         *
         * initialize() مصمم ليكون Best-Effort ولا ينبغي أن
         * يسقط التطبيق.
         */
        initializeStartupDiagnostics()

        /*
         * بعد تهيئة Diagnostics يتم تثبيت Crash Handler.
         *
         * بهذه الطريقة إذا حدث Crash أثناء Startup اللاحق
         * يمكن تسجيله في StartupDiagnostics.
         */
        setupCrashHandler()

        /*
         * إنشاء قنوات الإشعارات.
         */
        createNotificationChannels()

        /*
         * تهيئة EncryptedSharedPreferences مسبقًا.
         */
        initializeEncryptedPreferences()

        Log.d(
            TAG,
            "Application initialized successfully"
        )
    }

    /**
     * تهيئة StartupDiagnostics.
     *
     * هذه العملية مستقلة تمامًا عن Pipeline نفسه.
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

            /*
             * لا يجب أن يفشل Application بسبب Diagnostics.
             */
            Log.e(
                TAG,
                "StartupDiagnostics initialization failed",
                e
            )
        }
    }

    /**
     * تهيئة EncryptedSharedPreferences.
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
             * getEncryptedPreferences لديها fallback،
             * ولكن نحمي Application أيضًا من أي Exception
             * غير متوقع.
             */
            Log.e(
                TAG,
                "Failed to initialize preferences",
                e
            )
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Notification Channels
     * ═══════════════════════════════════════════════════════════
     */
    private fun createNotificationChannels() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        try {

            val channel =
                NotificationChannel(
                    "station_sms_channel",
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
                ) as NotificationManager

            manager.createNotificationChannel(
                channel
            )

            Log.d(
                TAG,
                "Notification channel created"
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
     * ═══════════════════════════════════════════════════════════
     * Global Crash Handler
     * ═══════════════════════════════════════════════════════════
     *
     * يتم الاحتفاظ بالـ defaultHandler حتى يستمر Android في
     * معالجة Crash بالطريقة الطبيعية بعد تسجيل التقرير.
     */
    private fun setupCrashHandler() {

        try {

            val defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler {
                    thread,
                    throwable ->

                try {

                    when (throwable) {

                        /*
                         * OutOfMemoryError لا ينبغي تنفيذ عمليات
                         * ثقيلة أثناء معالجته.
                         */
                        is OutOfMemoryError -> {

                            handleOOMError(
                                thread = thread,
                                throwable = throwable,
                                defaultHandler = defaultHandler
                            )
                        }

                        else -> {

                            handleNormalCrash(
                                thread = thread,
                                throwable = throwable,
                                defaultHandler = defaultHandler
                            )
                        }
                    }

                } catch (handlerError: Throwable) {

                    /*
                     * Crash Handler نفسه يجب ألا يؤدي إلى Crash
                     * إضافي أو يمنع النظام من استكمال معالجة
                     * الاستثناء الأصلي.
                     */
                    try {

                        Log.e(
                            TAG,
                            "Crash handler failed",
                            handlerError
                        )

                    } catch (_: Throwable) {
                        // لا شيء
                    }

                    try {

                        defaultHandler?.uncaughtException(
                            thread,
                            throwable
                        )

                    } catch (_: Throwable) {
                        // لا شيء
                    }
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to install global crash handler",
                e
            )
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Normal Crash
     * ═══════════════════════════════════════════════════════════
     */
    private fun handleNormalCrash(
        thread: Thread,
        throwable: Throwable,
        defaultHandler: Thread.UncaughtExceptionHandler?
    ) {

        /*
         * أولًا نحاول تسجيل Crash في StartupDiagnostics
         * إذا كان Startup نشطًا.
         *
         * لا نستخدم getInstance() هنا حتى لا نرمي Exception
         * إذا حدث شيء غير متوقع.
         */
        recordStartupCrashSafely(
            thread = thread,
            throwable = throwable
        )

        crashLock.lock()

        try {

            if (!hasEnoughSpace()) {

                Log.w(
                    TAG,
                    "Insufficient space for crash log"
                )

                return

            }

            val crashDir =
                File(
                    cacheDir,
                    CRASH_DIR
                )

            if (!crashDir.exists()) {

                if (!crashDir.mkdirs() &&
                    !crashDir.exists()
                ) {

                    Log.w(
                        TAG,
                        "Unable to create crash directory"
                    )

                    return
                }
            }

            cleanupOldCrashes(
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
                "Crash log saved: " +
                    logFile.absolutePath
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to write crash log",
                e
            )

        } finally {

            try {

                crashLock.unlock()

            } catch (_: Exception) {
                // لا شيء
            }
        }

        /*
         * يجب دائمًا تمرير Crash الأصلي إلى النظام.
         */
        try {

            defaultHandler?.uncaughtException(
                thread,
                throwable
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Default crash handler failed",
                e
            )
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Startup Crash Recording
     * ═══════════════════════════════════════════════════════════
     *
     * StartupDiagnostics مستقل عن MyApplication.
     *
     * نسجل Crash فقط إذا كان Startup فعليًا نشطًا.
     */
    private fun recordStartupCrashSafely(
        thread: Thread,
        throwable: Throwable
    ) {

        try {

            val diagnostics =
                StartupDiagnostics.peek()
                    ?: return

            if (!diagnostics.isStartupActive()) {
                return
            }

            diagnostics.recordCrash(
                threadName = thread.name,
                throwable = throwable
            )

        } catch (e: Exception) {

            /*
             * فشل Diagnostics لا يجوز أن يؤثر على Crash Handler.
             */
            Log.e(
                TAG,
                "Failed to record startup crash",
                e
            )
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Out Of Memory
     * ═══════════════════════════════════════════════════════════
     *
     * هذا المسار يجب أن يبقى بسيطًا جدًا.
     */
    private fun handleOOMError(
        thread: Thread,
        throwable: OutOfMemoryError,
        defaultHandler: Thread.UncaughtExceptionHandler?
    ) {

        try {

            /*
             * لا نستدعي StartupDiagnostics هنا عمدًا.
             *
             * recordCrash() يبني تقريرًا كبيرًا نسبيًا ويقرأ
             * ملفات أخرى، وهذا غير مناسب لمسار OOM.
             */

            val crashDir =
                File(
                    cacheDir,
                    CRASH_DIR
                )

            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }

            val simpleLog =
                buildString {

                    append("OOM Crash\n")
                    append(
                        "timestamp=" +
                            formatDate(
                                Date()
                            ) +
                            "\n"
                    )
                    append(
                        "thread=" +
                            safeText(
                                thread.name
                            ) +
                            "\n"
                    )
                    append(
                        "message=" +
                            safeText(
                                throwable.message
                                    ?: "N/A"
                            ) +
                            "\n"
                    )
                }

            val logFile =
                File(
                    crashDir,
                    "oom_${System.currentTimeMillis()}.txt"
                )

            /*
             * writeText قد يحتاج ذاكرة إضافية،
             * لكنه أقل تكلفة من بناء تقرير كامل.
             */
            try {

                logFile.writeText(
                    simpleLog
                )

            } catch (_: Throwable) {
                // تجاهل أي فشل أثناء OOM
            }

        } catch (_: Throwable) {

            /*
             * لا نفعل شيئًا.
             */
        }

        try {

            defaultHandler?.uncaughtException(
                thread,
                throwable
            )

        } catch (_: Throwable) {
            // لا شيء
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Crash Report
     * ═══════════════════════════════════════════════════════════
     */
    private fun buildCrashReport(
        thread: Thread,
        throwable: Throwable
    ): String {

        return try {

            val runtime =
                Runtime.getRuntime()

            val totalMemory =
                runtime.totalMemory()

            val freeMemory =
                runtime.freeMemory()

            val usedMemory =
                totalMemory - freeMemory

            buildString {

                appendLine("{")

                appendLine(
                    "  \"timestamp\": \"" +
                        escapeJson(
                            formatDate(Date())
                        ) +
                        "\","
                )

                appendLine(
                    "  \"thread\": {"
                )

                appendLine(
                    "    \"name\": \"" +
                        escapeJson(
                            thread.name
                        ) +
                        "\","
                )

                appendLine(
                    "    \"id\": " +
                        thread.id
                )

                appendLine(
                    "  },"
                )

                appendLine(
                    "  \"exception\": {"
                )

                appendLine(
                    "    \"type\": \"" +
                        escapeJson(
                            throwable
                                .javaClass
                                .name
                        ) +
                        "\","
                )

                appendLine(
                    "    \"message\": \"" +
                        escapeJson(
                            throwable.message
                                ?: "N/A"
                        ) +
                        "\","
                )

                appendLine(
                    "    \"stacktrace\": \"" +
                        escapeJson(
                            throwable
                                .stackTraceToString()
                        ) +
                        "\""
                )

                appendLine(
                    "  },"
                )

                appendLine(
                    "  \"device\": {"
                )

                appendLine(
                    "    \"manufacturer\": \"" +
                        escapeJson(
                            Build.MANUFACTURER
                        ) +
                        "\","
                )

                appendLine(
                    "    \"model\": \"" +
                        escapeJson(
                            Build.MODEL
                        ) +
                        "\","
                )

                appendLine(
                    "    \"brand\": \"" +
                        escapeJson(
                            Build.BRAND
                        ) +
                        "\","
                )

                appendLine(
                    "    \"device\": \"" +
                        escapeJson(
                            Build.DEVICE
                        ) +
                        "\","
                )

                appendLine(
                    "    \"hardware\": \"" +
                        escapeJson(
                            Build.HARDWARE
                        ) +
                        "\","
                )

                appendLine(
                    "    \"android_version\": \"" +
                        escapeJson(
                            Build.VERSION.RELEASE
                                ?: "unknown"
                        ) +
                        "\","
                )

                appendLine(
                    "    \"sdk_int\": " +
                        Build.VERSION.SDK_INT +
                        ","
                )

                appendLine(
                    "    \"fingerprint\": \"" +
                        escapeJson(
                            Build.FINGERPRINT
                        ) +
                        "\""
                )

                appendLine(
                    "  },"
                )

                appendLine(
                    "  \"memory\": {"
                )

                appendLine(
                    "    \"total_mb\": " +
                        (
                            totalMemory /
                                1024 /
                                1024
                            ) +
                        ","
                )

                appendLine(
                    "    \"free_mb\": " +
                        (
                            freeMemory /
                                1024 /
                                1024
                            ) +
                        ","
                )

                appendLine(
                    "    \"used_mb\": " +
                        (
                            usedMemory /
                                1024 /
                                1024
                            ) +
                        ","
                )

                appendLine(
                    "    \"max_mb\": " +
                        (
                            runtime.maxMemory() /
                                1024 /
                                1024
                            )
                )

                appendLine(
                    "  },"
                )

                appendLine(
                    "  \"app\": {"
                )

                appendLine(
                    "    \"package\": \"" +
                        escapeJson(
                            packageName
                        ) +
                        "\","
                )

                appendLine(
                    "    \"version\": \"" +
                        escapeJson(
                            getAppVersion()
                        ) +
                        "\""
                )

                appendLine(
                    "  }"
                )

                appendLine("}")

            }

        } catch (e: Exception) {

            /*
             * يجب أن يكون Crash Reporting نفسه آمنًا.
             */
            "{\"error\":\"Unable to build crash report\"}"
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Storage Check
     * ═══════════════════════════════════════════════════════════
     */
    private fun hasEnoughSpace(): Boolean {

        return try {

            val stat =
                StatFs(
                    cacheDir.path
                )

            val availableBytes =
                stat.availableBytes

            availableBytes >
                MIN_FREE_SPACE_MB *
                1024L *
                1024L

        } catch (e: Exception) {

            /*
             * إذا تعذر معرفة المساحة،
             * لا نمنع Crash Reporting.
             */
            true
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Crash Cleanup
     * ═══════════════════════════════════════════════════════════
     */
    private fun cleanupOldCrashes(
        crashDir: File
    ) {

        try {

            val files =
                crashDir.listFiles { file ->

                    file.name.startsWith(
                        "crash_"
                    ) ||
                        file.name.startsWith(
                            "oom_"
                        )
                }
                    ?: return

            if (files.size <= MAX_CRASH_FILES) {
                return
            }

            files.sortBy {
                it.lastModified()
            }

            val numberToDelete =
                files.size -
                    MAX_CRASH_FILES

            files
                .take(numberToDelete)
                .forEach { file ->

                    try {

                        if (file.delete()) {

                            Log.d(
                                TAG,
                                "Deleted old crash log: " +
                                    file.name
                            )
                        }

                    } catch (e: Exception) {

                        Log.w(
                            TAG,
                            "Failed to delete old crash log: " +
                                file.name,
                            e
                        )
                    }
                }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to cleanup old crashes",
                e
            )
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Date Formatting
     * ═══════════════════════════════════════════════════════════
     */
    private fun formatDate(
        date: Date
    ): String {

        return try {

            SimpleDateFormat(
                DATE_FORMAT,
                Locale.US
            ).format(date)

        } catch (e: Exception) {

            date.time.toString()
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * App Version
     * ═══════════════════════════════════════════════════════════
     */
    private fun getAppVersion(): String {

        return try {

            @Suppress("DEPRECATION")
            packageManager
                .getPackageInfo(
                    packageName,
                    0
                )
                .versionName
                ?: "unknown"

        } catch (e: Exception) {

            "unknown"
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * JSON Escaping
     * ═══════════════════════════════════════════════════════════
     *
     * تتم معالجة:
     *
     * - backslash
     * - quotes
     * - newline
     * - carriage return
     * - tab
     * - backspace
     * - form feed
     * - control characters
     */
    private fun escapeJson(
        text: String
    ): String {

        return buildString(
            text.length + 16
        ) {

            text.forEach { char ->

                when (char) {

                    '\\' ->
                        append("\\\\")

                    '"' ->
                        append("\\\"")

                    '\n' ->
                        append("\\n")

                    '\r' ->
                        append("\\r")

                    '\t' ->
                        append("\\t")

                    '\b' ->
                        append("\\b")

                    '\u000C' ->
                        append("\\f")

                    else -> {

                        if (char.code < 0x20) {

                            append(
                                "\\u%04x"
                                    .format(
                                        char.code
                                    )
                            )

                        } else {

                            append(char)
                        }
                    }
                }
            }
        }
    }

    /**
     * تحويل النص إلى قيمة آمنة للـ logging.
     */
    private fun safeText(
        value: String
    ): String {

        return try {

            value
                .replace(
                    "\r",
                    " "
                )
                .replace(
                    "\n",
                    " "
                )

        } catch (_: Exception) {

            "unknown"
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Database Size
     * ═══════════════════════════════════════════════════════════
     */
    fun getDatabaseSize(): Long {

        return try {

            val dbFile =
                File(
                    applicationContext
                        .getDatabasePath(
                            "diesel_station.db"
                        )
                        .path
                )

            if (dbFile.exists()) {
                dbFile.length()
            } else {
                0L
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to get database size",
                e
            )

            0L
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Cache Size
     * ═══════════════════════════════════════════════════════════
     */
    fun getCacheSize(): Long {

        return try {

            val currentCacheDir =
                cacheDir

            if (!currentCacheDir.exists()) {
                return 0L
            }

            currentCacheDir
                .walkTopDown()
                .filter {
                    it.isFile
                }
                .sumOf {
                    it.length()
                }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to calculate cache size",
                e
            )

            0L
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * Clear Cache
     * ═══════════════════════════════════════════════════════════
     *
     * ملاحظة:
     *
     * StartupDiagnostics يستخدم:
     *
     * filesDir/startup-diagnostics
     *
     * بينما هذه الدالة تحذف:
     *
     * cacheDir
     *
     * لذلك لا تقوم هذه الدالة بحذف StartupDiagnostics.
     */
    fun clearCache(): Boolean {

        return try {

            val currentCacheDir =
                cacheDir

            if (!currentCacheDir.exists()) {
                return false
            }

            val deleted =
                currentCacheDir.deleteRecursively()

            if (!deleted) {

                Log.w(
                    TAG,
                    "Some cache files could not be deleted"
                )
            }

            /*
             * إعادة إنشاء cacheDir بعد الحذف.
             */
            if (!currentCacheDir.exists()) {
                currentCacheDir.mkdirs()
            }

            true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to clear cache",
                e
            )

            false
        }
    }
}