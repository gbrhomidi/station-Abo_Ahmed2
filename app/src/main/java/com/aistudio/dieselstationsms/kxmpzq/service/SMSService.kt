package com.aistudio.dieselstationsms.kxmpzq.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.MainActivity
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsConversationManager
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsCustomerResolver
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsIntentDetector
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsMetrics
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsProcessor
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsReplyManager
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsSecurity
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsSecurityOTP
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import com.aistudio.dieselstationsms.kxmpzq.utils.SystemEventLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.aistudio.dieselstationsms.kxmpzq.BackupWorker
import com.aistudio.dieselstationsms.kxmpzq.worker.MaintenanceWorker

/**
 * ═══════════════════════════════════════════════════════════════
 * SMSService – خدمة الخلفية لإدارة دورة حياة نظام SMS التفاعلي
 * الإصدار 7.1.0 – Complete Orchestrator Architecture (Refactored)
 * ═══════════════════════════════════════════════════════════════
 *
 * تم إعادة بناء هذه الخدمة لتصبح منسقًا (Orchestrator) كاملًا:
 * 1. إدارة دورة حياة الخدمة (Service Lifecycle)
 * 2. تشغيل الخدمة في المقدمة (Foreground Service)
 * 3. تهيئة وإدارة جميع الوحدات المتخصصة
 * 4. مراقبة صحة النظام (Health Monitoring)
 * 5. تحميل وإدارة الإعدادات
 * 6. تنظيف قواعد البيانات الدوري
 * 7. مزامنة البيانات
 * 8. إدارة الأداء والإحصائيات
 * 9. إدارة السجلات والأخطاء
 * 10. جدولة المهام
 * 11. واجهة التشخيص العامة
 *
 * تم إزالة جميع المسؤوليات المتخصصة إلى:
 * - SmsProcessor.kt            ← معالجة الرسائل
 * - SmsSecurity.kt             ← الحماية والتحقق
 * - SmsSecurityOTP.kt          ← OTP
 * - SmsReplyManager.kt         ← الردود
 * - SmsConversationManager.kt  ← إدارة المحادثات
 * - SmsCustomerResolver.kt     ← العملاء
 * - SmsIntentDetector.kt       ← تحليل النية
 * - SmsMetrics.kt              ← المقاييس
 *
 * ═══════════════════════════════════════════════════════════════
 * @version 7.1.0 - Refactored with SystemEventLogger & PhoneUtils
 * @since 2026-07-24
 * ═══════════════════════════════════════════════════════════════
 */
class SMSService : Service() {

    companion object {
        private const val TAG = "SMSService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "station_sms_channel"
        private const val BACKUP_WORK_NAME = "auto_backup_work"
        private const val MAINTENANCE_WORK_NAME = "maintenance_work"
        private const val HEARTBEAT_INTERVAL_MS = 300_000L // 5 دقائق
        private const val HEALTH_CHECK_INTERVAL_MS = 600_000L // 10 دقائق
        private const val PERFORMANCE_INTERVAL_MS = 180_000L // 3 دقائق
        private const val WAKE_LOCK_TAG = "SMSService::WakeLock"
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val RESTART_BACKOFF_MS = 30_000L
        private const val CLEANUP_INTERVAL_MS = 3_600_000L // 1 ساعة
        private const val METRICS_FLUSH_INTERVAL_MS = 600_000L // 10 دقائق
        private const val SECURITY_CHECK_INTERVAL_MS = 1_800_000L // 30 دقيقة

        private val DATETIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        // ==================== إشعارات الأحداث ====================
        const val ACTION_SERVICE_STATUS_CHANGED = "com.aistudio.dieselstationsms.SERVICE_STATUS_CHANGED"
        const val ACTION_BACKUP_COMPLETED = "com.aistudio.dieselstationsms.BACKUP_COMPLETED"
        const val ACTION_HEALTH_STATUS = "com.aistudio.dieselstationsms.HEALTH_STATUS"
        const val ACTION_ERROR_REPORT = "com.aistudio.dieselstationsms.ERROR_REPORT"
        const val ACTION_PERFORMANCE_REPORT = "com.aistudio.dieselstationsms.PERFORMANCE_REPORT"

        // ==================== حالات الخدمة ====================
        const val STATUS_SERVICE_STARTED = "SERVICE_STARTED"
        const val STATUS_SERVICE_STOPPED = "SERVICE_STOPPED"
        const val STATUS_SERVICE_ERROR = "SERVICE_ERROR"
        const val STATUS_SERVICE_RESTARTED = "SERVICE_RESTARTED"
        const val STATUS_SERVICE_PAUSED = "SERVICE_PAUSED"
        const val STATUS_SERVICE_RESUMED = "SERVICE_RESUMED"
        const val STATUS_HEARTBEAT = "HEARTBEAT"
        const val STATUS_HEALTHY = "HEALTHY"
        const val STATUS_UNHEALTHY = "UNHEALTHY"
        const val STATUS_MAINTENANCE = "MAINTENANCE"

        // ==================== أنماط التشغيل ====================
        const val MODE_OFFLINE = "offline"
        const val MODE_ONLINE = "online"
        const val MODE_HYBRID = "hybrid"

        // ==================== أذونات ====================
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.WAKE_LOCK
        )

        // ==================== أسماء الجداول ====================
        const val TABLE_PROCESSED_HASHES = "sms_processed_hashes"
        const val TABLE_RATE_LIMITS = "sms_rate_limits"
        const val TABLE_CONVERSATION_CONTEXT = "sms_conversation_context"
        const val TABLE_CUSTOMER_PREFERENCES = "sms_customer_preferences"
        const val TABLE_INTERACTION_HISTORY = "sms_interaction_history"
        const val TABLE_RECURRING_ORDERS = "sms_recurring_orders"
        const val TABLE_METRICS = "sms_metrics"
        const val TABLE_OTP_VERIFICATIONS = "sms_otp_verifications"
        const val TABLE_SETTINGS = "sms_settings"

        // ==================== إعدادات التنظيف ====================
        const val HASHES_RETENTION_DAYS = 7
        const val RATE_LIMITS_RETENTION_DAYS = 1
        const val CONVERSATION_CONTEXT_RETENTION_DAYS = 30
        const val INTERACTION_HISTORY_RETENTION_DAYS = 90
        const val OTP_VERIFICATIONS_RETENTION_DAYS = 1
        const val METRICS_RETENTION_DAYS = 30
        const val RECURRING_ORDERS_RETENTION_DAYS = 365
        const val CUSTOMER_PREFERENCES_RETENTION_DAYS = 730

        @Volatile
        private var serviceInstance: SMSService? = null

        fun getInstance(): SMSService? = serviceInstance
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Coroutine Scopes ═══
    // ═══════════════════════════════════════════════════════════════
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val performanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    // ═══ حالة الخدمة ═══
    // ═══════════════════════════════════════════════════════════════
    private val isDestroyed = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val restartAttempts = AtomicInteger(0)
    private val lastRestartTime = AtomicLong(0L)
    private val startTime = AtomicLong(0L)
    private val currentMode = AtomicReference(MODE_OFFLINE)

    // ═══════════════════════════════════════════════════════════════
    // ═══ Jobs ═══
    // ═══════════════════════════════════════════════════════════════
    private var heartbeatJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var performanceMonitorJob: Job? = null
    private var cleanupJob: Job? = null
    private var metricsFlushJob: Job? = null
    private var securityCheckJob: Job? = null
    private var maintenanceJob: Job? = null

    // ═══════════════════════════════════════════════════════════════
    // ═══ Wake Lock ═══
    // ═══════════════════════════════════════════════════════════════
    private var wakeLock: PowerManager.WakeLock? = null

    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // ═══ المكونات المعمارية (Modules) ═══
    // ═══════════════════════════════════════════════════════════════
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var smsMetrics: SmsMetrics
    private lateinit var smsSecurity: SmsSecurity
    private lateinit var smsSecurityOTP: SmsSecurityOTP
    private lateinit var smsProcessor: SmsProcessor
    private lateinit var smsConversationManager: SmsConversationManager
    private lateinit var smsCustomerResolver: SmsCustomerResolver
    private lateinit var smsIntentDetector: SmsIntentDetector
    private lateinit var smsReplyManager: SmsReplyManager

    // ═══════════════════════════════════════════════════════════════
    // ═══ الإعدادات ═══
    // ═══════════════════════════════════════════════════════════════
    private val settings = mutableMapOf<String, String>()
    private val securitySettings = mutableMapOf<String, String>()
    private val conversationSettings = mutableMapOf<String, String>()
    private val otpSettings = mutableMapOf<String, String>()
    private val metricsSettings = mutableMapOf<String, String>()
    private val rateLimitSettings = mutableMapOf<String, String>()
    private val cleanupSettings = mutableMapOf<String, String>()

    // ═══════════════════════════════════════════════════════════════
    // ═══ إحصائيات الأداء ═══
    // ═══════════════════════════════════════════════════════════════
    private val performanceStats = mutableMapOf<String, Any>()
    private val moduleLoadTimes = mutableMapOf<String, Long>()
    private val errorCount = AtomicInteger(0)
    private val processedMessageCount = AtomicInteger(0)
    private val lastHealthCheckResult = AtomicReference("")

    // ═══════════════════════════════════════════════════════════════
    // ═══ دورة حياة الخدمة (Service Lifecycle) ═══
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        Log.i(TAG, "═══════════════════════════════════════════════")
        Log.i(TAG, "SMSService onCreate - Complete Orchestrator v7.0.0")
        Log.i(TAG, "═══════════════════════════════════════════════")

        isDestroyed.set(false)
        isInitialized.set(false)
        isRunning.set(false)
        isPaused.set(false)
        startTime.set(System.currentTimeMillis())
        restartAttempts.set(0)
        errorCount.set(0)
        processedMessageCount.set(0)

        try {
            // 1. تهيئة Coroutine Scope
            initializeCoroutineScope()

            // 2. تهيئة الخدمة
            initializeService()

            // 3. تهيئة قاعدة البيانات
            initializeDatabase()

            // 4. تهيئة الإشعارات
            initializeNotification()

            // 5. تحميل الإعدادات
            loadSettings()

            // 6. تهيئة الوحدات
            initializeSmsModules()

            // 7. تسجيل مستقبل الرسائل

            // 8. تشغيل محرك SMS
            startSmsEngine()

            // 9. بدء مراقبة الصحة
            startHealthMonitor()

            // 10. بدء مراقبة الأداء
            startPerformanceMonitor()

            // 11. جدولة المهام
            scheduleMaintenance()
            scheduleHealthChecks()
            scheduleDatabaseCleanup()
            scheduleMetricsFlush()
            scheduleSecurityChecks()

            // 12. تسجيل بدء الخدمة
            logServiceStarted()
            SystemEventLogger.recordService(this, STATUS_SERVICE_STARTED, "Service v7.1.0 started")

            isInitialized.set(true)
            isRunning.set(true)
            Log.i(TAG, "Service initialization completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Fatal Error in service initialization: ${e.message}", e)
            handleFatalError(e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "SMSService onStartCommand - START_STICKY")

        try {
            if (!isInitialized.get()) {
                Log.w(TAG, "Service not initialized yet, reinitializing...")
                initializeService()
            }

            if (isPaused.get()) {
                resumeSmsEngine()
            }

            if (!isDestroyed.get()) {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
            }

            // معالجة أي أمر مرسل إلى الخدمة
            handleServiceCommand(intent)

            // تسجيل معلومات بدء التشغيل بدون التأثير على السلوك
            intent?.getStringExtra("startup_reason")?.let {
                Log.i(TAG, "Startup reason: $it")
            }

            intent?.getStringExtra("restart_reason")?.let {
                Log.i(TAG, "Restart reason: $it")
            }

            sendServiceStatusBroadcast()

        } catch (e: Exception) {
            Log.e(TAG, "Error handling service start command: ${e.message}", e)
        }

        return START_STICKY
    }

    /**
     * معالجة أوامر الخدمة القادمة من BroadcastReceivers
     */
    private fun handleServiceCommand(intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return

        try {
            when (action) {

                "reschedule_tasks" -> {
                    val reason = intent.getStringExtra("reason") ?: "unknown"

                    Log.i(TAG, "Reschedule requested. reason=$reason")

                    // إعادة جدولة المهام الموجودة فعلياً
                    scheduleDatabaseCleanup()
                    scheduleMaintenance()
                    scheduleHealthChecks()
                    scheduleMetricsFlush()
                    scheduleSecurityChecks()

                    Log.i(TAG, "Scheduled tasks refreshed successfully")
                }

                "execute_scheduled_task" -> {
                    val taskType = intent.getStringExtra("task_type") ?: "default"

                    Log.i(TAG, "Scheduled task received: $taskType")

                    when (taskType) {
                        "cleanup", "database_cleanup" -> {
                            runCleanupNow()
                        }
                        else -> {
                            Log.w(TAG, "Unknown scheduled task type: $taskType")
                        }
                    }
                }

                "app_updated" -> {
                    Log.i(TAG, "Application update command received")

                    reloadSettings()
                    restartModules()

                    // إعادة إنشاء الجدولة بعد تحديث التطبيق
                    scheduleMaintenance()
                    scheduleDatabaseCleanup()
                    scheduleMetricsFlush()
                    scheduleSecurityChecks()
                }

                else -> {
                    Log.w(TAG, "Unknown service command: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle service command '$action': ${e.message}", e)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "SMSService onDestroy - Cleaning up resources")
        isDestroyed.set(true)
        isInitialized.set(false)
        isRunning.set(false)

        try {
            // 1. إيقاف المحرك
            stopSmsEngine()

            // 2. إلغاء تسجيل المستقبل

            // 3. إيقاف المراقبة
            stopHealthMonitor()
            stopPerformanceMonitor()

            // 4. إلغاء Coroutines
            heartbeatJob?.cancel()
            healthMonitorJob?.cancel()
            performanceMonitorJob?.cancel()
            cleanupJob?.cancel()
            metricsFlushJob?.cancel()
            securityCheckJob?.cancel()
            maintenanceJob?.cancel()

            // 5. إلغاء Scopes
            serviceScope.cancel()
            healthScope.cancel()
            performanceScope.cancel()
            cleanupScope.cancel()

            // 6. تحرير Wake Lock
            releaseWakeLock()

            // 7. تسجيل توقف الخدمة
            logServiceStopped()
            SystemEventLogger.recordService(this, STATUS_SERVICE_STOPPED, "Service stopped")

            // 8. إغلاق قاعدة البيانات
            if (::dbHelper.isInitialized) {
                dbHelper.close()
            }

            // 9. إلغاء جدولة العمل
            WorkManager.getInstance(this).cancelUniqueWork(BACKUP_WORK_NAME)
            WorkManager.getInstance(this).cancelUniqueWork(MAINTENANCE_WORK_NAME)

            Log.i(TAG, "Service destroyed and resources cleaned successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction: ${e.message}", e)
        }

        serviceInstance = null
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تهيئة الخدمة (Service Initialization) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * تهيئة Coroutine Scope للخدمة
     */
    private fun initializeCoroutineScope() {
        Log.d(TAG, "Coroutine scopes initialized")
    }

    /**
     * تهيئة الخدمة الأساسية
     */
    private fun initializeService() {
        try {
            Log.d(TAG, "Initializing service...")
            acquireWakeLock()
            Log.d(TAG, "Service initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize service: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة الإشعارات
     */
    private fun initializeNotification() {
        try {
            createNotificationChannel()
            Log.d(TAG, "Notification system initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize notifications: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة قاعدة البيانات
     */
    private fun initializeDatabase() {
        try {
            dbHelper = DatabaseHelper.getInstance(applicationContext)
            validateDatabase()
            Log.d(TAG, "DatabaseHelper initialized successfully (Singleton)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DatabaseHelper: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة جميع وحدات SMS
     */
    private fun initializeSmsModules() {
        try {
            val startTime = System.currentTimeMillis()

            initializeSecurity()
            initializeMetrics()
            initializeSmsProcessor()
            initializeConversationManager()
            initializeCustomerResolver()
            initializeIntentDetector()
            initializeReplyManager()
            initializeOtpModule()

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "All SMS modules initialized in ${duration}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SMS modules: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة وحدة الأمان
     */
    private fun initializeSecurity() {
        try {
            val start = System.currentTimeMillis()
            smsSecurity = SmsSecurity(applicationContext, dbHelper)
            moduleLoadTimes["security"] = System.currentTimeMillis() - start
            Log.d(TAG, "SmsSecurity initialized (${moduleLoadTimes["security"]}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmsSecurity: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة وحدة المقاييس
     */
    private fun initializeMetrics() {
        try {
            val start = System.currentTimeMillis()
            smsMetrics = SmsMetrics(dbHelper)
            moduleLoadTimes["metrics"] = System.currentTimeMillis() - start
            Log.d(TAG, "SmsMetrics initialized (${moduleLoadTimes["metrics"]}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmsMetrics: ${e.message}", e)
            throw e
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // ═══ إدارة الوحدات الجديدة (Module Management) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * تهيئة معالج الرسائل
     */
    private fun initializeSmsProcessor() {
        try {
            val start = System.currentTimeMillis()
            smsProcessor = SmsProcessor(applicationContext, dbHelper)
            moduleLoadTimes["processor"] = System.currentTimeMillis() - start
            Log.d(TAG, "SmsProcessor initialized (${moduleLoadTimes["processor"]}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmsProcessor: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة مدير المحادثات
     */
    private fun initializeConversationManager() {
        try {
            val start = System.currentTimeMillis()
            smsConversationManager = SmsConversationManager(dbHelper)
            moduleLoadTimes["conversation"] = System.currentTimeMillis() - start
            Log.d(TAG, "SmsConversationManager initialized (${moduleLoadTimes["conversation"]}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmsConversationManager: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة محلل العملاء
     */
    private fun initializeCustomerResolver() {
        try {
            val start = System.currentTimeMillis()
            smsCustomerResolver = SmsCustomerResolver(dbHelper)
            moduleLoadTimes["customer"] = System.currentTimeMillis() - start
            Log.d(TAG, "SmsCustomerResolver initialized (${moduleLoadTimes["customer"]}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmsCustomerResolver: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة كاشف النية
     */
    private fun initializeIntentDetector() {
        try {
            val start = System.currentTimeMillis()
            smsIntentDetector = SmsIntentDetector()
            moduleLoadTimes["intent"] = System.currentTimeMillis() - start
            Log.d(TAG, "SmsIntentDetector initialized (${moduleLoadTimes["intent"]}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmsIntentDetector: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة مدير الردود
     */
    private fun initializeReplyManager() {
        try {
            val start = System.currentTimeMillis()
            smsReplyManager = SmsReplyManager(applicationContext, dbHelper)
            moduleLoadTimes["reply"] = System.currentTimeMillis() - start
            Log.d(TAG, "SmsReplyManager initialized (${moduleLoadTimes["reply"]}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmsReplyManager: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة وحدة الأمان (الوحدة الكاملة)
     */
    private fun initializeSecurityModule() {
        initializeSecurity()
        Log.d(TAG, "Security module fully initialized")
    }

    /**
     * تهيئة وحدة OTP
     */
    private fun initializeOtpModule() {
        try {
            val start = System.currentTimeMillis()
            smsSecurityOTP = SmsSecurityOTP(dbHelper)
            moduleLoadTimes["otp"] = System.currentTimeMillis() - start
            Log.d(TAG, "SmsSecurityOTP initialized (${moduleLoadTimes["otp"]}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmsSecurityOTP: ${e.message}", e)
            throw e
        }
    }

    /**
     * تهيئة وحدة المقاييس (الوحدة الكاملة)
     */
    private fun initializeMetricsModule() {
        initializeMetrics()
        Log.d(TAG, "Metrics module fully initialized")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تشغيل وإيقاف النظام (Engine Control) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * تشغيل محرك SMS
     */
    private fun startSmsEngine() {
        try {
            Log.i(TAG, "Starting SMS engine...")
            isRunning.set(true)
            isPaused.set(false)
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
            logServiceStarted()
            Log.i(TAG, "SMS engine started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SMS engine: ${e.message}", e)
            throw e
        }
    }

    /**
     * إيقاف محرك SMS
     */
    private fun stopSmsEngine() {
        try {
            Log.i(TAG, "Stopping SMS engine...")
            isRunning.set(false)
            isPaused.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.i(TAG, "SMS engine stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SMS engine: ${e.message}", e)
        }
    }

    /**
     * إعادة تشغيل محرك SMS
     */
    private fun restartSmsEngine() {
        try {
            Log.i(TAG, "Restarting SMS engine...")
            stopSmsEngine()
            Thread.sleep(1000)
            startSmsEngine()
            logServiceRestarted()
            Log.i(TAG, "SMS engine restarted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart SMS engine: ${e.message}", e)
            handleFatalError(e)
        }
    }

    /**
     * إيقاف مؤقت لمحرك SMS
     */
    private fun pauseSmsEngine() {
        try {
            Log.i(TAG, "Pausing SMS engine...")
            isPaused.set(true)
            isRunning.set(false)
            SystemEventLogger.recordService(this, STATUS_SERVICE_PAUSED, "Engine paused")
            updateNotification("نظام SMS متوقف مؤقتًا")
            Log.i(TAG, "SMS engine paused")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing SMS engine: ${e.message}", e)
        }
    }

    /**
     * استئناف محرك SMS
     */
    private fun resumeSmsEngine() {
        try {
            Log.i(TAG, "Resuming SMS engine...")
            isPaused.set(false)
            isRunning.set(true)
            SystemEventLogger.recordService(this, STATUS_SERVICE_RESUMED, "Engine resumed")
            updateNotification("نظام SMS يعمل")
            Log.i(TAG, "SMS engine resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming SMS engine: ${e.message}", e)
        }
    }

    /**
     * إيقاف آمن للخدمة
     */
    private fun shutdownGracefully() {
        try {
            Log.i(TAG, "Initiating graceful shutdown...")
            isRunning.set(false)
            isInitialized.set(false)

            // إيقاف المراقبة
            stopHealthMonitor()
            stopPerformanceMonitor()

            // إلغاء تسجيل المستقبل

            // إيقاف المحرك
            stopSmsEngine()

            // تنظيف البيانات
            runCleanupNow()

            // تحرير الموارد
            releaseWakeLock()

            // إلغاء Coroutines
            serviceScope.cancel()
            healthScope.cancel()
            performanceScope.cancel()
            cleanupScope.cancel()

            // إغلاق قاعدة البيانات
            if (::dbHelper.isInitialized) {
                dbHelper.close()
            }

            logServiceStopped()
            Log.i(TAG, "Graceful shutdown completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error during graceful shutdown: ${e.message}", e)
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // ═══ مراقبة حالة النظام (Health Monitoring) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * بدء مراقبة صحة النظام
     */
    private fun startHealthMonitor() {
        try {
            Log.i(TAG, "Starting health monitor...")

            // Heartbeat
            heartbeatJob = serviceScope.launch {
                while (isActive && !isDestroyed.get()) {
                    try {
                        SystemEventLogger.recordService(applicationContext, STATUS_HEARTBEAT, "Alive")
                        Log.d(TAG, "Heartbeat recorded")
                    } catch (e: Exception) {
                        Log.e(TAG, "Heartbeat error: ${e.message}", e)
                    }
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            }

            // Health Check
            healthMonitorJob = healthScope.launch {
                while (isActive && !isDestroyed.get()) {
                    try {
                        performHealthCheck()
                    } catch (e: Exception) {
                        Log.e(TAG, "Health check error: ${e.message}", e)
                    }
                    delay(HEALTH_CHECK_INTERVAL_MS)
                }
            }

            Log.i(TAG, "Health monitoring started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start health monitor: ${e.message}", e)
        }
    }

    /**
     * إيقاف مراقبة صحة النظام
     */
    private fun stopHealthMonitor() {
        try {
            heartbeatJob?.cancel()
            healthMonitorJob?.cancel()
            Log.i(TAG, "Health monitor stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping health monitor: ${e.message}", e)
        }
    }

    /**
     * إجراء فحص صحة شامل
     */
    private suspend fun performHealthCheck() {
        try {
            Log.d(TAG, "Performing health check...")
            val issues = mutableListOf<String>()

            // فحص قاعدة البيانات
            if (!checkDatabase()) {
                issues.add("Database unhealthy")
                checkDatabaseIntegrity()
            }

            // فحص الأذونات
            if (!checkSmsPermissions()) {
                issues.add("SMS permissions missing")
            }

            if (!checkNotificationPermission()) {
                issues.add("Notification permission missing")
            }

            // فحص القيود
            checkBackgroundRestrictions()
            checkBatteryOptimization()


            // فحص المكونات
            if (!validateModules()) {
                issues.add("Modules validation failed")
            }

            // فحص الإعدادات
            if (!validateSettings()) {
                issues.add("Settings validation failed")
            }

            // فحص الجداول
            if (!validateTables()) {
                issues.add("Tables validation failed")
            }

            if (issues.isEmpty()) {
                lastHealthCheckResult.set(STATUS_HEALTHY)
                logHealthStatus(STATUS_HEALTHY)
                notifyServiceHealthy()
                updateNotification("نظام SMS يعمل بكفاءة")
                Log.d(TAG, "Health check passed - all systems healthy")
            } else {
                lastHealthCheckResult.set(STATUS_UNHEALTHY)
                val issueText = issues.joinToString(", ")
                logHealthStatus(STATUS_UNHEALTHY, issueText)
                notifyServiceError("مشاكل: $issueText")
                updateNotification("تنبيه: $issueText")
                Log.w(TAG, "Health check failed: $issueText")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Health check exception: ${e.message}", e)
            SystemEventLogger.recordError(applicationContext, "SMSService", "Health check failed: ${e.message}")
        }
    }

    /**
     * فحص قاعدة البيانات
     */
    private fun checkDatabase(): Boolean {
        return try {
            if (!::dbHelper.isInitialized || dbHelper.isClosed()) {
                Log.w(TAG, "Database not initialized or closed")
                initializeDatabase()
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Database check failed: ${e.message}", e)
            false
        }
    }

    /**
     * فحص أذونات SMS
     */
    private fun checkSmsPermissions(): Boolean {
        return try {
            val missing = REQUIRED_PERMISSIONS.filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "Missing permissions: ${missing.joinToString()}")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Permission check failed: ${e.message}", e)
            false
        }
    }

    /**
     * فحص إذن الإشعارات
     */
    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * فحص قيود التشغيل في الخلفية
     */
    private fun checkBackgroundRestrictions() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val isRestricted = powerManager::class.java.getMethod("isBackgroundRestricted").invoke(powerManager) as Boolean
                    if (isRestricted) {
                        Log.w(TAG, "Background restrictions are active")
                    }
                } catch (reflectEx: Exception) {
                    Log.d(TAG, "Could not check background restrictions via reflection: ${reflectEx.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background restriction check failed: ${e.message}", e)
        }
    }

    /**
     * فحص تحسين البطارية
     */
    private fun checkBatteryOptimization() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    Log.w(TAG, "Battery optimization is enabled for this app")
                }
            }

            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryStatus?.let { intent ->
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = level * 100 / scale.toFloat()
                if (batteryPct < 15) {
                    Log.w(TAG, "Battery level critical: ${batteryPct}%")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery optimization check failed: ${e.message}", e)
        }
    }


    /**
     * فحص سلامة قاعدة البيانات
     */
    private fun checkDatabaseIntegrity() {
        try {
            if (::dbHelper.isInitialized) {
                dbHelper.checkIntegrity()
                Log.d(TAG, "Database integrity check passed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database integrity check failed: ${e.message}", e)
            SystemEventLogger.recordError(applicationContext, "SMSService", "Database integrity: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تحميل الإعدادات (Settings Loading) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * تحميل جميع الإعدادات
     */
    private fun loadSettings() {
        try {
            Log.d(TAG, "Loading all settings...")
            loadSecuritySettings()
            loadConversationSettings()
            loadOtpSettings()
            loadMetricsSettings()
            loadRateLimitSettings()
            loadCleanupSettings()
            Log.d(TAG, "All settings loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings: ${e.message}", e)
        }
    }

    /**
     * إعادة تحميل الإعدادات
     */
    private fun reloadSettings() {
        try {
            Log.d(TAG, "Reloading settings...")
            settings.clear()
            securitySettings.clear()
            conversationSettings.clear()
            otpSettings.clear()
            metricsSettings.clear()
            rateLimitSettings.clear()
            cleanupSettings.clear()
            loadSettings()
            Log.i(TAG, "Settings reloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload settings: ${e.message}", e)
        }
    }

    /**
     * تحميل إعدادات الأمان
     */
    private fun loadSecuritySettings() {
        try {
            securitySettings["spam_threshold"] = "5"
            securitySettings["max_otp_attempts"] = "3"
            securitySettings["otp_expiry_minutes"] = "10"
            securitySettings["block_unknown_senders"] = "false"
            Log.d(TAG, "Security settings loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load security settings: ${e.message}", e)
        }
    }

    /**
     * تحميل إعدادات المحادثات
     */
    private fun loadConversationSettings() {
        try {
            conversationSettings["max_context_messages"] = "20"
            conversationSettings["context_expiry_hours"] = "24"
            conversationSettings["auto_close_hours"] = "72"
            conversationSettings["enable_smart_replies"] = "true"
            Log.d(TAG, "Conversation settings loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversation settings: ${e.message}", e)
        }
    }

    /**
     * تحميل إعدادات OTP
     */
    private fun loadOtpSettings() {
        try {
            otpSettings["otp_length"] = "6"
            otpSettings["otp_expiry_seconds"] = "600"
            otpSettings["max_resend_attempts"] = "3"
            otpSettings["cooldown_seconds"] = "60"
            Log.d(TAG, "OTP settings loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load OTP settings: ${e.message}", e)
        }
    }

    /**
     * تحميل إعدادات المقاييس
     */
    private fun loadMetricsSettings() {
        try {
            metricsSettings["enable_detailed_metrics"] = "true"
            metricsSettings["flush_interval_minutes"] = "10"
            metricsSettings["retention_days"] = "30"
            metricsSettings["alert_threshold"] = "100"
            Log.d(TAG, "Metrics settings loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metrics settings: ${e.message}", e)
        }
    }

    /**
     * تحميل إعدادات حدود المعدل
     */
    private fun loadRateLimitSettings() {
        try {
            rateLimitSettings["max_messages_per_minute"] = "30"
            rateLimitSettings["max_messages_per_hour"] = "200"
            rateLimitSettings["burst_limit"] = "10"
            rateLimitSettings["cooldown_ms"] = "1000"
            Log.d(TAG, "Rate limit settings loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rate limit settings: ${e.message}", e)
        }
    }

    /**
     * تحميل إعدادات التنظيف
     */
    private fun loadCleanupSettings() {
        try {
            cleanupSettings["auto_cleanup_enabled"] = "true"
            cleanupSettings["cleanup_interval_hours"] = "24"
            cleanupSettings["aggressive_cleanup"] = "false"
            cleanupSettings["preserve_recent_days"] = "7"
            Log.d(TAG, "Cleanup settings loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cleanup settings: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تنظيف قواعد البيانات (Database Cleanup) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * تحويل تاريخ نصي إلى timestamp (مللي ثانية)
     */
    private fun dateToTimestamp(dateStr: String): Long {
        return try {
            DATE_FORMAT.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse date: $dateStr, using 0")
            0L
        }
    }

    /**
     * تنظيف جدول الهاشات المعالجة
     */
    private fun cleanupProcessedHashes() {
        try {
            val cutoffTimestamp = dateToTimestamp(getDateBeforeDays(HASHES_RETENTION_DAYS))
            val deleted = dbHelper.deleteOlderThan(TABLE_PROCESSED_HASHES, "created_at", cutoffTimestamp)
            Log.d(TAG, "Cleaned up $deleted old processed hashes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup processed hashes: ${e.message}", e)
        }
    }

    /**
     * تنظيف جدول حدود المعدل
     */
    private fun cleanupRateLimits() {
        try {
            val cutoffTimestamp = dateToTimestamp(getDateBeforeDays(RATE_LIMITS_RETENTION_DAYS))
            val deleted = dbHelper.deleteOlderThan(TABLE_RATE_LIMITS, "timestamp", cutoffTimestamp)
            Log.d(TAG, "Cleaned up $deleted old rate limits")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup rate limits: ${e.message}", e)
        }
    }

    /**
     * تنظيف جدول سياق المحادثات
     */
    private fun cleanupConversationContext() {
        try {
            val cutoffTimestamp = dateToTimestamp(getDateBeforeDays(CONVERSATION_CONTEXT_RETENTION_DAYS))
            val deleted = dbHelper.deleteOlderThan(TABLE_CONVERSATION_CONTEXT, "last_updated", cutoffTimestamp)
            Log.d(TAG, "Cleaned up $deleted old conversation contexts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup conversation context: ${e.message}", e)
        }
    }

    /**
     * تنظيف جدول تاريخ التفاعلات
     */
    private fun cleanupInteractionHistory() {
        try {
            val cutoffTimestamp = dateToTimestamp(getDateBeforeDays(INTERACTION_HISTORY_RETENTION_DAYS))
            val deleted = dbHelper.deleteOlderThan(TABLE_INTERACTION_HISTORY, "interaction_time", cutoffTimestamp)
            Log.d(TAG, "Cleaned up $deleted old interaction history records")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup interaction history: ${e.message}", e)
        }
    }

    /**
     * تنظيف جدول تحققات OTP
     */
    private fun cleanupOtpVerifications() {
        try {
            val cutoffTimestamp = dateToTimestamp(getDateBeforeDays(OTP_VERIFICATIONS_RETENTION_DAYS))
            val deleted = dbHelper.deleteOlderThan(TABLE_OTP_VERIFICATIONS, "created_at", cutoffTimestamp)
            Log.d(TAG, "Cleaned up $deleted old OTP verifications")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup OTP verifications: ${e.message}", e)
        }
    }

    /**
     * تنظيف جدول الطلبات المتكررة
     */
    private fun cleanupRecurringOrders() {
        try {
            val cutoffTimestamp = dateToTimestamp(getDateBeforeDays(RECURRING_ORDERS_RETENTION_DAYS))
            val deleted = dbHelper.deleteOlderThan(TABLE_RECURRING_ORDERS, "last_order_date", cutoffTimestamp)
            Log.d(TAG, "Cleaned up $deleted old recurring orders")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup recurring orders: ${e.message}", e)
        }
    }

    /**
     * تنظيف جدول المقاييس
     */
    private fun cleanupMetrics() {
        try {
            val cutoffTimestamp = dateToTimestamp(getDateBeforeDays(METRICS_RETENTION_DAYS))
            val deleted = dbHelper.deleteOlderThan(TABLE_METRICS, "recorded_at", cutoffTimestamp)
            Log.d(TAG, "Cleaned up $deleted old metrics records")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup metrics: ${e.message}", e)
        }
    }

    /**
     * تنظيف جميع البيانات المنتهية الصلاحية
     */
    private fun cleanupExpiredData() {
        try {
            Log.i(TAG, "Starting expired data cleanup...")
            cleanupProcessedHashes()
            cleanupRateLimits()
            cleanupConversationContext()
            cleanupInteractionHistory()
            cleanupOtpVerifications()
            cleanupRecurringOrders()
            cleanupMetrics()
            Log.i(TAG, "Expired data cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Expired data cleanup failed: ${e.message}", e)
        }
    }

    /**
     * جدولة التنظيف الدوري
     */
    private fun scheduleCleanup() {
        try {
            cleanupJob = cleanupScope.launch {
                while (isActive && !isDestroyed.get()) {
                    try {
                        cleanupExpiredData()
                    } catch (e: Exception) {
                        Log.e(TAG, "Scheduled cleanup error: ${e.message}", e)
                    }
                    delay(CLEANUP_INTERVAL_MS)
                }
            }
            Log.i(TAG, "Cleanup scheduled every ${CLEANUP_INTERVAL_MS / 3600000} hours")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule cleanup: ${e.message}", e)
        }
    }

    /**
     * تنفيذ التنظيف فورًا
     */
    private fun runCleanupNow() {
        try {
            Log.i(TAG, "Running immediate cleanup...")
            cleanupExpiredData()
            Log.i(TAG, "Immediate cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Immediate cleanup failed: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ مزامنة البيانات (Data Sync) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * مزامنة سياق المحادثات
     */
    private fun syncConversationContext() {
        try {
            if (::smsConversationManager.isInitialized) {
                runBlocking {
                    smsConversationManager.syncContext()
                }
                Log.d(TAG, "Conversation context synced")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Conversation context sync failed: ${e.message}", e)
        }
    }

    /**
     * مزامنة تفضيلات العملاء
     */
    private fun syncCustomerPreferences() {
        try {
            if (::smsCustomerResolver.isInitialized) {
                runBlocking {
                    smsCustomerResolver.syncPreferences()
                }
                Log.d(TAG, "Customer preferences synced")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Customer preferences sync failed: ${e.message}", e)
        }
    }

    /**
     * مزامنة المقاييس
     */
    private fun syncMetrics() {
        try {
            if (::smsMetrics.isInitialized) {
                runBlocking {
                    smsMetrics.sync()
                }
                Log.d(TAG, "Metrics synced")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Metrics sync failed: ${e.message}", e)
        }
    }

    /**
     * مزامنة حدود المعدل
     */
    private fun syncRateLimits() {
        try {
            if (::smsSecurity.isInitialized) {
                runBlocking {
                    smsSecurity.syncRateLimits()
                }
                Log.d(TAG, "Rate limits synced")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Rate limits sync failed: ${e.message}", e)
        }
    }

    /**
     * مزامنة بيانات OTP
     */
    private fun syncOtpData() {
        try {
            if (::smsSecurityOTP.isInitialized) {
                runBlocking {
                    smsSecurityOTP.syncData()
                }
                Log.d(TAG, "OTP data synced")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTP data sync failed: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ إدارة الأداء (Performance Management) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * بدء مراقبة الأداء
     */
    private fun startPerformanceMonitor() {
        try {
            Log.i(TAG, "Starting performance monitor...")

            performanceMonitorJob = performanceScope.launch {
                while (isActive && !isDestroyed.get()) {
                    try {
                        monitorMemory()
                        monitorCpu()
                        monitorThreads()
                        collectStatistics()
                    } catch (e: Exception) {
                        Log.e(TAG, "Performance monitor error: ${e.message}", e)
                    }
                    delay(PERFORMANCE_INTERVAL_MS)
                }
            }

            Log.i(TAG, "Performance monitor started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start performance monitor: ${e.message}", e)
        }
    }

    /**
     * إيقاف مراقبة الأداء
     */
    private fun stopPerformanceMonitor() {
        try {
            performanceMonitorJob?.cancel()
            Log.i(TAG, "Performance monitor stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping performance monitor: ${e.message}", e)
        }
    }

    /**
     * مراقبة استهلاك الذاكرة
     */
    private fun monitorMemory() {
        try {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            val memoryUsagePercent = (usedMemory * 100 / maxMemory.toFloat())

            performanceStats["memory_max_mb"] = maxMemory / (1024 * 1024)
            performanceStats["memory_used_mb"] = usedMemory / (1024 * 1024)
            performanceStats["memory_free_mb"] = freeMemory / (1024 * 1024)
            performanceStats["memory_usage_percent"] = memoryUsagePercent

            if (memoryUsagePercent > 85) {
                Log.w(TAG, "High memory usage: ${memoryUsagePercent.toInt()}%")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Memory monitoring failed: ${e.message}", e)
        }
    }

    /**
     * مراقبة استهلاك المعالج
     */
    private fun monitorCpu() {
        try {
            val pid = Process.myPid()
            val processCpuTime = Process.getElapsedCpuTime()
            performanceStats["cpu_time_ms"] = processCpuTime
            performanceStats["cpu_usage_estimate"] = estimateCpuUsage()
        } catch (e: Exception) {
            Log.e(TAG, "CPU monitoring failed: ${e.message}", e)
        }
    }

    /**
     * تقدير استخدام المعالج
     */
    private fun estimateCpuUsage(): Double {
        return try {
            val startTime = SystemClock.elapsedRealtime()
            val startCpu = Process.getElapsedCpuTime()
            Thread.sleep(100)
            val endTime = SystemClock.elapsedRealtime()
            val endCpu = Process.getElapsedCpuTime()
            val cpuDiff = endCpu - startCpu
            val timeDiff = endTime - startTime
            if (timeDiff > 0) (cpuDiff.toDouble() / timeDiff.toDouble()) * 100 else 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    /**
     * مراقبة عدد الخيوط
     */
    private fun monitorThreads() {
        try {
            val threadCount = Thread.activeCount()
            val threadMap = Thread.getAllStackTraces()
            performanceStats["active_threads"] = threadCount
            performanceStats["total_threads"] = threadMap.size

            if (threadCount > 100) {
                Log.w(TAG, "High thread count: $threadCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Thread monitoring failed: ${e.message}", e)
        }
    }

    /**
     * جمع الإحصائيات
     */
    private fun collectStatistics() {
        try {
            performanceStats["uptime_seconds"] = (System.currentTimeMillis() - startTime.get()) / 1000
            performanceStats["processed_messages"] = processedMessageCount.get()
            performanceStats["error_count"] = errorCount.get()
            performanceStats["restart_attempts"] = restartAttempts.get()
            performanceStats["is_running"] = isRunning.get()
            performanceStats["is_paused"] = isPaused.get()
            performanceStats["is_initialized"] = isInitialized.get()
            performanceStats["last_health_check"] = lastHealthCheckResult.get()
            performanceStats["current_mode"] = currentMode.get()
            performanceStats["timestamp"] = System.currentTimeMillis()

            // تسجيل المقاييس
            if (::smsMetrics.isInitialized) {
                runBlocking {
                    smsMetrics.recordPerformanceStats(performanceStats)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Statistics collection failed: ${e.message}", e)
        }
    }

    /**
     * تصدير الإحصائيات
     */
    private fun dumpStatistics(): JSONObject {
        return try {
            collectStatistics()
            val json = JSONObject()
            performanceStats.forEach { (key, value) ->
                when (value) {
                    is Number -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    else -> json.put(key, value.toString())
                }
            }
            json
        } catch (e: Exception) {
            Log.e(TAG, "Statistics dump failed: ${e.message}", e)
            JSONObject().put("error", e.message)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ إدارة السجلات (Logging) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * تسجيل بدء الخدمة
     */
    private fun logServiceStarted() {
        try {
            Log.i(TAG, "═══════════════════════════════════════════════")
            Log.i(TAG, "Service STARTED")
            Log.i(TAG, "Version: 7.1.0")
            Log.i(TAG, "Mode: ${currentMode.get()}")
            Log.i(TAG, "Timestamp: ${DATETIME_FORMAT.format(Date())}")
            Log.i(TAG, "═══════════════════════════════════════════════")
            SystemEventLogger.recordService(this, STATUS_SERVICE_STARTED, "Service v7.1.0 started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log service start: ${e.message}", e)
        }
    }

    /**
     * تسجيل توقف الخدمة
     */
    private fun logServiceStopped() {
        try {
            val uptime = (System.currentTimeMillis() - startTime.get()) / 1000
            Log.i(TAG, "═══════════════════════════════════════════════")
            Log.i(TAG, "Service STOPPED")
            Log.i(TAG, "Uptime: ${uptime}s")
            Log.i(TAG, "Messages processed: ${processedMessageCount.get()}")
            Log.i(TAG, "Errors: ${errorCount.get()}")
            Log.i(TAG, "═══════════════════════════════════════════════")
            SystemEventLogger.recordService(this, STATUS_SERVICE_STOPPED, "Uptime: ${uptime}s, Messages: ${processedMessageCount.get()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log service stop: ${e.message}", e)
        }
    }

    /**
     * تسجيل إعادة تشغيل الخدمة
     */
    private fun logServiceRestarted() {
        try {
            val attempt = restartAttempts.incrementAndGet()
            Log.w(TAG, "═══════════════════════════════════════════════")
            Log.w(TAG, "Service RESTARTED (attempt $attempt)")
            Log.w(TAG, "Timestamp: ${DATETIME_FORMAT.format(Date())}")
            Log.w(TAG, "═══════════════════════════════════════════════")
            SystemEventLogger.recordService(this, STATUS_SERVICE_RESTARTED, "Restart attempt: $attempt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log service restart: ${e.message}", e)
        }
    }

    /**
     * تسجيل حالة الصحة
     */
    private fun logHealthStatus(status: String, details: String? = null) {
        try {
            if (details != null) {
                Log.i(TAG, "Health Status: $status - $details")
            } else {
                Log.i(TAG, "Health Status: $status")
            }
            SystemEventLogger.recordService(this, status, details ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log health status: ${e.message}", e)
        }
    }

    /**
     * تسجيل خطأ حرج
     */
    private fun logCriticalError(error: Throwable) {
        try {
            errorCount.incrementAndGet()
            Log.e(TAG, "═══════════════════════════════════════════════")
            Log.e(TAG, "CRITICAL ERROR")
            Log.e(TAG, "Type: ${error.javaClass.simpleName}")
            Log.e(TAG, "Message: ${error.message}")
            Log.e(TAG, "Stack trace:", error)
            Log.e(TAG, "═══════════════════════════════════════════════")
            SystemEventLogger.recordError(this, "SMSService", "Critical: ${error.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log critical error: ${e.message}", e)
        }
    }

    /**
     * تسجيل عملية الاسترداد
     */
    private fun logRecovery(action: String) {
        try {
            Log.i(TAG, "═══════════════════════════════════════════════")
            Log.i(TAG, "RECOVERY ACTION: $action")
            Log.i(TAG, "Timestamp: ${DATETIME_FORMAT.format(Date())}")
            Log.i(TAG, "═══════════════════════════════════════════════")
            SystemEventLogger.recordService(this, "RECOVERY", action)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log recovery: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ معالجة الأخطاء (Error Handling) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * معالجة خطأ فادح
     */
    private fun handleFatalError(error: Throwable) {
        try {
            logCriticalError(error)

            val currentAttempts = restartAttempts.get()
            val now = System.currentTimeMillis()
            val lastRestart = lastRestartTime.get()

            // إعادة تعيين العداد إذا مر وقت كافٍ
            if (now - lastRestart > 600_000L) {
                restartAttempts.set(0)
            }

            if (currentAttempts < MAX_RESTART_ATTEMPTS) {
                lastRestartTime.set(now)
                Log.w(TAG, "Attempting recovery (attempt ${currentAttempts + 1}/$MAX_RESTART_ATTEMPTS)")
                recoverFromFailure()
            } else {
                Log.e(TAG, "Max restart attempts reached. Service will not auto-restart.")
                reportError(error)
                shutdownGracefully()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error handler failed: ${e.message}", e)
        }
    }

    /**
     * الاسترداد من الفشل
     */
    private fun recoverFromFailure() {
        try {
            logRecovery("Starting recovery sequence")

            // 1. إيقاف المكونات
            stopSmsEngine()

            // 2. إلغاء المراقبة
            stopHealthMonitor()
            stopPerformanceMonitor()

            // 3. تنظيف
            cleanupScope.launch {
                runCleanupNow()
            }

            // 4. إعادة تهيئة
            Thread.sleep(RESTART_BACKOFF_MS)
            initializeService()
            initializeDatabase()
            initializeSmsModules()

            // 5. إعادة التشغيل
            startSmsEngine()
            startHealthMonitor()
            startPerformanceMonitor()

            logRecovery("Recovery completed successfully")
            notifyServiceHealthy()

        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed: ${e.message}", e)
            restartAfterCrash()
        }
    }

    /**
     * إعادة التشغيل بعد تعطل
     */
    private fun restartAfterCrash() {
        try {
            Log.w(TAG, "Initiating crash recovery restart...")
            logServiceRestarted()

            val restartIntent = Intent(applicationContext, SMSService::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("restart_reason", "crash_recovery")
                putExtra("restart_attempt", restartAttempts.get())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }

            Log.i(TAG, "Crash recovery restart initiated")

        } catch (e: Exception) {
            Log.e(TAG, "Crash recovery restart failed: ${e.message}", e)
        }
    }

    /**
     * الإبلاغ عن خطأ
     */
    private fun reportError(error: Throwable) {
        try {
            val report = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("error_type", error.javaClass.simpleName)
                put("error_message", error.message ?: "Unknown")
                put("stack_trace", error.stackTraceToString())
                put("service_version", "7.0.0")
                put("uptime_seconds", (System.currentTimeMillis() - startTime.get()) / 1000)
                put("restart_attempts", restartAttempts.get())
                put("error_count", errorCount.get())
            }

            // إرسال Broadcast
            val intent = Intent(ACTION_ERROR_REPORT).apply {
                putExtra("error_report", report.toString())
            }
            sendBroadcast(intent)

            // تسجيل في قاعدة البيانات
            if (::smsMetrics.isInitialized) {
                runBlocking {
                    smsMetrics.recordEvent(
                        eventType = SmsMetrics.EventType.CRITICAL_ERROR,
                        phone = "",
                        details = report.toString()
                    )
                }
            }

            Log.e(TAG, "Error reported: ${report.toString().take(500)}")

        } catch (e: Exception) {
            Log.e(TAG, "Error reporting failed: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ جدولة المهام (Task Scheduling) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * جدولة صيانة دورية
     */
    private fun scheduleMaintenance() {
        try {
            val maintenanceRequest = PeriodicWorkRequestBuilder<MaintenanceWorker>(
                12, TimeUnit.HOURS,
                1, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                MAINTENANCE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                maintenanceRequest
            )
            Log.i(TAG, "Maintenance scheduled every 12 hours")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule maintenance: ${e.message}", e)
        }
    }

    /**
     * جدولة فحوصات الصحة
     */
    private fun scheduleHealthChecks() {
        try {
            // يتم التنفيذ عبر Coroutines في startHealthMonitor()
            Log.i(TAG, "Health checks scheduled via coroutine")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule health checks: ${e.message}", e)
        }
    }

    /**
     * جدولة تنظيف قاعدة البيانات
     */
    private fun scheduleDatabaseCleanup() {
        try {
            scheduleCleanup()
            Log.i(TAG, "Database cleanup scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule database cleanup: ${e.message}", e)
        }
    }

    /**
     * جدولة حفظ المقاييس
     */
    private fun scheduleMetricsFlush() {
        try {
            metricsFlushJob = serviceScope.launch {
                while (isActive && !isDestroyed.get()) {
                    try {
                        collectStatistics()
                        if (::smsMetrics.isInitialized) {
                            runBlocking {
                                smsMetrics.flush()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Metrics flush error: ${e.message}", e)
                    }
                    delay(METRICS_FLUSH_INTERVAL_MS)
                }
            }
            Log.i(TAG, "Metrics flush scheduled every ${METRICS_FLUSH_INTERVAL_MS / 60000} minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule metrics flush: ${e.message}", e)
        }
    }

    /**
     * جدولة فحوصات الأمان
     */
    private fun scheduleSecurityChecks() {
        try {
            securityCheckJob = serviceScope.launch {
                while (isActive && !isDestroyed.get()) {
                    try {
                        if (::smsSecurity.isInitialized) {
                            smsSecurity.performSecurityCheck()
                        }
                        if (::smsSecurityOTP.isInitialized) {
                            smsSecurityOTP.cleanupExpired()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Security check error: ${e.message}", e)
                    }
                    delay(SECURITY_CHECK_INTERVAL_MS)
                }
            }
            Log.i(TAG, "Security checks scheduled every ${SECURITY_CHECK_INTERVAL_MS / 60000} minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule security checks: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ إدارة الإشعارات (Notification Management) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * إنشاء قناة الإشعارات (Android 8+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Station SMS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة مراقبة الرسائل النصية والنسخ الاحتياطي"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }

    /**
     * بناء إشعار الخدمة العاملة في المقدمة
     */
    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText("نظام SMS التفاعلي يعمل")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * تحديث نص الإشعار بشكل ديناميكي
     */
    private fun updateNotification(text: String) {
        if (isDestroyed.get()) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * إشعار بصحة الخدمة
     */
    private fun notifyServiceHealthy() {
        try {
            updateNotification("نظام SMS يعمل بكفاءة")
            val intent = Intent(ACTION_HEALTH_STATUS).apply {
                putExtra("status", STATUS_HEALTHY)
                putExtra("timestamp", System.currentTimeMillis())
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify healthy: ${e.message}", e)
        }
    }

    /**
     * إشعار بخطأ في الخدمة
     */
    private fun notifyServiceError(message: String) {
        try {
            updateNotification("⚠️ $message")
            val intent = Intent(ACTION_HEALTH_STATUS).apply {
                putExtra("status", STATUS_UNHEALTHY)
                putExtra("message", message)
                putExtra("timestamp", System.currentTimeMillis())
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify error: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ واجهة التشخيص (Diagnostics Interface) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * الحصول على حالة الخدمة الحالية
     */
    fun getServiceStatus(): JSONObject {
        return JSONObject().apply {
            put("is_running", isRunning.get())
            put("is_initialized", isInitialized.get())
            put("is_paused", isPaused.get())
            put("is_destroyed", isDestroyed.get())
            put("mode", currentMode.get())
            put("server_enabled", false)
            put("backup_scheduled", true)
            put("uptime_seconds", (System.currentTimeMillis() - startTime.get()) / 1000)
            put("restart_attempts", restartAttempts.get())
            put("error_count", errorCount.get())
            put("processed_messages", processedMessageCount.get())
            put("timestamp", DATETIME_FORMAT.format(Date()))
            put("version", "7.0.0")
            put("health_status", lastHealthCheckResult.get())
        }
    }

    /**
     * الحصول على إحصائيات الخدمة
     */
    fun getServiceStatistics(): JSONObject {
        return try {
            JSONObject().apply {
                put("uptime_seconds", (System.currentTimeMillis() - startTime.get()) / 1000)
                put("total_messages_processed", processedMessageCount.get())
                put("total_errors", errorCount.get())
                put("restart_attempts", restartAttempts.get())
                put("is_healthy", isHealthy())
                put("has_all_permissions", hasAllPermissions())
                put("module_load_times", JSONObject(moduleLoadTimes as Map<*, *>))
                put("timestamp", System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get service statistics: ${e.message}", e)
            JSONObject().put("error", e.message)
        }
    }

    /**
     * الحصول على المقاييس الحالية
     */
    fun getCurrentMetrics(): JSONObject {
        return try {
            if (::smsMetrics.isInitialized) {
                runBlocking {
                    smsMetrics.getCurrentMetrics()
                }
            } else {
                JSONObject().put("error", "Metrics not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current metrics: ${e.message}", e)
            JSONObject().put("error", e.message)
        }
    }

    /**
     * الحصول على الوحدات المحملة
     */
    fun getLoadedModules(): JSONArray {
        return try {
            val modules = JSONArray()
            val moduleList = listOf(
                "DatabaseHelper" to ::dbHelper.isInitialized,
                "SmsMetrics" to ::smsMetrics.isInitialized,
                "SmsSecurity" to ::smsSecurity.isInitialized,
                "SmsSecurityOTP" to ::smsSecurityOTP.isInitialized,
                "SmsProcessor" to ::smsProcessor.isInitialized,
                "SmsConversationManager" to ::smsConversationManager.isInitialized,
                "SmsCustomerResolver" to ::smsCustomerResolver.isInitialized,
                "SmsIntentDetector" to ::smsIntentDetector.isInitialized,
                "SmsReplyManager" to ::smsReplyManager.isInitialized,
            )
            moduleList.forEach { (name, initialized) ->
                modules.put(JSONObject().apply {
                    put("name", name)
                    put("initialized", initialized)
                    put("load_time_ms", moduleLoadTimes[name.lowercase()] ?: -1)
                })
            }
            modules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get loaded modules: ${e.message}", e)
            JSONArray().put(JSONObject().put("error", e.message))
        }
    }

    /**
     * الحصول على حالة قاعدة البيانات
     */
    fun getDatabaseStatus(): JSONObject {
        return try {
            JSONObject().apply {
                put("initialized", ::dbHelper.isInitialized)
                put("is_closed", if (::dbHelper.isInitialized) dbHelper.isClosed() else true)
                put("is_healthy", checkDatabase())
                put("tables_valid", validateTables())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get database status: ${e.message}", e)
            JSONObject().put("error", e.message)
        }
    }

    /**
     * فرض التنظيف
     */
    fun forceCleanup() {
        try {
            Log.i(TAG, "Force cleanup requested")
            cleanupScope.launch {
                runCleanupNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Force cleanup failed: ${e.message}", e)
        }
    }

    /**
     * فرض إعادة التحميل
     */
    fun forceReload() {
        try {
            Log.i(TAG, "Force reload requested")
            reloadSettings()
            syncConversationContext()
            syncCustomerPreferences()
            syncMetrics()
            syncRateLimits()
            syncOtpData()
            Log.i(TAG, "Force reload completed")
        } catch (e: Exception) {
            Log.e(TAG, "Force reload failed: ${e.message}", e)
        }
    }

    /**
     * إعادة تشغيل الوحدات
     */
    fun restartModules() {
        try {
            Log.i(TAG, "Restarting modules...")
            initializeSmsModules()
            Log.i(TAG, "Modules restarted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Module restart failed: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ دوال التحقق (Validation) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * التحقق من قاعدة البيانات
     */
    private fun validateDatabase(): Boolean {
        return try {
            if (!::dbHelper.isInitialized) {
                Log.w(TAG, "Database not initialized")
                return false
            }
            dbHelper.isOpen()
        } catch (e: Exception) {
            Log.e(TAG, "Database validation failed: ${e.message}", e)
            false
        }
    }

    /**
     * التحقق من المكونات
     */
    private fun validateModules(): Boolean {
        return try {
            val requiredModules = listOf(
                ::dbHelper.isInitialized,
                ::smsMetrics.isInitialized,
                ::smsSecurity.isInitialized,
                ::smsProcessor.isInitialized,
                ::smsConversationManager.isInitialized,
                ::smsCustomerResolver.isInitialized,
                ::smsIntentDetector.isInitialized,
                ::smsReplyManager.isInitialized
            )
            val allInitialized = requiredModules.all { it }
            if (!allInitialized) {
                Log.w(TAG, "Some modules are not initialized")
            }
            allInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Module validation failed: ${e.message}", e)
            false
        }
    }

    /**
     * التحقق من الأذونات
     */
    private fun validatePermissions(): Boolean {
        return hasAllPermissions()
    }

    /**
     * التحقق من الإعدادات
     */
    private fun validateSettings(): Boolean {
        return try {
            settings.isNotEmpty() ||
            securitySettings.isNotEmpty() ||
            conversationSettings.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Settings validation failed: ${e.message}", e)
            false
        }
    }

    /**
     * التحقق من الجداول
     */
    private fun validateTables(): Boolean {
        return try {
            if (!::dbHelper.isInitialized) return false
            val requiredTables = listOf(
                TABLE_PROCESSED_HASHES,
                TABLE_RATE_LIMITS,
                TABLE_CONVERSATION_CONTEXT,
                TABLE_CUSTOMER_PREFERENCES,
                TABLE_INTERACTION_HISTORY,
                TABLE_RECURRING_ORDERS,
                TABLE_METRICS,
                TABLE_OTP_VERIFICATIONS
            )
            requiredTables.all { table ->
                dbHelper.tableExists(table)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Table validation failed: ${e.message}", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ دوال المساعدة (Helpers) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * التحقق مما إذا كانت الخدمة تعمل
     */
    fun isServiceRunning(): Boolean {
        return isRunning.get() && !isDestroyed.get() && isInitialized.get()
    }

    /**
     * التحقق من صحة الخدمة
     */
    fun isHealthy(): Boolean {
        return !isDestroyed.get() &&
                isInitialized.get() &&
                isRunning.get() &&
                !isPaused.get() &&
                ::dbHelper.isInitialized &&
                !dbHelper.isClosed() &&
                ::smsProcessor.isInitialized &&
    }

    /**
     * التحقق من وجود جميع الأذونات
     */
    private fun hasAllPermissions(): Boolean {
        return try {
            REQUIRED_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Permission check failed: ${e.message}", e)
            false
        }
    }

    /**
     * التحقق مما إذا كان يجب إعادة التشغيل
     */
    private fun shouldRestart(): Boolean {
        val now = System.currentTimeMillis()
        val lastRestart = lastRestartTime.get()
        return restartAttempts.get() < MAX_RESTART_ATTEMPTS &&
                (now - lastRestart > RESTART_BACKOFF_MS)
    }

    /**
     * التحقق مما إذا كان يجب التنظيف
     */
    private fun shouldCleanup(): Boolean {
        return cleanupSettings["auto_cleanup_enabled"] == "true"
    }

    /**
     * التحقق مما إذا كان يجب جمع المقاييس
     */
    private fun shouldCollectMetrics(): Boolean {
        return metricsSettings["enable_detailed_metrics"] == "true"
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ أدوات مساعدة (Utilities) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * الحصول على تاريخ قبل عدد معين من الأيام
     */
    private fun getDateBeforeDays(days: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return DATE_FORMAT.format(calendar.time)
    }

    /**
     * تسجيل حدث في جدول sms_metrics
     */
    private fun recordServiceEvent(eventType: String, details: String? = null) {
        try {
            if (::smsMetrics.isInitialized) {
                serviceScope.launch {
                    try {
                        val eventEnum = SmsMetrics.EventType.valueOf(eventType)
                        smsMetrics.recordEvent(
                            eventType = eventEnum,
                            phone = "",
                            details = details ?: ""
                        )
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Unknown event type for metrics: $eventType")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record event: ${e.message}", e)
        }
    }

    /**
     * الحصول على Wake Lock لمنع إيقاف الخدمة
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10 دقائق
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}", e)
        }
    }

    /**
     * تحرير Wake Lock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        }
    }

    /**
     * جدولة عملية النسخ الاحتياطي التلقائي
     */
    private fun scheduleAutoBackup() {
        try {
            val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(
                24, TimeUnit.HOURS,
                1, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                backupRequest
            )
            Log.i(TAG, "Auto backup scheduled via WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule auto backup: ${e.message}", e)
        }
    }

    /**
     * إرسال Broadcast بحالة الخدمة
     */
    private fun sendServiceStatusBroadcast() {
        try {
            val intent = Intent(ACTION_SERVICE_STATUS_CHANGED).apply {
                putExtra("status", getServiceStatus().toString())
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status broadcast: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ واجهات عامة (Public API) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * الحصول على مرجع SmsProcessor
     */
    fun getSmsProcessor(): SmsProcessor? {
        return if (::smsProcessor.isInitialized) smsProcessor else null
    }

    /**
     * الحصول على مرجع SmsMetrics
     */
    fun getSmsMetrics(): SmsMetrics? {
        return if (::smsMetrics.isInitialized) smsMetrics else null
    }

    /**
     * الحصول على مرجع DatabaseHelper
     */
    fun getDatabaseHelper(): DatabaseHelper? {
        return if (::dbHelper.isInitialized && !dbHelper.isClosed()) dbHelper else null
    }

    /**
     * الحصول على مرجع SmsSecurity
     */
    fun getSmsSecurity(): SmsSecurity? {
        return if (::smsSecurity.isInitialized) smsSecurity else null
    }

    /**
     * الحصول على مرجع SmsSecurityOTP
     */
    fun getSmsSecurityOTP(): SmsSecurityOTP? {
        return if (::smsSecurityOTP.isInitialized) smsSecurityOTP else null
    }

    /**
     * الحصول على مرجع SmsConversationManager
     */
    fun getSmsConversationManager(): SmsConversationManager? {
        return if (::smsConversationManager.isInitialized) smsConversationManager else null
    }

    /**
     * الحصول على مرجع SmsCustomerResolver
     */
    fun getSmsCustomerResolver(): SmsCustomerResolver? {
        return if (::smsCustomerResolver.isInitialized) smsCustomerResolver else null
    }

    /**
     * الحصول على مرجع SmsIntentDetector
     */
    fun getSmsIntentDetector(): SmsIntentDetector? {
        return if (::smsIntentDetector.isInitialized) smsIntentDetector else null
    }

    /**
     * الحصول على مرجع SmsReplyManager
     */
    fun getSmsReplyManager(): SmsReplyManager? {
        return if (::smsReplyManager.isInitialized) smsReplyManager else null
    }

    /**
     * الحصول على مرجع SmsReceiver
     */
        return settings.toMap()
    }

    /**
     * الحصول على إعدادات الأمان
     */
    fun getSecuritySettings(): Map<String, String> {
        return securitySettings.toMap()
    }

    /**
     * الحصول على إعدادات المحادثات
     */
    fun getConversationSettings(): Map<String, String> {
        return conversationSettings.toMap()
    }

    /**
     * الحصول على إعدادات OTP
     */
    fun getOtpSettings(): Map<String, String> {
        return otpSettings.toMap()
    }

    /**
     * الحصول على إعدادات المقاييس
     */
    fun getMetricsSettings(): Map<String, String> {
        return metricsSettings.toMap()
    }

    /**
     * الحصول على إعدادات حدود المعدل
     */
    fun getRateLimitSettings(): Map<String, String> {
        return rateLimitSettings.toMap()
    }

    /**
     * الحصول على إعدادات التنظيف
     */
    fun getCleanupSettings(): Map<String, String> {
        return cleanupSettings.toMap()
    }

    /**
     * الحصول على إحصائيات الأداء
     */
    fun getPerformanceStats(): Map<String, Any> {
        return performanceStats.toMap()
    }

    /**
     * الحصول على أوقات تحميل الوحدات
     */
    fun getModuleLoadTimes(): Map<String, Long> {
        return moduleLoadTimes.toMap()
    }

    /**
     * زيادة عداد الرسائل المعالجة
     */
    fun incrementProcessedCount() {
        processedMessageCount.incrementAndGet()
    }

    /**
     * الحصول على عدد الرسائل المعالجة
     */
    fun getProcessedMessageCount(): Int {
        return processedMessageCount.get()
    }

    /**
     * الحصول على عدد الأخطاء
     */
    fun getErrorCount(): Int {
        return errorCount.get()
    }

    /**
     * الحصول على وقت التشغيل
     */
    fun getUptimeSeconds(): Long {
        return (System.currentTimeMillis() - startTime.get()) / 1000
    }

    /**
     * الحصول على نمط التشغيل الحالي
     */
    fun getCurrentMode(): String {
        return currentMode.get()
    }

    /**
     * تعيين نمط التشغيل
     */
    fun setMode(mode: String) {
        currentMode.set(mode)
        Log.i(TAG, "Mode changed to: $mode")
    }

    /**
     * التحقق مما إذا كانت الخدمة متوقفة مؤقتًا
     */
    fun isPaused(): Boolean {
        return isPaused.get()
    }

    /**
     * التحقق مما إذا كانت الخدمة مدمرة
     */
    fun isDestroyed(): Boolean {
        return isDestroyed.get()
    }

    /**
     * التحقق مما إذا كانت الخدمة مهيأة
     */
    fun isInitialized(): Boolean {
        return isInitialized.get()
    }
}