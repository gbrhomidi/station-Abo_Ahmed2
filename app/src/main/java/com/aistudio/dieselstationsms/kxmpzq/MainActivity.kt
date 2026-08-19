package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.aistudio.dieselstationsms.kxmpzq.receiver.*
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService
import com.aistudio.dieselstationsms.kxmpzq.notifications.TaskNotificationManager
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceStatusRepository
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceLaunchResult
import com.aistudio.dieselstationsms.kxmpzq.startup.SmsServiceLauncher
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.sms.*
import com.aistudio.dieselstationsms.kxmpzq.settings.backup.BackupEntry
import com.aistudio.dieselstationsms.kxmpzq.settings.di.SettingsModule
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.ui.theme.MyApplicationTheme
import com.aistudio.dieselstationsms.kxmpzq.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════
 * MainActivity - النشاط الرئيسي لتطبيق محطة أبو أحمد
 * ═══════════════════════════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val CHANNEL_ID = "station_sms_channel"
        private const val CHANNEL_NAME = "Station SMS Service"

        private const val BIOMETRIC_TITLE = "المصادقة البيومترية"
        private const val BIOMETRIC_SUBTITLE = "استخدم بصمة الإصبع أو الوجه للدخول"
        private const val BIOMETRIC_CANCEL = "إلغاء"

        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_USER_NAME = "user_name"

        // DEV_MODE: يمنح المستخدم رقم 1 صلاحيات التطوير الكاملة في نسخ Debug فقط.
        // يجب تعطيل/إزالة هذا الاستثناء قبل إنتاج نسخة Release.
        private const val DEV_MODE_ADMIN_USER_ID = 1L

        private var webViewInstanceId = 0

        // ============================
        // DebugLogger - نظام التشخيص المركزي
        // ============================
        object DebugLogger {
            private const val TAG = "DebugLogger"
            private var webViewRef: WeakReference<WebView>? = null
            private var isVConsoleReady = false

            fun attachWebView(webView: WebView?) {
                webViewRef = WeakReference(webView)
                isVConsoleReady = true
                info("DebugLogger", "Attached to WebView")
            }

            fun detachWebView() {
                webViewRef?.clear()
                webViewRef = null
                isVConsoleReady = false
            }

            fun info(tag: String, message: String) {
                val full = "[$tag] $message"
                Log.i(TAG, full)
                sendToVConsole("INFO", full)
            }

            fun warn(tag: String, message: String) {
                val full = "[$tag] $message"
                Log.w(TAG, full)
                sendToVConsole("WARN", full)
            }

            fun error(tag: String, message: String, throwable: Throwable? = null) {
                val full = if (throwable != null) {
                    "$message\n${throwable.stackTraceToString()}"
                } else {
                    message
                }
                Log.e(TAG, "[$tag] $full")
                sendToVConsole("ERROR", "[$tag] $full")
            }

            private fun sendToVConsole(level: String, message: String) {
                if (!isVConsoleReady) return
                val wv = webViewRef?.get() ?: return
                val escaped = message.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                val js = """
                    (function() {
                        if (typeof vConsole !== 'undefined' && vConsole) {
                            var msg = "$escaped";
                            var prefix = "[Kotlin] ";
                            switch("$level") {
                                case "INFO": vConsole.log(prefix + msg); break;
                                case "WARN": vConsole.warn(prefix + msg); break;
                                case "ERROR": vConsole.error(prefix + msg); break;
                                default: vConsole.log(prefix + msg);
                            }
                        }
                    })();
                """.trimIndent()
                wv.post {
                    try {
                        wv.evaluateJavascript(js, null)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }

            fun logException(tag: String, throwable: Throwable) {
                error(tag, throwable.message ?: "Exception occurred", throwable)
            }

            fun logEvent(event: String, details: String = "") {
                info("EVENT", "$event | $details")
            }
        }

        // ============================
        // معالج الأخطاء العام (Global Exception Handler)
        // ============================
        private val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

        fun installGlobalExceptionHandler() {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                DebugLogger.logException("GlobalException", throwable)
                defaultExceptionHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    // ====== مكونات النشاط ======
    private var webView: WebView? = null
    private var geminiApiKey: String = ""
    private var serverReady = false
    private val isDestroyed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var isWebViewInitialized = false
    private var isErrorPageShown = false
    private var backgroundJob: Job? = null
    private var maintenanceJob: Job? = null

    private var webAppInterface: WebAppInterface? = null
    private val BRIDGE_INITIALIZED_TAG = 0x7F0F0001

    private val isDebugMode: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private lateinit var dbHelper: DatabaseHelper
    private val settingsModule: SettingsModule by lazy {
        SettingsModule(applicationContext)
    }
    private lateinit var geminiHelper: GeminiAIHelper
    private lateinit var smsServiceLauncher: SmsServiceLauncher
    internal lateinit var sharedPrefs: SharedPreferences
    private var pendingNotificationTaskId: Long? = null

    // ====== متغيرات الجلسة ======
    private var currentAuthToken: String?
        get() = sharedPrefs.getString(KEY_TOKEN, null)
        set(value) {
            sharedPrefs.edit().putString(KEY_TOKEN, value).apply()
        }
    private var currentUserId: Long
        get() = sharedPrefs.getLong(KEY_USER_ID, 0)
        set(value) {
            sharedPrefs.edit().putLong(KEY_USER_ID, value).apply()
        }
    private var currentUserRole: String
        get() = sharedPrefs.getString(KEY_USER_ROLE, "") ?: ""
        set(value) {
            sharedPrefs.edit().putString(KEY_USER_ROLE, value).apply()
        }
    private var currentUserName: String
        get() = sharedPrefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) {
            sharedPrefs.edit().putString(KEY_USER_NAME, value).apply()
        }

    // ============================================================
    // 1. دورة حياة النشاط
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureNotificationIntent(intent)

        installGlobalExceptionHandler()

        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            DebugLogger.warn("onCreate", "enableEdgeToEdge failed: ${e.message}")
        }

        try {
            initEncryptedPrefs()
        } catch (e: Exception) {
            sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            DebugLogger.warn("onCreate", "Encrypted prefs fallback to regular")
        }

        try {
            dbHelper = DatabaseHelper.getInstance(applicationContext)
            smsServiceLauncher = SmsServiceLauncher(
                applicationContext,
                ServiceStatusRepository(applicationContext)
            )
            DebugLogger.info("Database", "DatabaseHelper initialized")
        } catch (e: Exception) {
            DebugLogger.logException("Database", e)
            Toast.makeText(this, "فشل تهيئة قاعدة البيانات", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            geminiHelper = GeminiAIHelper(this)
            geminiApiKey = loadEnvKey("GEMINI_API_KEY")
            if (geminiApiKey.isNotEmpty()) {
                geminiHelper.initialize(geminiApiKey)
            }
        } catch (e: Exception) {
            DebugLogger.warn("Gemini", "Gemini initialization failed: ${e.message}")
        }

        createNotificationChannel()

        if (isDebugMode) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
                DebugLogger.info("WebView", "Debugging enabled")
            } catch (e: Exception) {
                DebugLogger.warn("WebView", "Could not enable debugging: ${e.message}")
            }
        }

        requestAllPermissions()

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        handler.postDelayed({
            if (!isDestroyed.get()) {
                loadWebViewFromAssets()
            }
        }, 2000)

        lifecycleScope.launch {
            initializeSystem()
        }

        scheduleBackgroundTasks()
        TaskNotificationManager.reschedulePendingTasksAsync(applicationContext)

        DebugLogger.info("MainActivity", "onCreate finished")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationIntent(intent)
    }

    private fun captureNotificationIntent(intent: Intent?) {
        if (intent?.getStringExtra("notification_action") == "open_tasks") {
            val taskId = intent.getLongExtra(TaskNotificationManager.EXTRA_TASK_ID, 0L)
            if (taskId <= 0L) return
            val currentUrl = webView?.url.orEmpty()
            if (currentUrl.endsWith("/main.html") || currentUrl.endsWith("main.html")) {
                webView?.loadUrl("file:///android_asset/screens/tasks.html?task_id=$taskId")
            } else {
                pendingNotificationTaskId = taskId
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updateUIState()
        if (!isDestroyed.get()) {
            checkSmsSystemHealth()
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        updateUIState()
        if (!isDestroyed.get() && webView != null) {
            if (!webView!!.isAttachedToWindow) {
                handler.postDelayed({
                    if (!isDestroyed.get()) {
                        loadWebViewFromAssets()
                    }
                }, 500)
            }
        }
        DebugLogger.logEvent("app_resumed", "Application resumed")
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        DebugLogger.logEvent("app_paused", "Application paused")
    }

    override fun onDestroy() {
        isDestroyed.set(true)

        backgroundJob?.cancel()
        backgroundJob = null
        maintenanceJob?.cancel()
        maintenanceJob = null

        handler.removeCallbacksAndMessages(null)

        DebugLogger.detachWebView()

        try {
            val wv = webView
            if (wv != null) {
                destroyWebView(wv)
                webView = null
            }
        } catch (e: Exception) {
            DebugLogger.warn("onDestroy", "Error destroying WebView: ${e.message}")
        }

        super.onDestroy()
        DebugLogger.info("MainActivity", "onDestroy finished")
    }

    // ============================================================
    // 2. تهيئة قاعدة البيانات
    // ============================================================

    private suspend fun initializeDatabase() {
        withContext(Dispatchers.IO) {
            try {
                DebugLogger.info("Database", "DATABASE_CREATE_STARTED")
                val tables = dbHelper.getTableCounts()
                validateDatabaseSchema()
                migrateDatabaseIfNeeded()
                DebugLogger.info("Database", "DATABASE_CREATE_SUCCESS")
                checkEssentialTables()
            } catch (e: Exception) {
                DebugLogger.logException("Database", e)
                handleApplicationError(e)
            }
        }
    }

    private suspend fun validateDatabaseSchema() {
        withContext(Dispatchers.IO) {
            try {
                val requiredTables = listOf(
                    "sms_processed_hashes",
                    "sms_rate_limits",
                    "sms_conversation_context",
                    "sms_customer_preferences",
                    "sms_interaction_history",
                    "sms_recurring_orders",
                    "sms_metrics",
                    "sms_otp_verifications"
                )
                for (table in requiredTables) {
                    dbHelper.tableExists(table)
                }
            } catch (e: Exception) {
                DebugLogger.logException("Database", e)
                handleApplicationError(e)
            }
        }
    }

    private suspend fun migrateDatabaseIfNeeded() {
        withContext(Dispatchers.IO) {
            try {
                val currentVersion = dbHelper.getVersion()
                if (currentVersion < DatabaseHelper.VERSION) {
                    DebugLogger.info("Database", "Migrating from $currentVersion to ${DatabaseHelper.VERSION}")
                }
            } catch (e: Exception) {
                DebugLogger.logException("Database", e)
                handleApplicationError(e)
            }
        }
    }

    private fun checkEssentialTables() {
        try {
            val essential = listOf("users", "roles", "permissions", "role_permissions", "screens")
            val db = dbHelper.readableDatabase
            val cursor = db.query("sqlite_master", arrayOf("name"), "type='table' AND name IN (${essential.joinToString(",") { "'$it'" }})", null, null, null, null)
            val existing = mutableSetOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    existing.add(it.getString(0))
                }
            }
            for (table in essential) {
                if (table in existing) {
                    DebugLogger.info("Database", "TABLE $table EXISTS")
                } else {
                    DebugLogger.error("Database", "TABLE $table MISSING")
                }
            }
        } catch (e: Exception) {
            DebugLogger.logException("Database", e)
        }
    }

    // ============================================================
    // 3. تهيئة إعدادات SMS
    // ============================================================

    private suspend fun initializeSmsSettings() {
        withContext(Dispatchers.IO) {
            try {
                val defaultSettings = mapOf(
                    "sms_enabled" to "1",
                    "sms_security_mode" to "relaxed",
                    "public_price_query_enabled" to "0",
                    "sms_max_daily_messages" to "100",
                    "sms_rate_limit" to "10",
                    "sms_otp_enabled" to "1"
                )
                for ((key, defaultValue) in defaultSettings) {
                    val current = dbHelper.getSetting(key)
                    if (current.isEmpty()) {
                        dbHelper.setSetting(key, defaultValue)
                    }
                }
                loadSmsConfiguration()
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                handleApplicationError(e)
            }
        }
    }

    private fun loadSmsConfiguration() {
        try {
            dbHelper.getSetting("sms_enabled")
            dbHelper.getSetting("sms_security_mode")
            dbHelper.getSetting("sms_max_daily_messages")
            dbHelper.getSetting("sms_rate_limit")
            dbHelper.getSetting("sms_otp_enabled")
        } catch (e: Exception) {
            DebugLogger.warn("SMS", "loadSmsConfiguration error: ${e.message}")
        }
    }

    private fun saveSmsConfiguration(config: Map<String, String>) {
        try {
            for ((key, value) in config) {
                dbHelper.setSetting(key, value)
            }
        } catch (e: Exception) {
            DebugLogger.warn("SMS", "saveSmsConfiguration error: ${e.message}")
        }
    }

    // ============================================================
    // 4. إدارة الصلاحيات
    // ============================================================

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()

        if (!isPermissionGranted(Manifest.permission.SEND_SMS)) {
            permissions.add(Manifest.permission.SEND_SMS)
        }
        if (!isPermissionGranted(Manifest.permission.RECEIVE_SMS)) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }
        if (!isPermissionGranted(Manifest.permission.READ_SMS)) {
            permissions.add(Manifest.permission.READ_SMS)
        }
        val runtimePermissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE
        )
        runtimePermissions.forEach { permission ->
            if (!isPermissionGranted(permission)) {
                permissions.add(permission)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startSMSService()
        }
    }

    private fun checkSmsPermissions(): Boolean {
        return isPermissionGranted(Manifest.permission.SEND_SMS) &&
                isPermissionGranted(Manifest.permission.RECEIVE_SMS) &&
                isPermissionGranted(Manifest.permission.READ_SMS)
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != PERMISSION_REQUEST_CODE) return
        if (grantResults.isEmpty()) return

        val denied = permissions.zip(grantResults.toList())
            .filter { it.second != PackageManager.PERMISSION_GRANTED }
            .map { it.first }

        val criticalPermissions = listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        val hasCriticalDenied = denied.any { it in criticalPermissions }

        if (denied.isNotEmpty()) {
            Toast.makeText(
                this,
                if (hasCriticalDenied) {
                    "أذونات SMS الأساسية مفقودة؛ لن يعمل الرد التلقائي حتى تمنحها."
                } else {
                    "بعض الأذونات الاختيارية مفقودة، لكن نظام SMS سيستمر بالعمل."
                },
                Toast.LENGTH_LONG
            ).show()
            DebugLogger.warn("Permissions", "Permissions denied: $denied")
        }

        // لا تجعل أذونات الواجهة الاختيارية حاجزًا أمام تشغيل SMS.
        if (!hasCriticalDenied) {
            startSMSService()
        }
    }

    // ============================================================
    // 5. تشغيل وإيقاف خدمة SMS
    // ============================================================

    private fun startSMSService() {
        if (isDestroyed.get()) return
        if (isSMSServiceRunning()) return

        try {
            when (val result = smsServiceLauncher.launch(StartupReason.MANUAL)) {
                is ServiceLaunchResult.Success -> {
                    DebugLogger.logEvent("sms_service_started", result.message.orEmpty())
                    requestBatteryOptimizationExemption()
                }
                is ServiceLaunchResult.AlreadyRunning -> {
                    DebugLogger.logEvent("sms_service_already_running", result.message.orEmpty())
                }
                is ServiceLaunchResult.Failure -> {
                    Toast.makeText(this, "فشل في بدء خدمة SMS", Toast.LENGTH_SHORT).show()
                    DebugLogger.warn("SMS", result.error)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "فشل في بدء خدمة SMS", Toast.LENGTH_SHORT).show()
            DebugLogger.logException("SMS", e)
            handleApplicationError(e)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }.onFailure {
            DebugLogger.warn("Permissions", "Battery optimization request unavailable: ${it.message}")
        }
    }

    private fun stopSMSService() {
        try {
            val intent = Intent(this, SMSService::class.java)
            stopService(intent)
            DebugLogger.logEvent("sms_service_stopped", "Service stopped")
        } catch (e: Exception) {
            DebugLogger.warn("SMS", "stopSMSService error: ${e.message}")
        }
    }

    private fun restartSMSService() {
        stopSMSService()
        handler.postDelayed({
            if (!isDestroyed.get()) {
                startSMSService()
            }
        }, 1000)
    }

    private fun isSMSServiceRunning(): Boolean {
        return SMSService.getInstance()?.isServiceRunning() ?: false
    }

    // ============================================================
    // 6. إدارة إعدادات المستخدم
    // ============================================================

    fun getAllSettings(): Map<String, String> {
        return try {
            dbHelper.getAllSettingsMap()
        } catch (e: Exception) {
            DebugLogger.warn("Settings", "getAllSettings error: ${e.message}")
            emptyMap()
        }
    }

    // ============================================================
    // 7. مراقبة حالة النظام
    // ============================================================

    private fun getSmsSystemStatus(): JSONObject {
        return JSONObject().apply {
            put("enabled", dbHelper.getSetting("sms_enabled") == "1")
            put("service", isSMSServiceRunning())
            put("database", checkDatabaseHealth())
            put("permissions", checkSmsPermissions())
            put("last_check", System.currentTimeMillis())
        }
    }

    private fun checkSmsSystemHealth(): Boolean {
        return try {
            val dbOk = checkDatabaseHealth()
            val serviceOk = isSMSServiceRunning()
            val permOk = checkSmsPermissions()
            val result = dbOk && serviceOk && permOk
            if (!result) {
                DebugLogger.warn("Health", "db=$dbOk, service=$serviceOk, permissions=$permOk")
            }
            result
        } catch (e: Exception) {
            DebugLogger.logException("Health", e)
            false
        }
    }

    private fun runSmsDiagnostics(): String {
        return try {
            val status = getSmsSystemStatus()
            val diagnostics = JSONObject().apply {
                put("status", status)
                put("tables", dbHelper.getTableCounts())
                put("settings", dbHelper.getAllSettingsMap())
                put("version", getAppVersion())
                put("timestamp", System.currentTimeMillis())
            }
            diagnostics.toString(2)
        } catch (e: Exception) {
            "Error running diagnostics: ${e.message}"
        }
    }

    private fun checkDatabaseHealth(): Boolean {
        return try {
            dbHelper.getDatabaseSize() > 0
        } catch (e: Exception) {
            false
        }
    }

    // ============================================================
    // 8. تنظيف البيانات
    // ============================================================

    private suspend fun cleanupSmsDatabase() {
        withContext(Dispatchers.IO) {
            try {
                cleanupOldRateLimits()
                cleanupOldConversationContext()
                cleanupOldMetrics()
            } catch (e: Exception) {
                DebugLogger.warn("Cleanup", "cleanupSmsDatabase error: ${e.message}")
            }
        }
    }

    private fun cleanupOldRateLimits() {
        try {
            dbHelper.cleanupOldRateLimits()
        } catch (e: Exception) {
            DebugLogger.warn("Cleanup", "cleanupOldRateLimits error: ${e.message}")
        }
    }

    private fun cleanupOldConversationContext() {
        try {
            dbHelper.cleanupOldConversationContext(30)
        } catch (e: Exception) {
            DebugLogger.warn("Cleanup", "cleanupOldConversationContext error: ${e.message}")
        }
    }

    private fun cleanupOldMetrics() {
        try {
            dbHelper.cleanupOldMetrics(90)
        } catch (e: Exception) {
            DebugLogger.warn("Cleanup", "cleanupOldMetrics error: ${e.message}")
        }
    }

    // ============================================================
    // 9. المهام الخلفية
    // ============================================================

    private fun scheduleBackgroundTasks() {
        startPeriodicMaintenance()
    }

    private fun startPeriodicMaintenance() {
        maintenanceJob?.cancel()

        maintenanceJob = lifecycleScope.launch {
            while (!isDestroyed.get()) {
                delay(24 * 60 * 60 * 1000)
                try {
                    cleanupSmsDatabase()
                    DebugLogger.logEvent("periodic_maintenance", "Maintenance run completed")
                } catch (e: Exception) {
                    DebugLogger.warn("Maintenance", "Periodic maintenance error: ${e.message}")
                }
            }
        }
    }

    // ============================================================
    // 10. الإشعارات
    // ============================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "قناة إشعارات خدمة الرسائل النصية"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showSmsNotification(message: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notification = android.app.Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("محطة أبو أحمد")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .build()
                notificationManager.notify(System.currentTimeMillis().toInt(), notification)
            }
        } catch (e: Exception) {
            DebugLogger.warn("Notification", "showSmsNotification error: ${e.message}")
        }
    }

    // ============================================================
    // 11. واجهة التحكم
    // ============================================================

    private fun setupUI() { }
    private fun setupButtons() { }

    private fun updateUIState() { }

    // ============================================================
    // 12. تسجيل الأحداث والأخطاء
    // ============================================================

    private fun logApplicationEvent(event: String, details: String) {
        try {
            dbHelper.logActivity("system", event, details)
        } catch (e: Exception) {
            DebugLogger.warn("Log", "logApplicationEvent error: ${e.message}")
        }
    }

    private fun handleApplicationError(e: Exception) {
        DebugLogger.logException("Application", e)
        if (!isDestroyed.get()) {
            runOnUiThread {
                Toast.makeText(this, "حدث خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // 13. إدارة النسخة والتحديث
    // ============================================================

    private fun getAppVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun checkForUpdates() { }

    // ============================================================
    // 14. سلسلة التهيئة الأساسية
    // ============================================================

    private suspend fun initializeSystem() {
        try {
            initializeDatabase()
            initializeSmsSettings()
            DebugLogger.logEvent("app_started", "Application started")
            checkSmsSystemHealth()
        } catch (e: Exception) {
            DebugLogger.logException("Init", e)
            handleApplicationError(e)
        }
    }

    // ============================================================
    // دوال مساعدة (WebView، تحميل الأصول، إلخ)
    // ============================================================

    private fun loadWebViewFromAssets() {
        if (isDestroyed.get()) return
        val wv = webView ?: return

        try {
            if (wv.isAttachedToWindow) {
                DebugLogger.info("WebView", "Loading login.html")
                wv.loadUrl("file:///android_asset/screens/login.html")
            } else {
                handler.postDelayed({
                    if (!isDestroyed.get()) {
                        loadWebViewFromAssets()
                    }
                }, 500)
            }
        } catch (e: Exception) {
            DebugLogger.logException("WebView", e)
            showErrorPage()
        }
    }

    private fun showErrorPage() {
        if (isDestroyed.get() || isErrorPageShown) return
        isErrorPageShown = true
        val wv = webView ?: return

        val errorHtml = """
            <html dir="rtl">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>خطأ</title>
                <style>
                    body { font-family: 'Segoe UI', Tahoma, sans-serif; text-align: center; padding: 50px 20px; background: #f5f5f5; margin: 0; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
                    .error-box { background: white; padding: 30px; border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.1); max-width: 400px; margin: 0 auto; }
                    h1 { color: #d32f2f; font-size: 24px; margin-bottom: 12px; }
                    p { color: #666; line-height: 1.6; margin: 8px 0; }
                    .icon { font-size: 48px; margin-bottom: 16px; display: block; }
                    .btn-retry { background: #1976d2; color: white; border: none; padding: 12px 24px; border-radius: 8px; cursor: pointer; margin-top: 16px; font-size: 16px; transition: background 0.3s; }
                    .btn-retry:hover { background: #1565c0; }
                </style>
            </head>
            <body>
                <div class="error-box">
                    <span class="icon">⚠️</span>
                    <h1>حدث خطأ في تحميل الواجهة</h1>
                    <p>يرجى إعادة المحاولة</p>
                    <button class="btn-retry" onclick="window.location.reload()">🔄 إعادة المحاولة</button>
                </div>
            </body>
            </html>
        """.trimIndent()

        try {
            wv.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            DebugLogger.warn("WebView", "showErrorPage failed: ${e.message}")
        }
    }

    private fun loadEnvKey(key: String): String {
        return try {
            assets.open(".env").use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$key=")) {
                            val value = trimmed.substringAfter("=").trim()
                            if (value.isNotEmpty() && value != "YOUR_GEMINI_API_KEY_HERE") {
                                value
                            } else null
                        } else null
                    }.firstOrNull() ?: ""
                }
            }
        } catch (e: Exception) {
            DebugLogger.warn("Env", "loadEnvKey($key) failed: ${e.message}")
            ""
        }
    }

    private fun initEncryptedPrefs() {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            sharedPrefs = EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            DebugLogger.info("Prefs", "Encrypted prefs initialized")
        } catch (e: Exception) {
            sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            DebugLogger.warn("Prefs", "Encrypted prefs fallback: ${e.message}")
        }
    }

    // ============================================================
    // WebView و Compose
    // ============================================================

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun WebViewScreen(modifier: Modifier = Modifier) {
        DisposableEffect(Unit) {
            onDispose {
                // WebView يتم إدارته بواسطة Activity
            }
        }

        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val wv = if (this@MainActivity.webView == null) {
                        WebView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )

                            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                allowFileAccess = true
                                allowContentAccess = true
                                javaScriptCanOpenWindowsAutomatically = false
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                loadsImagesAutomatically = true
                                setSupportMultipleWindows(false)
                                userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                            }

                            webViewClient = createWebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                    val msg = consoleMessage.message()
                                    val line = consoleMessage.lineNumber()
                                    val source = consoleMessage.sourceId()
                                    Log.d("WebViewConsole", "$msg (source: $source, line: $line)")
                                    DebugLogger.info("WebViewConsole", "$msg")
                                    if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        DebugLogger.error("WebViewConsole", "JS Error: $msg at $source:$line")
                                    }
                                    return true
                                }
                            }

                            if (getTag(BRIDGE_INITIALIZED_TAG) != true) {
                                webAppInterface = WebAppInterface(context, this@MainActivity)
                                addJavascriptInterface(webAppInterface!!, "AndroidInterface")
                                setTag(BRIDGE_INITIALIZED_TAG, true)
                                DebugLogger.info("Bridge", "AndroidInterface injected")
                            }
                        }
                    } else {
                        this@MainActivity.webView!!
                    }

                    (wv.parent as? ViewGroup)?.removeView(wv)
                    addView(wv)
                    this@MainActivity.webView = wv
                    this@MainActivity.isWebViewInitialized = true

                    DebugLogger.attachWebView(wv)

                    val instanceId = ++webViewInstanceId
                    DebugLogger.info("WebView", "CREATED instance=$instanceId hash=${wv.hashCode()}")
                }
            },
            update = { }
        )
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                DebugLogger.info("WebView", "PAGE_STARTED: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isDestroyed.get()) return
                serverReady = true
                isErrorPageShown = false
                DebugLogger.info("WebView", "PAGE_FINISHED: $url")
                if (url?.endsWith("/main.html") == true || url?.endsWith("main.html") == true) {
                    val taskId = pendingNotificationTaskId
                    if (taskId != null) {
                        pendingNotificationTaskId = null
                        view?.postDelayed({
                            if (!isDestroyed.get()) {
                                view.loadUrl("file:///android_asset/screens/tasks.html?task_id=$taskId")
                            }
                        }, 250)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (handleCustomUrl(url)) {
                    DebugLogger.info("WebView", "URL_OVERRIDDEN: $url")
                    return true
                }
                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                if (handleCustomUrl(url)) {
                    DebugLogger.info("WebView", "URL_OVERRIDDEN: $url")
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (isDestroyed.get()) return
                val desc = error?.description?.toString() ?: "Unknown error"
                val code = error?.errorCode ?: -1
                DebugLogger.error("WebView", "RECEIVED_ERROR: code=$code, description=$desc, url=${request?.url}")
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (isDestroyed.get()) return
                DebugLogger.error("WebView", "RECEIVED_ERROR: code=$errorCode, description=$description, url=$failingUrl")
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                DebugLogger.warn("WebView", "SSL_ERROR: ${error?.primaryError}, url=${error?.url}")
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                DebugLogger.error("WebView", "RENDER_PROCESS_GONE: ${detail?.didCrash()}")
                view?.let { destroyWebView(it) }
                if (!isDestroyed.get()) {
                    webView = null
                    isWebViewInitialized = false
                    handler.postDelayed({
                        if (!isDestroyed.get()) {
                            recreateWebView()
                        }
                    }, 3000)
                }
                return true
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                DebugLogger.warn("WebView", "HTTP_ERROR: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} for ${request?.url}")
            }
        }
    }

    private fun recreateWebView() {
        if (isDestroyed.get()) return

        val wv = webView
        if (wv != null && wv.isAttachedToWindow) {
            wv.loadUrl("file:///android_asset/screens/login.html")
            DebugLogger.info("WebView", "RELOADED via existing WebView")
        } else {
            handler.postDelayed({
                if (!isDestroyed.get()) {
                    isErrorPageShown = false
                    loadWebViewFromAssets()
                }
            }, 500)
        }
    }

    private fun handleCustomUrl(url: String): Boolean {
        return when {
            url.startsWith("whatsapp://") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    Toast.makeText(this, "تطبيق واتساب غير مثبت", Toast.LENGTH_SHORT).show()
                    DebugLogger.warn("CustomUrl", "WhatsApp not installed")
                    false
                }
            }
            url.startsWith("fb://") || url.startsWith("facebook://") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    Toast.makeText(this, "تطبيق فيسبوك غير مثبت", Toast.LENGTH_SHORT).show()
                    DebugLogger.warn("CustomUrl", "Facebook not installed")
                    false
                }
            }
            url.startsWith("mailto:") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    Toast.makeText(this, "لا يوجد تطبيق بريد إلكتروني", Toast.LENGTH_SHORT).show()
                    DebugLogger.warn("CustomUrl", "Email app not found")
                    false
                }
            }
            url.startsWith("tel:") -> {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    DebugLogger.warn("CustomUrl", "Dial failed: ${e.message}")
                    false
                }
            }
            url.startsWith("http") && !url.contains("127.0.0.1") && !url.contains("localhost") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    DebugLogger.warn("CustomUrl", "External link failed: ${e.message}")
                    false
                }
            }
            else -> false
        }
    }

    private fun destroyWebView(webView: WebView?) {
        if (webView == null) return

        DebugLogger.info("WebView", "DESTROYED hash=${webView.hashCode()}")

        try {
            if (this.webView === webView) {
                this.webView = null
                isWebViewInitialized = false
            }

            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
            webView.removeJavascriptInterface("AndroidInterface")
            webView.removeAllViews()
            webView.destroy()
        } catch (e: Exception) {
            DebugLogger.warn("WebView", "destroyWebView error: ${e.message}")
        }
    }

    // ============================================================
    // المصادقة البيومترية – مع تسجيل إضافي
    // ============================================================

    fun showBiometricPrompt(onSuccess: () -> Unit, onError: (String) -> Unit) {
        DebugLogger.info("Biometric", "showBiometricPrompt START")
        try {
            Class.forName("androidx.biometric.BiometricPrompt")
        } catch (e: ClassNotFoundException) {
            DebugLogger.warn("Biometric", "BiometricPrompt not available")
            onError("unsupported")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            DebugLogger.warn("Biometric", "Android version < P")
            onError("unsupported")
            return
        }

        try {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = androidx.biometric.BiometricPrompt(
                this,
                executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: androidx.biometric.BiometricPrompt.AuthenticationResult
                    ) {
                        super.onAuthenticationSucceeded(result)
                        DebugLogger.info("Biometric", "Authentication SUCCEEDED")
                        onSuccess()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        DebugLogger.warn("Biometric", "Authentication FAILED")
                        onError("failed")
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        DebugLogger.warn("Biometric", "Authentication ERROR: code=$errorCode, message=$errString")
                        when (errorCode) {
                            androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED,
                            androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                                onError("cancelled")
                            }
                            else -> onError(errString.toString())
                        }
                    }
                }
            )

            val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(BIOMETRIC_TITLE)
                .setSubtitle(BIOMETRIC_SUBTITLE)
                .setNegativeButtonText(BIOMETRIC_CANCEL)
                .setConfirmationRequired(false)
                .build()

            DebugLogger.info("Biometric", "PromptInfo built, calling authenticate")
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            DebugLogger.logException("Biometric", e)
            onError("unsupported")
        }
    }

    // ============================================================
    // WebAppInterface - واجهة JavaScript الكاملة مع تسجيل محسّن
    // ============================================================

    @Keep
    inner class WebAppInterface(
        context: Context,
        activity: MainActivity
    ) {
        private val contextRef = WeakReference(context)
        private val activityRef = WeakReference(activity)
        private val dbHelperRef = WeakReference(dbHelper)
        private val geminiHelperRef = WeakReference(geminiHelper)
        private val settingsJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        private fun getDbHelper(): DatabaseHelper? = dbHelperRef.get()
        private fun getGeminiHelper(): GeminiAIHelper? = geminiHelperRef.get()
        private fun getActivity(): MainActivity? = activityRef.get()

        private fun checkPermission(
            permissionCode: String,
            action: String
        ): Boolean {
            val activity = getActivity() ?: return false
            val userId = activity.currentUserId
            if (userId == 0L) return false

            // DEV_MODE: تجاوز الصلاحيات محصور بالمستخدم admin رقم 1 ونسخ Debug.
            // لا يُستخدم هذا المسار في Release؛ بقية المستخدمين يمرون عبر SQLite كالمعتاد.
            if (activity.isDebugMode && userId == DEV_MODE_ADMIN_USER_ID) {
                DebugLogger.info("DEV_MODE", "Full permission bypass for development admin userId=$userId action=$permissionCode.$action")
                return true
            }

            val db = getDbHelper() ?: return false
            return db.checkUserPermission(userId, "$permissionCode.$action")
        }

        private fun getScreensForUser(
            activity: MainActivity,
            db: DatabaseHelper,
            userId: Long
        ): JSONArray {
            if (!activity.isDebugMode || userId != DEV_MODE_ADMIN_USER_ID) {
                return db.getUserScreens(userId)
            }

            // DEV_MODE: الشاشات المسجلة في SQLite + ملفات HTML الموجودة فعلياً في assets.
            val combined = db.getAllActiveScreens()
            val knownNames = mutableSetOf<String>()
            for (index in 0 until combined.length()) {
                combined.optJSONObject(index)?.optString("screen_name")?.let { knownNames.add(it) }
            }
            val assetScreens = db.getAvailableAssetScreens()
            for (index in 0 until assetScreens.length()) {
                val screen = assetScreens.optJSONObject(index) ?: continue
                val name = screen.optString("screen_name")
                if (name.isNotBlank() && knownNames.add(name)) combined.put(screen)
            }
            return combined
        }

        private fun getCurrentStationId(db: DatabaseHelper, userId: Long): Int {
            return db.getUserById(userId)
                ?.optInt("station_id", 1)
                ?.takeIf { it > 0 }
                ?: 1
        }

        private fun resolveCurrencyId(db: DatabaseHelper, requestedCurrencyId: Long): Long {
            if (requestedCurrencyId > 0) return requestedCurrencyId
            val currencies = db.getCurrencies()
            var firstId = 0L
            for (i in 0 until currencies.length()) {
                val currency = currencies.optJSONObject(i) ?: continue
                val id = currency.optLong("id", 0L)
                if (id > 0 && firstId == 0L) firstId = id
                if (currency.optInt("is_default", 0) == 1 && id > 0) return id
            }
            return if (firstId > 0) firstId else 1L
        }

        private fun successResponse(id: Long, message: String): String {
            return JSONObject().apply {
                put("success", true)
                put("id", id)
                put("message", message)
            }.toString()
        }

        private fun successResponse(success: Boolean, message: String): String {
            return JSONObject().apply {
                put("success", success)
                put("message", message)
            }.toString()
        }

        private fun errorResponse(error: String?): String {
            return JSONObject().apply {
                put("success", false)
                put("error", error ?: "خطأ غير معروف")
            }.toString()
        }

        private fun dataResponseObject(data: Any): JSONObject {
            return when (data) {
                is JSONObject -> data.put("success", true)
                is JSONArray -> JSONObject().apply { put("success", true); put("data", data) }
                else -> JSONObject().apply { put("success", true); put("data", data) }
            }
        }

        private fun dataResponse(data: Any): String {
            return when (data) {
                is JSONObject -> data.put("success", true).toString()
                is JSONArray -> JSONObject().apply {
                    put("success", true)
                    put("data", data)
                }.toString()
                else -> JSONObject().apply {
                    put("success", true)
                    put("data", data)
                }.toString()
            }
        }

        private fun applicationSettingsJson(settings: ApplicationSettings): JSONObject {
            val encoded = settingsJson.encodeToString(ApplicationSettings.serializer(), settings)
            return JSONObject(encoded)
        }

        private fun monitoringStateJson(state: com.aistudio.dieselstationsms.kxmpzq.settings.monitoring.SettingsMonitoringState): JSONObject {
            return JSONObject().apply {
                put("service_running", state.serviceRunning)
                put("service_healthy", state.serviceHealthy)
                put("current_startup_state", state.currentStartupState)
                put("active_phase", state.activePhase ?: JSONObject.NULL)
                put("completed_phases", JSONArray(state.completedPhases))
                put("failed_phases", JSONArray(state.failedPhases))
                put("events_count", state.eventsCount)
                put("metrics", JSONObject(state.metrics))
                put("logs", JSONArray(state.logs))
                put("last_error", state.lastError ?: JSONObject.NULL)
                put("uptime", state.uptime)
                put("memory_usage", state.memoryUsage)
                put("cpu_usage", state.cpuUsage)
                put("battery_level", state.batteryLevel)
            }
        }

        @JavascriptInterface
fun getDashboardStats(jsonData: String = "{}"): String {
    DebugLogger.info(
        "Dashboard",
        "getDashboardStats called: $jsonData"
    )

    val db = getDbHelper()
        ?: return errorResponse("قاعدة البيانات غير متاحة")

    return try {
        val params = try {
            JSONObject(
                jsonData.ifBlank { "{}" }
            )
        } catch (e: Exception) {
            DebugLogger.warn(
                "Dashboard",
                "Invalid JSON parameters; using defaults: ${e.message}"
            )
            JSONObject()
        }

        val stationId = params.optInt(
            "station_id",
            getCurrentStationId(db, getActivity()?.currentUserId ?: 0L)
        )

        if (stationId <= 0) {
            return errorResponse("معرف المحطة غير صالح")
        }

        DebugLogger.info(
            "Dashboard",
            "Loading dashboard statistics for stationId=$stationId"
        )

        val stats = db.getDashboardStats(stationId)

        JSONObject().apply {
            put("success", true)
            put("data", stats)
        }.toString()

    } catch (e: Exception) {
        DebugLogger.logException(
            "Dashboard",
            e
        )

        JSONObject().apply {
            put("success", false)
            put(
                "error",
                e.message ?: "فشل تحميل إحصائيات لوحة التحكم"
            )
            put(
                "code",
                "DASHBOARD_STATS_ERROR"
            )
        }.toString()
    }
}

        @JavascriptInterface
        fun screenExists(path: String): String {
            val normalizedPath = path.trim().removePrefix("/")
            if (!normalizedPath.startsWith("screens/") || normalizedPath.contains("..") || normalizedPath.contains('\\')) {
                return errorResponse("مسار الشاشة غير صالح")
            }
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                activity.assets.open(normalizedPath).use { }
                JSONObject().apply {
                    put("success", true)
                    put("data", true)
                    put("path", normalizedPath)
                }.toString()
            } catch (e: java.io.FileNotFoundException) {
                JSONObject().apply {
                    put("success", true)
                    put("data", false)
                    put("path", normalizedPath)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("ScreenExists", e)
                errorResponse(e.message ?: "تعذر التحقق من الشاشة")
            }
        }

        // ============================================================
        // 1. المصادقة – مع تسجيل محسن
        // ============================================================

        @JavascriptInterface
        fun login(username: String, password: String): String {
            val startTime = System.currentTimeMillis()
            DebugLogger.info("LOGIN", "LOGIN_REQUEST_RECEIVED username=$username")
            val activity = getActivity()
            DebugLogger.info("LOGIN", "activity=${activity?.hashCode()} webView=${activity?.webView?.hashCode()} attached=${activity?.webView?.isAttachedToWindow}")

            val db = getDbHelper()
            if (db == null) {
                DebugLogger.error("LOGIN", "DatabaseHelper is null")
                return errorResponse("قاعدة البيانات غير متاحة")
            }

            return try {
                DebugLogger.info("LOGIN", "AUTHENTICATE_USER_STARTED")
                val authResult = db.authenticateUser(username, password)
                if (authResult != null) {
                    DebugLogger.info("LOGIN", "AUTHENTICATION_RESULT success=true")
                    val userId = authResult.optLong("user_id", 0)
                    val isDevAdmin = activity?.isDebugMode == true && userId == DEV_MODE_ADMIN_USER_ID
                    val permissionsArray = if (isDevAdmin) {
                        // DEV_MODE: كل الصلاحيات المعرفة فعلياً في SQLite للمستخدم admin رقم 1.
                        db.getAllActivePermissions()
                    } else {
                        db.getUserPermissions(userId)
                    }
                    authResult.put("permissions", permissionsArray)

                    val screensArray = if (activity != null) {
                        getScreensForUser(activity, db, userId)
                    } else {
                        db.getUserScreens(userId)
                    }
                    authResult.put("screens", screensArray)
                    authResult.put("dev_mode_full_access", isDevAdmin)

                    // إعادة بناء الدور من users/roles بدلاً من الاعتماد على authResult المختصر.
                    val role = db.getUserById(userId)?.optString("role", "USER") ?: "USER"
                    authResult.put("role", role)
                    authResult.put("is_admin", role == "SUPER_ADMIN" || role == "ADMIN")

                    val token = UUID.randomUUID().toString()
                    activity?.let { act ->
                        act.currentAuthToken = token
                        act.currentUserId = userId
                        act.currentUserRole = role
                        act.currentUserName = authResult.optString("username", "")
                        DebugLogger.info("LOGIN", "Session updated for user ${authResult.optString("username")}")
                    }

                    val duration = System.currentTimeMillis() - startTime
                    DebugLogger.info("LOGIN", "SUCCESS duration=${duration}ms")

                    JSONObject().apply {
                        put("success", true)
                        put("user", authResult)
                        put("token", token)
                    }.toString()
                } else {
                    val duration = System.currentTimeMillis() - startTime
                    DebugLogger.warn("LOGIN", "AUTHENTICATION_RESULT success=false duration=${duration}ms")
                    errorResponse("بيانات خاطئة")
                }
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                DebugLogger.logException("LOGIN_EXCEPTION", e)
                errorResponse("خطأ داخلي: ${e.message}")
            }
        }

        @JavascriptInterface
        fun getCurrentUser(): String {
            DebugLogger.info("WebAppInterface", "getCurrentUser called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                // currentUserId محفوظ في auth_prefs، لذلك لا يعتمد هذا المسار على بقاء WebView نفسه.
                var userId = activity.currentUserId
                if (userId <= 0L) {
                    // استعادة آمنة من بيانات Remember-Me المحفوظة فقط؛ لا نثق ببيانات localStorage التجارية.
                    val prefs = activity.sharedPrefs
                    val remember = prefs.getBoolean("remember_me", false)
                    val savedUserId = prefs.getLong("saved_user_id", 0L)
                    val savedToken = prefs.getString("saved_token", "").orEmpty()
                    if (remember && savedUserId > 0L && savedToken.isNotBlank()) {
                        userId = savedUserId
                        activity.currentAuthToken = savedToken
                        activity.currentUserId = savedUserId
                        DebugLogger.info("CurrentUser", "Session restored from persisted Remember-Me state for user=$userId")
                    }
                }
                if (userId <= 0L) {
                    // استعادة أخيرة من auth_prefs نفسها إذا بقي token واسم المستخدم دون user_id.
                    val authToken = activity.currentAuthToken.orEmpty()
                    val persistedUsername = activity.currentUserName.trim()
                    if (authToken.isNotBlank() && persistedUsername.isNotBlank()) {
                        val persistedUser = db.getUserByUsername(persistedUsername)
                        val persistedId = persistedUser?.optLong("user_id", 0L) ?: 0L
                        if (persistedId > 0L) {
                            userId = persistedId
                            activity.currentUserId = persistedId
                            activity.currentUserRole = persistedUser?.optString("role", "") ?: ""
                            DebugLogger.info("CurrentUser", "Session restored from auth_prefs username=$persistedUsername user=$userId")
                        }
                    }
                }
                if (userId <= 0L) {
                    return errorResponse("لا توجد جلسة مستخدم")
                }

                val user = db.getUserById(userId) ?: return errorResponse("المستخدم غير موجود")
                val isDevAdmin = activity.isDebugMode && userId == DEV_MODE_ADMIN_USER_ID
                val permissions = if (isDevAdmin) {
                    // DEV_MODE: الصلاحيات الكاملة تُقرأ من جدول permissions الفعلي، ولا تُنشأ بيانات وهمية.
                    db.getAllActivePermissions()
                } else {
                    db.getUserPermissions(userId)
                }
                val screens = getScreensForUser(activity, db, userId)
                user.put("permissions", permissions)
                user.put("screens", screens)
                user.put("dev_mode_full_access", isDevAdmin)

                // عقد Typed ثابت: { success: true, data: {...} }.
                JSONObject().apply {
                    put("success", true)
                    put("data", user)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("CurrentUser", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun requestBiometricAuth(): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            DebugLogger.info("Biometric", "requestBiometricAuth called")
            activity.runOnUiThread {
                activity.showBiometricPrompt(
                    onSuccess = {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("message", "authenticated")
                        }
                        DebugLogger.info("Biometric", "Authentication success")
                        activity.safeEvaluateJs("window.onBiometricResult && window.onBiometricResult(${result})")
                    },
                    onError = { error ->
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", error)
                        }
                        DebugLogger.warn("Biometric", "Authentication error: $error")
                        activity.safeEvaluateJs("window.onBiometricResult && window.onBiometricResult(${result})")
                    }
                )
            }
            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        // ============================================================
        // دوال استعادة كلمة المرور – مع تسجيل محسن
        // ============================================================

        @JavascriptInterface
        fun resetPassword(token: String, newPassword: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (newPassword.length < 8 ||
                !newPassword.any { it.isUpperCase() } ||
                !newPassword.any { it.isLowerCase() } ||
                !newPassword.any { it.isDigit() }
            ) {
                return errorResponse("كلمة المرور يجب أن تحتوي على 8 أحرف، حرف كبير، حرف صغير، ورقم")
            }
            DebugLogger.info("ResetPassword", "Attempt with one-time token")
            return try {
                val userData = db.validateResetToken(token)
                if (userData == null) {
                    DebugLogger.warn("ResetPassword", "Invalid or expired token")
                    return errorResponse("رابط الاستعادة غير صالح أو منتهي الصلاحية")
                }

                val userId = userData.optLong("user_id", 0L)
                if (userId == 0L) {
                    return errorResponse("معرف المستخدم غير صالح")
                }

                val updated = db.updateUserPassword(userId, newPassword)
                if (!updated) {
                    return errorResponse("فشل تحديث كلمة المرور")
                }

                db.clearResetToken(token)
                DebugLogger.info("ResetPassword", "Password updated for user ${userData.optString("username")}")
                successResponse(true, "تم تحديث كلمة المرور بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("ResetPassword", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun requestPasswordResetSms(username: String, phone: String): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                return errorResponse("إذن إرسال SMS غير مفعل")
            }
            val cleanUsername = username.trim()
            val normalizedPhone = PhoneUtils.normalize(phone)
                ?: return errorResponse("رقم الهاتف غير صالح")
            if (cleanUsername.isBlank()) return errorResponse("اسم المستخدم مطلوب")

            return try {
                val user = db.getUserRecoveryContact(cleanUsername)
                    ?: return errorResponse("بيانات الاستعادة غير صحيحة")
                val savedPhone = user.optString("phone")
                if (!PhoneUtils.isSameNumber(savedPhone, normalizedPhone)) {
                    return errorResponse("بيانات الاستعادة غير صحيحة")
                }
                val userId = user.optLong("user_id", 0L)
                if (userId <= 0L) return errorResponse("معرف المستخدم غير صالح")

                val otp = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
                if (!db.storeUserOtp(userId, otp, 300)) {
                    return errorResponse("تم إرسال رمز حديث مؤخراً؛ يرجى الانتظار دقيقة قبل إعادة الطلب")
                }
                val smsBody = "محطة أبو أحمد: رمز استعادة كلمة المرور هو $otp. صالح لمدة 5 دقائق. لا تشاركه مع أي شخص."
                val enqueue = SmsOutboxRepository.enqueue(
                    db = db,
                    recipient = normalizedPhone,
                    body = smsBody,
                    eventId = "password-reset-sms",
                    businessEntityId = userId.toString(),
                    priority = SmsBudgetManager.Priority.HIGH,
                    dedupeKey = "password-reset-sms:$userId:${System.currentTimeMillis() / 60_000L}"
                )
                if (enqueue == null) {
                    db.clearOtpCode(userId)
                    return errorResponse("تعذر وضع رسالة الاستعادة في قائمة الإرسال")
                }
                SmsOutboxWorker.schedule(activity.applicationContext)
                JSONObject().apply {
                    put("success", true)
                    put("status", enqueue.status)
                    put("message_id", enqueue.messageId)
                    put("expires_in_seconds", 300)
                    put("phone_masked", normalizedPhone.take(3) + "****" + normalizedPhone.takeLast(2))
                    put("message", "تم وضع رمز التحقق في قائمة إرسال SMS؛ صالح لمدة 5 دقائق")
                }.toString()
            } catch (e: SecurityException) {
                DebugLogger.logException("PasswordResetSmsPermission", e)
                errorResponse("إذن إرسال SMS غير مفعل")
            } catch (e: Exception) {
                DebugLogger.logException("PasswordResetSms", e)
                errorResponse("تعذر إنشاء طلب استعادة كلمة المرور")
            }
        }

        @JavascriptInterface
        fun verifyResetCode(phone: String, code: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (!code.matches(Regex("\\d{6}"))) return errorResponse("رمز التحقق يجب أن يتكون من 6 أرقام")
            return try {
                val user = db.getUserRecoveryContactByPhone(phone)
                    ?: return errorResponse("الرمز غير صحيح أو منتهي الصلاحية")
                val userId = user.optLong("user_id", 0L)
                if (userId <= 0L || !db.validateOtpCode(userId, code)) {
                    return errorResponse("الرمز غير صحيح أو منتهي الصلاحية")
                }
                val resetToken = UUID.randomUUID().toString()
                if (!db.storeResetToken(userId, resetToken, 10)) {
                    return errorResponse("تعذر إنشاء جلسة تغيير كلمة المرور")
                }
                JSONObject().apply {
                    put("success", true)
                    put("reset_token", resetToken)
                    put("expires_in_seconds", 600)
                    put("username", user.optString("username"))
                    put("message", "تم التحقق بنجاح؛ يمكنك الآن اختيار كلمة مرور جديدة")
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("VerifyCode", e)
                errorResponse("تعذر التحقق من رمز الاستعادة")
            }
        }

        @JavascriptInterface
        fun getUserData(username: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            DebugLogger.info("GetUserData", "Username: $username")
            return try {
                val user = db.getUserByUsername(username)
                if (user == null) {
                    DebugLogger.warn("GetUserData", "User not found: $username")
                    return errorResponse("المستخدم غير موجود")
                }
                dataResponse(user)
            } catch (e: Exception) {
                DebugLogger.logException("GetUserData", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 2. الذكاء الاصطناعي (Gemini)
        // ============================================================

        @JavascriptInterface
        fun getGeminiApiKey(): String {
            return if (geminiApiKey.isNotEmpty()) "configured" else "not_configured"
        }

        @JavascriptInterface
        fun sendToAI(message: String): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            if (geminiApiKey.isEmpty()) {
                return errorResponse("مفتاح Gemini API غير مُهيأ")
            }
            DebugLogger.info("AI", "sendToAI: $message")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val response = geminiHelper.sendMessage(message)
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("response", response)
                        }
                        activity.safeEvaluateJs("window.onAIResponse && window.onAIResponse(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onAIResponse && window.onAIResponse(${result})")
                    }
                    DebugLogger.logException("AI", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun getAIResponse(message: String): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            if (geminiApiKey.isEmpty()) {
                return errorResponse("مفتاح Gemini API غير مُهيأ")
            }
            DebugLogger.info("AI", "getAIResponse: $message")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val response = geminiHelper.sendMessageSync(message)
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("response", response)
                        }
                        activity.safeEvaluateJs("window.onAIResponse && window.onAIResponse(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onAIResponse && window.onAIResponse(${result})")
                    }
                    DebugLogger.logException("AI", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun getAIInsight(): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (!checkPermission("ai", "read")) return errorResponse("لا تملك صلاحية الوصول للذكاء الاصطناعي")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val stats = db.getDashboardStats(1)
                    val prompt = """
                        أنت مساعد ذكي لمحطة وقود. قدم تحليلاً مختصراً للبيانات التالية:
                        - المخزون المتبقي: ${stats.optDouble("total_remaining", 0.0).toInt()} لتر
                        - الديون المستحقة: ${stats.optDouble("total_due", 0.0).toInt()} ريال
                        - مبيعات اليوم: ${stats.optDouble("total_sales", 0.0).toInt()} ريال
                        - عدد العملاء: ${stats.optInt("total_customers", 0)}
                        قدم توصية واحدة عملية مختصرة (سطرين فقط).
                    """.trimIndent()
                    val insight = geminiHelper.sendMessageSync(prompt)
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("insight", insight)
                        }
                        activity.safeEvaluateJs("window.onAIInsight && window.onAIInsight(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onAIInsight && window.onAIInsight(${result})")
                    }
                    DebugLogger.logException("AI", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        // ============================================================
        // 3. الأطراف (العملاء، الموردين، السائقين)
        // ============================================================

        @JavascriptInterface
        fun addParty(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addParty called")
            if (!checkPermission("parties", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.insertParty(data)
                DebugLogger.info("Party", "Added party id=$id")
                successResponse(id, "تمت الإضافة بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateParty(id: Long, jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateParty called")
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateParty(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteParty(id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteParty called")
            if (!checkPermission("parties", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteParty(id)
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun archiveParty(id: Long): String {
            DebugLogger.info("WebAppInterface", "archiveParty called")
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية الأرشفة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.archiveParty(id)
                successResponse(rows > 0, if (rows > 0) "تم الأرشفة بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getParties(type: String?): String {
            DebugLogger.info("WebAppInterface", "getParties called")
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val parties = db.getParties(type ?: "")
                dataResponse(parties)
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomers(): String = getParties("customer")
        @JavascriptInterface
        fun getSuppliers(): String = getParties("supplier")
        @JavascriptInterface
        fun getDrivers(): String = getParties("driver")

        @JavascriptInterface
        fun searchParties(query: String): String {
            DebugLogger.info("WebAppInterface", "searchParties called")
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val results = db.searchParties(query)
                dataResponse(results)
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPartyById(id: Long): String {
            DebugLogger.info("WebAppInterface", "getPartyById called")
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val party = db.getPartyById(id)
                party?.toString() ?: errorResponse("العميل غير موجود")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 4. الطلبات
        // ============================================================

        @JavascriptInterface
        fun addOrder(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addOrder called")
            if (!checkPermission("orders", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addOrder(data)
                DebugLogger.info("Order", "Added order id=$id")
                successResponse(id, "تم إضافة الطلب بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Order", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getOrders(status: String?): String {
            DebugLogger.info("WebAppInterface", "getOrders called")
            if (!checkPermission("orders", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val orders = db.getOrders(status)
                dataResponse(orders)
            } catch (e: Exception) {
                DebugLogger.logException("Order", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPendingOrders(): String = getOrders("pending")

        // ============================================================
        // 5. التوصيلات
        // ============================================================

        @JavascriptInterface
        fun addDelivery(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addDelivery called")
            if (!checkPermission("deliveries", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addDelivery(data)
                DebugLogger.info("Delivery", "Added delivery id=$id")
                successResponse(id, "تم إضافة التسليم بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Delivery", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getDeliveries(): String {
            DebugLogger.info("WebAppInterface", "getDeliveries called")
            if (!checkPermission("deliveries", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val deliveries = db.getDeliveries()
                dataResponse(deliveries)
            } catch (e: Exception) {
                DebugLogger.logException("Delivery", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTodayDeliveries(): String {
            DebugLogger.info("WebAppInterface", "getTodayDeliveries called")
            if (!checkPermission("deliveries", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val deliveries = db.getTodayDeliveries()
                dataResponse(deliveries)
            } catch (e: Exception) {
                DebugLogger.logException("Delivery", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 6. المبيعات
        // ============================================================

        @JavascriptInterface
        fun addSale(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addSale called")
            if (!checkPermission("sales", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addFuelSale(data)
                DebugLogger.info("Sale", "Added sale id=$id")
                successResponse(id, "تم إضافة البيع بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Sale", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun completeSale(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "completeSale called")
            if (!checkPermission("sales", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val result = db.completeSale(JSONObject(jsonData))
                dataResponse(result)
            } catch (e: Exception) {
                DebugLogger.logException("Sale", e)
                errorResponse(e.message)
            }
        }


        @JavascriptInterface
        fun getNextInvoiceNumber(): String {
            if (!checkPermission("sales", "create")) return errorResponse("لا تملك صلاحية إنشاء فاتورة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getNextInvoiceNumber()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun searchInvoices(jsonData: String): String {
            if (!checkPermission("sales", "read")) return errorResponse("لا تملك صلاحية قراءة الفواتير")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.searchInvoices(JSONObject(jsonData.ifBlank { "{}" }))) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun retrieveInvoice(invoiceNumber: String): String {
            if (!checkPermission("sales", "read")) return errorResponse("لا تملك صلاحية قراءة الفاتورة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { db.getInvoiceDetails(invoiceNumber)?.let { dataResponse(it) } ?: errorResponse("الفاتورة غير موجودة") }
            catch (e: Exception) { DebugLogger.logException("Invoice", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun searchSales(jsonData: String): String {
            if (!checkPermission("sales", "read")) return errorResponse("لا تملك صلاحية قراءة المبيعات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.searchSaleItems(JSONObject(jsonData.ifBlank { "{}" }))) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun salesReport(): String = searchSales("{}")

        @JavascriptInterface
        fun processSaleReturn(jsonData: String): String {
            if (!checkPermission("sales", "update")) return errorResponse("لا تملك صلاحية معالجة المرتجعات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.processSaleReturn(JSONObject(jsonData))) } catch (e: Exception) { DebugLogger.logException("SaleReturn", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getReturns(jsonData: String): String {
            if (!checkPermission("sales", "read")) return errorResponse("لا تملك صلاحية قراءة المرتجعات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getReturns(JSONObject(jsonData.ifBlank { "{}" }))) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun addReturn(jsonData: String): String = processSaleReturn(jsonData)

        @JavascriptInterface
        fun updateReturn(id: Long, jsonData: String): String {
            if (!checkPermission("sales", "update")) return errorResponse("لا تملك صلاحية تحديث المرتجعات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.updateReturn(id, JSONObject(jsonData)); successResponse(rows > 0, if (rows > 0) "تم تحديث المرتجع" else "المرتجع غير موجود") }
            catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun deleteReturn(id: Long): String {
            if (!checkPermission("sales", "delete")) return errorResponse("لا تملك صلاحية حذف المرتجعات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.deleteReturn(id); successResponse(rows > 0, if (rows > 0) "تم عكس المرتجع فعلياً" else "المرتجع غير موجود") }
            catch (e: Exception) { DebugLogger.logException("SaleReturn", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getEntityTypes(): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية قراءة أنواع الأطراف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val result = JSONArray()
                db.getPartyTypes().let { array -> for (i in 0 until array.length()) { val item = array.getJSONObject(i); result.put(JSONObject().apply { put("type_id", item.optLong("id")); put("type_name", item.optString("type_name_ar", item.optString("type_name"))) }) } }
                dataResponse(result)
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getEntitiesByType(typeId: Long): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية قراءة الأطراف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getPartiesByType(typeId)) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getEntityDetails(id: Long): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية قراءة الطرف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { db.getPartyById(id)?.let { dataResponse(it) } ?: errorResponse("الطرف غير موجود") }
            catch (e: Exception) { DebugLogger.logException("PartyDetails", e); errorResponse(e.message) }
        }


        @JavascriptInterface
        fun getSales(): String {
            DebugLogger.info("WebAppInterface", "getSales called")
            if (!checkPermission("sales", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getSales()
                dataResponse(sales)
            } catch (e: Exception) {
                DebugLogger.logException("Sale", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTodaySales(): String {
            DebugLogger.info("WebAppInterface", "getTodaySales called")
            if (!checkPermission("sales", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getTodaySales()
                dataResponse(sales)
            } catch (e: Exception) {
                DebugLogger.logException("Sale", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteSale(saleId: Long): String {
            DebugLogger.info("WebAppInterface", "deleteSale called")
            if (!checkPermission("sales", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val dbWritable = db.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("sales_transactions", cv, "id=?", arrayOf(saleId.toString()))
                if (rows > 0) db.logActivity("system", "delete_sale", "حذف مبيعة $saleId")
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Sale", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 7. الحركات النقدية
        // ============================================================

        @JavascriptInterface
        fun addCashMovement(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addCashMovement called")
            if (!checkPermission("cash", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addCashMovement(data)
                DebugLogger.info("Cash", "Added cash movement id=$id")
                successResponse(id, "تم إضافة الحركة المالية بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Cash", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCashMovements(): String {
            DebugLogger.info("WebAppInterface", "getCashMovements called")
            if (!checkPermission("cash", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val movements = db.getCashMovements()
                dataResponse(movements)
            } catch (e: Exception) {
                DebugLogger.logException("Cash", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTodayCash(): String {
            DebugLogger.info("WebAppInterface", "getTodayCash called")
            if (!checkPermission("cash", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val cash = db.getTodayCash()
                dataResponse(cash)
            } catch (e: Exception) {
                DebugLogger.logException("Cash", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 8. قراءات العدادات والخزانات
        // ============================================================

        @JavascriptInterface
        fun addMeterReading(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addMeterReading called")
            if (!checkPermission("meter", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addMeterReading(data)
                DebugLogger.info("Meter", "Added meter reading id=$id")
                successResponse(id, "تم إضافة قراءة العداد بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Meter", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getMeterReadings(): String {
            DebugLogger.info("WebAppInterface", "getMeterReadings called")
            if (!checkPermission("meter", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val readings = db.getMeterReadings()
                dataResponse(readings)
            } catch (e: Exception) {
                DebugLogger.logException("Meter", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addTankReading(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addTankReading called")
            if (!checkPermission("tanks", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addTankReading(data)
                DebugLogger.info("Tank", "Added tank reading id=$id")
                successResponse(id, "تم إضافة قراءة الخزان بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Tank", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTankReadings(): String {
            DebugLogger.info("WebAppInterface", "getTankReadings called")
            if (!checkPermission("tanks", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val readings = db.getTankReadings()
                dataResponse(readings)
            } catch (e: Exception) {
                DebugLogger.logException("Tank", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 9. المخزون
        // ============================================================

        @JavascriptInterface
        fun addStockMovement(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addStockMovement called")
            if (!checkPermission("stock", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val data = JSONObject(jsonData).apply {
                    put("performed_by", activity.currentUserId)
                    put("created_by", activity.currentUserId)
                    put("station_id", getCurrentStationId(db, activity.currentUserId))
                }
                val id = db.addStockMovement(data)
                DebugLogger.info("Stock", "Added stock movement id=$id")
                successResponse(id, "تم إضافة حركة المخزون بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Stock", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun transferStockMovement(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "transferStockMovement called")
            if (!checkPermission("stock", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val data = JSONObject(jsonData).apply {
                    put("performed_by", activity.currentUserId)
                    put("station_id", getCurrentStationId(db, activity.currentUserId))
                }
                val id = db.transferStockMovement(data)
                successResponse(id, "تم تنفيذ التحويل الذري بين المستودعين")
            } catch (e: Exception) {
                DebugLogger.logException("StockTransfer", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getStockMovements(jsonData: String = "{}"): String {
            DebugLogger.info("WebAppInterface", "getStockMovements called")
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val movements = db.getStockMovements(JSONObject(jsonData.ifBlank { "{}" }))
                dataResponse(movements)
            } catch (e: Exception) {
                DebugLogger.logException("Stock", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun generateInventoryReport(jsonData: String = "{}"): String = getInventoryReport(jsonData)

        @JavascriptInterface
        fun archiveStockMovement(movementId: Long): String {
            DebugLogger.info("WebAppInterface", "archiveStockMovement called")
            if (!checkPermission("stock", "delete")) return errorResponse("لا تملك صلاحية الأرشفة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val rows = db.archiveStockMovement(movementId, activity.currentUserId)
                successResponse(rows > 0, if (rows > 0) "تمت أرشفة الحركة" else "لم يتم العثور على الحركة")
            } catch (e: Exception) { DebugLogger.logException("StockArchive", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getInventoryMovementStats(jsonData: String = "{}"): String {
            DebugLogger.info("WebAppInterface", "getInventoryMovementStats called")
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getInventoryMovementStats(JSONObject(jsonData.ifBlank { "{}" }))) }
            catch (e: Exception) { DebugLogger.logException("StockStats", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getInventoryReport(jsonData: String = "{}"): String {
            DebugLogger.info("WebAppInterface", "getInventoryReport called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getInventoryReport(JSONObject(jsonData.ifBlank { "{}" }))) }
            catch (e: Exception) { DebugLogger.logException("InventoryReport", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getInventoryProductDetails(productId: Long): String {
            DebugLogger.info("WebAppInterface", "getInventoryProductDetails called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            if (productId <= 0L) return errorResponse("معرّف المنتج غير صالح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { db.getInventoryProductDetails(productId)?.let { dataResponse(it) } ?: errorResponse("المنتج غير موجود") }
            catch (e: Exception) { DebugLogger.logException("InventoryProductDetails", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getProductMovementTrend(productId: Long, days: Int = 30): String {
            DebugLogger.info("WebAppInterface", "getProductMovementTrend called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            if (productId <= 0L) return errorResponse("معرّف المنتج غير صالح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getProductMovementTrend(productId, days)) }
            catch (e: Exception) { DebugLogger.logException("InventoryTrend", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getLowStockItems(): String {
            DebugLogger.info("WebAppInterface", "getLowStockItems called")
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val items = db.getLowStockItems()
                dataResponse(items)
            } catch (e: Exception) {
                DebugLogger.logException("Stock", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 10. المنتجات التالفة والمستودعات
        // ============================================================

        @JavascriptInterface
        fun getWarehouses(jsonData: String = "{}"): String {
            DebugLogger.info("WebAppInterface", "getWarehouses called")
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData.ifBlank { "{}" })
                val stationId = data.optInt("station_id", getCurrentStationId(db, getActivity()?.currentUserId ?: 0L))
                dataResponse(db.getWarehouses(stationId.takeIf { it > 0 }))
            } catch (e: Exception) {
                DebugLogger.logException("Warehouse", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getDamagedProducts(jsonData: String = "{}"): String {
            DebugLogger.info("WebAppInterface", "getDamagedProducts called")
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
                val data = JSONObject(jsonData.ifBlank { "{}" }).apply {
                    if (optInt("station_id", 0) <= 0) put("station_id", getCurrentStationId(db, activity.currentUserId))
                }
                dataResponse(db.getDamagedProducts(data))
            } catch (e: Exception) {
                DebugLogger.logException("DamagedProduct", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addDamagedProduct(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addDamagedProduct called")
            if (!checkPermission("stock", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
                val data = JSONObject(jsonData).apply {
                    if (optLong("reported_by", 0L) <= 0L) put("reported_by", activity.currentUserId)
                    if (optInt("station_id", 0) <= 0) put("station_id", getCurrentStationId(db, activity.currentUserId))
                }
                val id = db.addDamagedProduct(data)
                DebugLogger.info("DamagedProduct", "Added damaged product id=$id")
                successResponse(id, "تم إضافة سجل التالف بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("DamagedProduct", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateDamagedProduct(id: Long, jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateDamagedProduct called id=$id")
            if (!checkPermission("stock", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.updateDamagedProduct(id, JSONObject(jsonData))
                successResponse(rows > 0, if (rows > 0) "تم تحديث سجل التالف بنجاح" else "لم يتم العثور على سجل التالف")
            } catch (e: Exception) {
                DebugLogger.logException("DamagedProduct", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateDamagedProductStatus(id: Long, status: String): String {
            DebugLogger.info("WebAppInterface", "updateDamagedProductStatus called id=$id status=$status")
            if (!checkPermission("stock", "update")) return errorResponse("لا تملك صلاحية اعتماد التالف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
                val rows = db.updateDamagedProductStatus(id, status, activity.currentUserId)
                successResponse(rows > 0, if (rows > 0) "تم تحديث حالة سجل التالف بنجاح" else "لم يتم العثور على سجل التالف")
            } catch (e: Exception) {
                DebugLogger.logException("DamagedProduct", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun archiveDamagedProduct(id: Long): String {
            DebugLogger.info("WebAppInterface", "archiveDamagedProduct called id=$id")
            if (!checkPermission("stock", "delete")) return errorResponse("لا تملك صلاحية الأرشفة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.archiveDamagedProduct(id)
                successResponse(rows > 0, if (rows > 0) "تمت أرشفة سجل التالف" else "لم يتم العثور على سجل التالف")
            } catch (e: Exception) {
                DebugLogger.logException("DamagedProduct", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteDamagedProduct(id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteDamagedProduct called id=$id")
            if (!checkPermission("stock", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteDamagedProduct(id)
                successResponse(rows > 0, if (rows > 0) "تمت أرشفة سجل التالف" else "لم يتم العثور على سجل التالف")
            } catch (e: Exception) {
                DebugLogger.logException("DamagedProduct", e)
                errorResponse(e.message)
            }
        }



        // ============================================================
        // Typed IAM contracts — backed by the existing SQLite schema
        // ============================================================
        @JavascriptInterface
        fun getStations(): String {
            if (!checkPermission("users", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getStations()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getRoles(): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getRoles()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getGroups(): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getGroups()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun addGroup(jsonData: String): String {
            if (!checkPermission("permissions", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { successResponse(db.addGroup(JSONObject(jsonData)), "تم إنشاء المجموعة") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun updateGroup(id: Long, jsonData: String): String {
            if (!checkPermission("permissions", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.updateGroup(id, JSONObject(jsonData)); successResponse(rows > 0, if (rows > 0) "تم تحديث المجموعة" else "لم يتم العثور على المجموعة") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun deleteGroup(id: Long): String {
            if (!checkPermission("permissions", "delete")) return errorResponse("لا تملك صلاحية الأرشفة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.deleteGroup(id); successResponse(rows > 0, if (rows > 0) "تمت أرشفة المجموعة" else "لم يتم العثور على المجموعة") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getPermissions(): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getPermissions()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun addPermission(jsonData: String): String {
            if (!checkPermission("permissions", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { successResponse(db.addPermission(JSONObject(jsonData)), "تم إنشاء الصلاحية") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun updatePermission(id: Long, jsonData: String): String {
            if (!checkPermission("permissions", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.updatePermission(id, JSONObject(jsonData)); successResponse(rows > 0, if (rows > 0) "تم تحديث الصلاحية" else "لم يتم العثور على الصلاحية") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun deletePermission(id: Long): String {
            if (!checkPermission("permissions", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.deletePermission(id); successResponse(rows > 0, if (rows > 0) "تمت أرشفة الصلاحية" else "لم يتم العثور على الصلاحية") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getScreens(): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getScreens()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getModules(): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getModules()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun addScreen(jsonData: String): String {
            if (!checkPermission("permissions", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { successResponse(db.addScreen(JSONObject(jsonData)), "تم إنشاء الشاشة") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun updateScreen(id: Long, jsonData: String): String {
            if (!checkPermission("permissions", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.updateScreen(id, JSONObject(jsonData)); successResponse(rows > 0, if (rows > 0) "تم تحديث الشاشة" else "لم يتم العثور على الشاشة") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun deleteScreen(id: Long): String {
            if (!checkPermission("permissions", "delete")) return errorResponse("لا تملك صلاحية الأرشفة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.deleteScreen(id); successResponse(rows > 0, if (rows > 0) "تمت أرشفة الشاشة" else "لم يتم العثور على الشاشة") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getScreenPermissions(screenId: Long): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getScreenPermissions(screenId)) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun grantUserPermission(jsonData: String): String {
            if (!checkPermission("permissions", "create")) return errorResponse("لا تملك صلاحية المنح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                if (data.optLong("granted_by", 0L) <= 0L) data.put("granted_by", getActivity()?.currentUserId ?: 0L)
                successResponse(db.grantUserPermission(data), "تم منح الصلاحية")
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getGrantedPermissions(): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getGrantedPermissions()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun revokeUserPermission(id: Long): String {
            if (!checkPermission("permissions", "delete")) return errorResponse("لا تملك صلاحية السحب")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.revokeUserPermission(id); successResponse(rows > 0, if (rows > 0) "تم سحب الصلاحية" else "لم يتم العثور على الصلاحية") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getUserSessions(userId: Long): String {
            if (!checkPermission("users", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getUserSessions(userId)) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun terminateSession(sessionId: Long): String {
            if (!checkPermission("users", "update")) return errorResponse("لا تملك صلاحية إنهاء الجلسة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.terminateSession(sessionId); successResponse(rows > 0, if (rows > 0) "تم إنهاء الجلسة" else "لم يتم العثور على الجلسة") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getUserActivityLog(jsonData: String): String {
            if (!checkPermission("users", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getUserActivityLog(JSONObject(jsonData.ifBlank { "{}" }))) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getDelegatedPermissions(): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getDelegatedPermissions()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun grantDelegatedPermission(jsonData: String): String {
            if (!checkPermission("permissions", "create")) return errorResponse("لا تملك صلاحية التفويض")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val actorId = getActivity()?.currentUserId ?: 0L
                if (actorId <= 0L) return errorResponse("جلسة المستخدم غير متاحة")
                data.put("delegator_id", actorId)
                successResponse(db.grantDelegatedPermission(data), "تم منح التفويض المؤقت بنجاح")
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun revokeDelegatedPermission(id: Long): String {
            if (!checkPermission("permissions", "delete")) return errorResponse("لا تملك صلاحية إلغاء التفويض")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.revokeDelegatedPermission(id); successResponse(rows > 0, if (rows > 0) "تم إلغاء التفويض" else "لم يتم العثور على التفويض") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getGroupPermissions(): String {
            if (!checkPermission("permissions", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getGroupPermissions()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun grantGroupPermission(jsonData: String): String {
            if (!checkPermission("permissions", "create")) return errorResponse("لا تملك صلاحية ربط المجموعة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                successResponse(db.grantGroupPermission(data), "تم ربط الصلاحية بالمجموعة بنجاح")
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun revokeGroupPermission(id: Long): String {
            if (!checkPermission("permissions", "delete")) return errorResponse("لا تملك صلاحية إلغاء الربط")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.revokeGroupPermission(id); successResponse(rows > 0, if (rows > 0) "تم إلغاء الربط" else "لم يتم العثور على الربط") } catch (e: Exception) { errorResponse(e.message) }
        }

        // ============================================================
        // 11. الأصول
        // ============================================================

        @JavascriptInterface
        fun addAsset(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addAsset called")
            if (!checkPermission("assets", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addAsset(data)
                DebugLogger.info("Asset", "Added asset id=$id")
                successResponse(id, "تم إضافة الأصل بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Asset", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getAssets(): String {
            DebugLogger.info("WebAppInterface", "getAssets called")
            if (!checkPermission("assets", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val assets = db.getAssets()
                dataResponse(assets)
            } catch (e: Exception) {
                DebugLogger.logException("Asset", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 11. المستخدمين والموظفين
        // ============================================================

        @JavascriptInterface
        fun addUser(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addUser called")
            if (!checkPermission("users", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val actorId = getActivity()?.currentUserId ?: 0L
                if (actorId > 0L) data.put("created_by", actorId)
                val id = db.addUser(data)
                DebugLogger.info("User", "Added user id=$id")
                successResponse(id, "تم إضافة المستخدم بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("User", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUsers(): String {
            DebugLogger.info("WebAppInterface", "getUsers called")
            if (!checkPermission("users", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val users = db.getUsers()
                dataResponse(users)
            } catch (e: Exception) {
                DebugLogger.logException("User", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUsersByRole(role: String): String {
            DebugLogger.info("WebAppInterface", "getUsersByRole called")
            if (!checkPermission("users", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val users = db.getUsersByRole(role)
                dataResponse(users)
            } catch (e: Exception) {
                DebugLogger.logException("User", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateUser(id: Long, jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateUser called")
            if (!checkPermission("users", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val actorId = getActivity()?.currentUserId ?: 0L
                if (actorId > 0L) data.put("updated_by", actorId)
                val rows = db.updateUser(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("User", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteUser(id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteUser called")
            if (!checkPermission("users", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteUser(id)
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("User", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addEmployee(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addEmployee called")
            if (!checkPermission("employees", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addEmployee(data)
                DebugLogger.info("Employee", "Added employee id=$id")
                successResponse(id, "تم إضافة الموظف بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Employee", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getEmployees(): String {
            DebugLogger.info("WebAppInterface", "getEmployees called")
            if (!checkPermission("employees", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val employees = db.getEmployees(1)
                dataResponse(employees)
            } catch (e: Exception) {
                DebugLogger.logException("Employee", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateEmployee(id: Long, jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateEmployee called")
            if (!checkPermission("employees", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateEmployee(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Employee", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteEmployee(id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteEmployee called")
            if (!checkPermission("employees", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                if (id > Int.MAX_VALUE || id < 0) return errorResponse("معرف غير صالح")
                val intId = id.toInt()
                val rows = db.deleteEmployee(intId)
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Employee", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 12. الورديات
        // ============================================================

        @JavascriptInterface
        fun startShift(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "startShift called")
            if (!checkPermission("shifts", "create")) return errorResponse("لا تملك صلاحية بدء الوردية")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.startShift(data)
                DebugLogger.info("Shift", "Started shift id=$id")
                successResponse(id, "تم بدء الوردية بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun endShift(id: Long, jsonData: String): String {
            DebugLogger.info("WebAppInterface", "endShift called")
            if (!checkPermission("shifts", "update")) return errorResponse("لا تملك صلاحية إنهاء الوردية")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.endShift(id, data)
                successResponse(rows > 0, if (rows > 0) "تم إنهاء الوردية بنجاح" else "لم يتم العثور على الوردية")
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCurrentShift(): String {
            DebugLogger.info("WebAppInterface", "getCurrentShift called")
            if (!checkPermission("shifts", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val shift = db.getCurrentShift()
                shift?.toString() ?: errorResponse("لا توجد وردية نشطة")
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getShifts(): String {
            DebugLogger.info("WebAppInterface", "getShifts called")
            if (!checkPermission("shifts", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val shifts = db.getShifts(1)
                dataResponse(shifts)
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteShift(shiftId: Long): String {
            DebugLogger.info("WebAppInterface", "deleteShift called")
            if (!checkPermission("shifts", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val dbWritable = db.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("shifts", cv, "id=?", arrayOf(shiftId.toString()))
                if (rows > 0) db.logActivity("system", "delete_shift", "حذف وردية $shiftId")
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addShiftSale(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addShiftSale called")
            if (!checkPermission("shifts", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addShiftSale(data)
                DebugLogger.info("Shift", "Added shift sale id=$id")
                successResponse(id, "تم إضافة بيع الوردية بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addShiftDelivery(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addShiftDelivery called")
            if (!checkPermission("shifts", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addShiftDelivery(data)
                DebugLogger.info("Shift", "Added shift delivery id=$id")
                successResponse(id, "تم إضافة تسليم الوردية بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addShiftExpense(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addShiftExpense called")
            if (!checkPermission("shifts", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addShiftExpense(data)
                DebugLogger.info("Shift", "Added shift expense id=$id")
                successResponse(id, "تم إضافة مصروف الوردية بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getShiftReport(shiftId: Long): String {
            DebugLogger.info("WebAppInterface", "getShiftReport called")
            if (!checkPermission("shifts", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val report = db.getShiftReport(shiftId)
                dataResponse(report)
            } catch (e: Exception) {
                DebugLogger.logException("Shift", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 13. الإشعارات
        // ============================================================

        @JavascriptInterface
        fun addNotification(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addNotification called")
            if (!checkPermission("notifications", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addNotification(data)
                DebugLogger.info("Notification", "Added notification id=$id")
                successResponse(id, "تم إضافة الإشعار بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Notification", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getNotifications(): String {
            DebugLogger.info("WebAppInterface", "getNotifications called")
            if (!checkPermission("notifications", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val notifications = db.getNotifications()
                dataResponse(notifications)
            } catch (e: Exception) {
                DebugLogger.logException("Notification", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUnreadNotificationsCount(): String {
            DebugLogger.info("WebAppInterface", "getUnreadNotificationsCount called")
            if (!checkPermission("notifications", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val count = db.getUnreadNotificationsCount()
                JSONObject().apply {
                    put("success", true)
                    put("count", count)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("Notification", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun markNotificationRead(id: Long): String {
            DebugLogger.info("WebAppInterface", "markNotificationRead called")
            if (!checkPermission("notifications", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.markNotificationRead(id)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على الإشعار")
            } catch (e: Exception) {
                DebugLogger.logException("Notification", e)
                errorResponse(e.message)
            }
        }

                // ============================================================
        // Navigation from the SMS screen
        // ============================================================
        @JavascriptInterface
        fun goToHome(): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed.get()) {
                    activity.webView?.loadUrl("file:///android_asset/screens/main.html")
                }
            }
            return successResponse(true, "تم الرجوع إلى الشاشة الرئيسية")
        }

        // ============================================================
        // 14. الرسائل النصية (SMS) - جزء مختصر للقراءة
        // ============================================================
        @JavascriptInterface
        fun addSmsMessage(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addSmsMessage called")
            if (!checkPermission("sms", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val recipient = data.optString("phone_number", data.optString("recipient")).trim()
                val body = data.optString("message_body", data.optString("body"))
                val result = SmsOutboxRepository.enqueue(
                    db = db,
                    recipient = recipient,
                    body = body,
                    eventId = data.optString("event_id").takeIf { it.isNotBlank() },
                    conversationId = data.optString("conversation_id").takeIf { it.isNotBlank() },
                    businessEntityId = data.optString("business_entity_id").takeIf { it.isNotBlank() },
                    priority = runCatching { SmsBudgetManager.Priority.valueOf(data.optString("priority", "NORMAL").uppercase()) }
                        .getOrDefault(SmsBudgetManager.Priority.NORMAL)
                ) ?: return errorResponse("بيانات الرسالة غير صالحة")
                SmsOutboxWorker.schedule(this@MainActivity)
                JSONObject().apply {
                    put("success", true)
                    put("status", result.status)
                    put("operation_id", result.messageId)
                    put("parts_count", result.partsCount)
                    put("message", "تم وضع الرسالة في طابور الإرسال")
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsMessages(): String {
            DebugLogger.info("WebAppInterface", "getSmsMessages called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val messages = db.getSmsMessages()
                dataResponse(messages)
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsMessagesPage(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "getSmsMessagesPage called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponseObject(db.getSmsMessagesPage(JSONObject(jsonData.ifBlank { "{}" }))).toString()
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsMessagesByPhone(phone: String): String {
            DebugLogger.info("WebAppInterface", "getSmsMessagesByPhone called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val messages = db.getSmsMessagesByPhone(phone)
                dataResponse(messages)
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsMessagesByStatus(status: String): String {
            DebugLogger.info("WebAppInterface", "getSmsMessagesByStatus called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val messages = db.getSmsMessagesByStatus(status)
                dataResponse(messages)
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateSmsStatus(id: Long, status: String): String {
            DebugLogger.info("WebAppInterface", "updateSmsStatus called")
            if (!checkPermission("sms", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.updateSmsStatus(id, status)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على الرسالة")
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun markSmsMessageRead(id: Long): String {
            DebugLogger.info("WebAppInterface", "markSmsMessageRead called")
            if (!checkPermission("sms", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.markSmsMessageRead(id)
                successResponse(rows > 0, if (rows > 0) "تم تعليم الرسالة كمقروءة" else "الرسالة مقروءة بالفعل أو غير موجودة")
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun retrySmsMessage(id: Long): String {
            DebugLogger.info("WebAppInterface", "retrySmsMessage called")
            if (!checkPermission("sms", "update")) return errorResponse("لا تملك صلاحية إعادة المحاولة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val retried = db.retrySmsMessage(id)
                if (retried) SmsOutboxWorker.schedule(this@MainActivity)
                successResponse(retried, if (retried) "تمت إعادة الرسالة إلى طابور الإرسال" else "الرسالة غير قابلة لإعادة المحاولة")
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteSmsMessage(id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteSmsMessage called")
            if (!checkPermission("sms", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val deleted = db.deleteSmsMessage(id)
                successResponse(deleted, if (deleted) "تم إلغاء الرسالة وحذفها" else "لا يمكن حذف رسالة قيد الإرسال أو غير موجودة")
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsStats(): String {
            DebugLogger.info("WebAppInterface", "getSmsStats called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stats = db.getSmsStats()
                dataResponse(stats)
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsOperationalHealth(): String {
            DebugLogger.info("WebAppInterface", "getSmsOperationalHealth called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية قراءة صحة SMS")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val snapshot = SmsOperationalNervousSystem(db).snapshot()
                dataResponse(JSONObject().apply {
                    put("score", snapshot.score)
                    put("queued", snapshot.queued)
                    put("failed", snapshot.failed)
                    put("open_sla", snapshot.openSla)
                    put("database_healthy", snapshot.databaseHealthy)
                    put("timestamp", System.currentTimeMillis())
                })
            } catch (e: Exception) {
                DebugLogger.logException("SMS_HEALTH", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsConversationTrace(phone: String): String {
            DebugLogger.info("WebAppInterface", "getSmsConversationTrace called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية قراءة مسار SMS")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val trace = JSONArray()
                val conversationId = db.readableDatabase.rawQuery(
                    "SELECT conversation_id FROM sms_conversation_context WHERE phone = ? LIMIT 1",
                    arrayOf(phone.trim())
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
                if (conversationId.isNotBlank()) {
                    db.readableDatabase.rawQuery(
                        "SELECT stage, payload_json, created_at FROM sms_conversation_trace WHERE conversation_id = ? ORDER BY created_at ASC LIMIT 200",
                        arrayOf(conversationId)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            trace.put(JSONObject().apply {
                                put("stage", cursor.getString(0))
                                put("payload", runCatching { JSONObject(cursor.getString(1)) }.getOrDefault(JSONObject()))
                                put("created_at", cursor.getLong(2))
                            })
                        }
                    }
                }
                dataResponse(JSONObject().apply {
                    put("conversation_id", conversationId)
                    put("trace", trace)
                })
            } catch (e: Exception) {
                DebugLogger.logException("SMS_TRACE", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsWeeklyAnalytics(days: Int = 7): String {
            DebugLogger.info("WebAppInterface", "getSmsWeeklyAnalytics called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية قراءة تحليلات SMS")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                SmsWeeklyAnalytics(db).build(days).toString()
            } catch (e: Exception) {
                DebugLogger.logException("SMS_WEEKLY_ANALYTICS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsTemplates(): String {
            DebugLogger.info("WebAppInterface", "getSmsTemplates called")
            if (!checkPermission("sms_templates", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val templates = db.getSmsTemplates()
                dataResponse(templates)
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getNotificationTemplates(): String {
            DebugLogger.info("WebAppInterface", "getNotificationTemplates called")
            if (!checkPermission("notifications", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponse(db.getNotificationTemplates())
            } catch (e: Exception) {
                DebugLogger.logException("NotificationTemplates", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addNotificationTemplate(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addNotificationTemplate called")
            if (!checkPermission("notifications", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                data.put("created_by", getActivity()?.currentUserId ?: 0L)
                val id = db.addNotificationTemplate(data)
                successResponse(id, "تم إضافة قالب الإشعار بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("NotificationTemplates", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateNotificationTemplate(id: Long, jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateNotificationTemplate called")
            if (!checkPermission("notifications", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.updateNotificationTemplate(id, JSONObject(jsonData))
                successResponse(rows > 0, if (rows > 0) "تم تحديث قالب الإشعار" else "لم يتم العثور على القالب")
            } catch (e: Exception) {
                DebugLogger.logException("NotificationTemplates", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteNotificationTemplate(id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteNotificationTemplate called")
            if (!checkPermission("notifications", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteNotificationTemplate(id)
                successResponse(rows > 0, if (rows > 0) "تم حذف قالب الإشعار" else "لم يتم العثور على القالب")
            } catch (e: Exception) {
                DebugLogger.logException("NotificationTemplates", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addSmsTemplate(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addSmsTemplate called")
            if (!checkPermission("sms_templates", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addSmsTemplate(data)
                DebugLogger.info("SMS", "Added template id=$id")
                successResponse(id, "تم إضافة القالب بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateSmsTemplate(id: Long, jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateSmsTemplate called")
            if (!checkPermission("sms_templates", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateSmsTemplate(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على القالب")
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteSmsTemplate(id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteSmsTemplate called")
            if (!checkPermission("sms_templates", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteSmsTemplate(id)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على القالب")
            } catch (e: Exception) {
                DebugLogger.logException("SMS", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsCoreDiagnostics(): String {
            DebugLogger.info("WebAppInterface", "getSmsCoreDiagnostics called")
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            return try {
                dataResponseObject(SmsCoreDiagnostics.exportJson(this@MainActivity)).toString()
            } catch (e: Exception) {
                DebugLogger.logException("SmsCoreDiagnostics", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 15. القائمة البيضاء والإعدادات
        // ============================================================

        @JavascriptInterface
        fun getWhitelist(): String {
            DebugLogger.info("WebAppInterface", "getWhitelist called")
            if (!checkPermission("whitelist", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val whitelist = db.getSmsWhitelist()
                dataResponse(whitelist)
            } catch (e: Exception) {
                DebugLogger.logException("Whitelist", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addWhitelist(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addWhitelist called")
            if (!checkPermission("whitelist", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val phone = data.optString("phone", "")
                val name = data.optString("name", "")
                if (phone.isBlank()) return errorResponse("رقم الهاتف مطلوب")
                db.addToSmsWhitelist(phone, name)
                DebugLogger.info("Whitelist", "Added $phone")
                successResponse(0, "تمت الإضافة بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Whitelist", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun removeWhitelist(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "removeWhitelist called")
            if (!checkPermission("whitelist", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val phone = data.optString("phone", "")
                if (phone.isBlank()) return errorResponse("رقم الهاتف مطلوب")
                db.removeFromSmsWhitelist(phone)
                DebugLogger.info("Whitelist", "Removed $phone")
                successResponse(0, "تم الحذف بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Whitelist", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateWhitelist(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateWhitelist called")
            if (!checkPermission("whitelist", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val phone = data.optString("phone", "").trim()
                if (phone.isBlank()) return errorResponse("رقم الهاتف مطلوب")
                val rows = db.updateSmsWhitelist(phone, data.optString("name", ""), data.optInt("enabled", 1) == 1)
                successResponse(rows > 0, if (rows > 0) "تم تحديث الرقم" else "لم يتم العثور على الرقم")
            } catch (e: Exception) {
                DebugLogger.logException("Whitelist", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addSetting(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addSetting called")
            if (!checkPermission("settings", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addSetting(data)
                DebugLogger.info("Setting", "Added setting id=$id")
                successResponse(id, "تم إضافة الإعداد بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Setting", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteSetting(key: String): String {
            DebugLogger.info("WebAppInterface", "deleteSetting called")
            if (!checkPermission("settings", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteSetting(key)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على الإعداد")
            } catch (e: Exception) {
                DebugLogger.logException("Setting", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSetting(key: String): String {
            DebugLogger.info("WebAppInterface", "getSetting called")
            if (!checkPermission("settings", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val value = db.getSetting(key)
                JSONObject().apply {
                    put("success", true)
                    put("value", value)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("Setting", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun setSetting(key: String, value: String): String {
            DebugLogger.info("WebAppInterface", "setSetting called")
            if (!checkPermission("settings", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                db.setSetting(key, value)
                DebugLogger.info("Setting", "Updated $key=$value")
                successResponse(0, "تم التحديث")
            } catch (e: Exception) {
                DebugLogger.logException("Setting", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getAllSettingsMap(): String {
            DebugLogger.info("WebAppInterface", "getAllSettingsMap called")
            if (!checkPermission("settings", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val settings = db.getAllSettingsMap()
                dataResponse(settings)
            } catch (e: Exception) {
                DebugLogger.logException("Setting", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 17. Settings module — عقد SQLite/Bridge الصريح
        // ============================================================

        @JavascriptInterface
        fun getApplicationSettings(): String {
            if (!checkPermission("settings", "read")) return errorResponse("لا تملك صلاحية قراءة الإعدادات")
            return try {
                val settings = runBlocking(Dispatchers.IO) {
                    settingsModule.settingsRepository.getSettings()
                }
                dataResponse(applicationSettingsJson(settings))
            } catch (e: Exception) {
                DebugLogger.logException("Settings", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun saveApplicationSettings(jsonData: String): String {
            if (!checkPermission("settings", "update")) return errorResponse("لا تملك صلاحية تحديث الإعدادات")
            return try {
                val payload = JSONObject(jsonData.ifBlank { "{}" })
                val settingsObject = payload.optJSONObject("settings") ?: payload
                val settings = settingsJson.decodeFromString<ApplicationSettings>(settingsObject.toString())
                val saved = runBlocking(Dispatchers.IO) {
                    settingsModule.settingsRepository.saveSettings(settings)
                    settingsModule.settingsRepository.getSettings()
                }
                applyApplicationSettings(saved)
                dataResponse(applicationSettingsJson(saved))
            } catch (e: Exception) {
                DebugLogger.logException("Settings", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun resetApplicationSettings(): String {
            if (!checkPermission("settings", "update")) return errorResponse("لا تملك صلاحية إعادة ضبط الإعدادات")
            return try {
                val defaults = runBlocking(Dispatchers.IO) {
                    settingsModule.settingsRepository.resetSettings()
                    settingsModule.settingsRepository.getSettings()
                }
                applyApplicationSettings(defaults)
                dataResponse(applicationSettingsJson(defaults))
            } catch (e: Exception) {
                DebugLogger.logException("Settings", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun createSettingsBackup(): String {
            if (!checkPermission("settings", "create")) return errorResponse("لا تملك صلاحية إنشاء نسخة إعدادات")
            return try {
                val id = runBlocking(Dispatchers.IO) {
                    settingsModule.backupManager.createBackup()
                }
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إنشاء نسخة الإعدادات")
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("SettingsBackup", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun listSettingsBackups(): String {
            if (!checkPermission("settings", "read")) return errorResponse("لا تملك صلاحية قراءة النسخ")
            return try {
                val entries = runBlocking(Dispatchers.IO) {
                    settingsModule.backupManager.listBackups()
                }
                val result = JSONArray()
                entries.forEach { entry: BackupEntry ->
                    result.put(JSONObject().apply {
                        put("id", entry.id)
                        put("created_at", entry.createdAt)
                        put("size", entry.size)
                        put("is_encrypted", entry.isEncrypted)
                    })
                }
                dataResponse(result)
            } catch (e: Exception) {
                DebugLogger.logException("SettingsBackup", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun restoreSettingsBackup(id: String): String {
            if (!checkPermission("settings", "update")) return errorResponse("لا تملك صلاحية استعادة النسخ")
            return try {
                val restored = runBlocking(Dispatchers.IO) {
                    settingsModule.backupManager.restoreBackup(id)
                }
                applyApplicationSettings(restored)
                dataResponse(applicationSettingsJson(restored))
            } catch (e: Exception) {
                DebugLogger.logException("SettingsBackup", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteSettingsBackup(id: String): String {
            if (!checkPermission("settings", "delete")) return errorResponse("لا تملك صلاحية حذف النسخ")
            return try {
                val deleted = runBlocking(Dispatchers.IO) {
                    settingsModule.backupManager.deleteBackup(id)
                }
                successResponse(deleted, if (deleted) "تم حذف النسخة" else "لم يتم العثور على النسخة")
            } catch (e: Exception) {
                DebugLogger.logException("SettingsBackup", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSettingsMonitoring(): String {
            if (!checkPermission("settings", "read")) return errorResponse("لا تملك صلاحية قراءة المراقبة")
            return try {
                val state = runBlocking(Dispatchers.IO) {
                    settingsModule.monitoringRepository.refresh()
                    settingsModule.monitoringRepository.observeMonitoring().first()
                }
                dataResponse(monitoringStateJson(state))
            } catch (e: Exception) {
                DebugLogger.logException("SettingsMonitoring", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun clearSettingsLogs(): String {
            if (!checkPermission("settings", "delete")) return errorResponse("لا تملك صلاحية تنظيف السجلات")
            return try {
                runBlocking(Dispatchers.IO) { settingsModule.monitoringRepository.clearLogs() }
                successResponse(true, "تم تنظيف السجلات القديمة")
            } catch (e: Exception) {
                DebugLogger.logException("SettingsMonitoring", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun clearSettingsMetrics(): String {
            if (!checkPermission("settings", "delete")) return errorResponse("لا تملك صلاحية تنظيف المقاييس")
            return try {
                runBlocking(Dispatchers.IO) { settingsModule.monitoringRepository.clearMetrics() }
                successResponse(true, "تم تنظيف المقاييس القديمة")
            } catch (e: Exception) {
                DebugLogger.logException("SettingsMonitoring", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun optimizeSettingsDatabase(): String {
            if (!checkPermission("settings", "update")) return errorResponse("لا تملك صلاحية تحسين قاعدة البيانات")
            return try {
                runBlocking(Dispatchers.IO) { settingsModule.maintenanceRepository.optimizeDatabase() }
                successResponse(true, "تم فحص وتحسين قاعدة البيانات")
            } catch (e: Exception) {
                DebugLogger.logException("SettingsMaintenance", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSettingsDatabaseIntegrity(): String {
            if (!checkPermission("settings", "read")) return errorResponse("لا تملك صلاحية فحص قاعدة البيانات")
            return try {
                val valid = runBlocking(Dispatchers.IO) { dbHelper.checkIntegrity() }
                JSONObject().apply {
                    put("success", true)
                    put("valid", valid)
                    put("database_open", dbHelper.isOpen())
                    put("size_bytes", dbHelper.getDatabaseSize())
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("SettingsMaintenance", e)
                errorResponse(e.message)
            }
        }

        private fun applyApplicationSettings(settings: ApplicationSettings) {
            val smsEnabled = settings.smsServiceEnabled && settings.smsReceiveEnabled
            dbHelper.setSetting("sms_enabled", if (smsEnabled) "1" else "0")
            dbHelper.setSetting("sms_receive_enabled", if (settings.smsReceiveEnabled) "1" else "0")
            dbHelper.setSetting("sms_send_enabled", if (settings.smsSendEnabled) "1" else "0")
            dbHelper.setSetting("sms_retry_enabled", if (settings.smsRetryEnabled) "1" else "0")
            dbHelper.setSetting("auto_start_enabled", if (settings.autoStartEnabled) "1" else "0")
            if (smsEnabled && settings.autoStartEnabled) startSMSService() else stopSMSService()
        }

        // ============================================================
        // 18. لوحة التحكم والتقارير
        // ============================================================

        // لاحظ: تم إزالة الدالة المكررة getDashboardStats هنا، حيث توجد نسخة واحدة فقط في بداية WebAppInterface

        @JavascriptInterface
        fun getOverduePayments(): String {
            DebugLogger.info("WebAppInterface", "getOverduePayments called")
            if (!checkPermission("payments", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val payments = db.getOverduePayments()
                dataResponse(payments)
            } catch (e: Exception) {
                DebugLogger.logException("Payments", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getActiveAlerts(): String {
            DebugLogger.info("WebAppInterface", "getActiveAlerts called")
            if (!checkPermission("alerts", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val alerts = db.getActiveAlerts()
                dataResponse(alerts)
            } catch (e: Exception) {
                DebugLogger.logException("Alerts", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getRecentActivity(limit: Int): String {
            DebugLogger.info("WebAppInterface", "getRecentActivity called")
            if (!checkPermission("activity", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val activity = db.getRecentActivity(limit)
                dataResponse(activity)
            } catch (e: Exception) {
                DebugLogger.logException("Activity", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getActivityLogs(limit: Int): String {
            DebugLogger.info("WebAppInterface", "getActivityLogs called")
            if (!checkPermission("activity", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponse(db.getActivityLogs(limit))
            } catch (e: Exception) {
                DebugLogger.logException("Activity", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteActivityLog(sourceTable: String, id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteActivityLog called")
            if (!checkPermission("activity", "delete")) return errorResponse("لا تملك صلاحية حذف السجل")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteActivityLog(sourceTable, id)
                when {
                    rows < 0 -> errorResponse("مصدر السجل غير مسموح")
                    rows == 0 -> successResponse(false, "لم يتم العثور على السجل")
                    else -> successResponse(true, "تم حذف السجل فعلياً")
                }
            } catch (e: Exception) {
                DebugLogger.logException("Activity", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun cleanupActivityLogs(retentionDays: Int): String {
            DebugLogger.info("WebAppInterface", "cleanupActivityLogs called")
            if (!checkPermission("activity", "delete")) return errorResponse("لا تملك صلاحية تنظيف السجلات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val deleted = db.cleanupActivityLogs(retentionDays)
                JSONObject().apply {
                    put("success", true)
                    put("deleted", deleted)
                    put("message", "تم تنظيف السجلات فعلياً")
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("Activity", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getBalanceSheet(reportDate: String, currencyId: Long): String {
            DebugLogger.info("WebAppInterface", "getBalanceSheet called")
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة الميزانية")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stationId = getCurrentStationId(db, activity.currentUserId)
                val effectiveCurrencyId = resolveCurrencyId(db, currencyId)
                dataResponse(db.getBalanceSheet(reportDate, stationId, effectiveCurrencyId))
            } catch (e: Exception) {
                DebugLogger.logException("BalanceSheet", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun saveBalanceSheet(reportDate: String, currencyId: Long): String {
            DebugLogger.info("WebAppInterface", "saveBalanceSheet called")
            if (!checkPermission("accounting", "create")) return errorResponse("لا تملك صلاحية حفظ الميزانية")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stationId = getCurrentStationId(db, activity.currentUserId)
                val effectiveCurrencyId = resolveCurrencyId(db, currencyId)
                val id = db.saveBalanceSheetSnapshot(reportDate, stationId, effectiveCurrencyId, activity.currentUserId)
                successResponse(id, "تم حفظ snapshot الميزانية فعلياً")
            } catch (e: Exception) {
                DebugLogger.logException("BalanceSheet", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 18. المنتجات والوقود
        // ============================================================

        @JavascriptInterface
        fun getProducts(): String {
            DebugLogger.info("WebAppInterface", "getProducts called")
            if (!checkPermission("products", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val products = db.getProducts()
                dataResponse(products)
            } catch (e: Exception) {
                DebugLogger.logException("Products", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addProduct(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addProduct called")
            if (!checkPermission("products", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.insertProduct(data)
                DebugLogger.info("Product", "Added product id=$id")
                successResponse(id, "تم إضافة المنتج بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Product", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateProduct(id: Long, jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateProduct called")
            if (!checkPermission("products", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateProduct(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على المنتج")
            } catch (e: Exception) {
                DebugLogger.logException("Product", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteProduct(id: Long): String {
            DebugLogger.info("WebAppInterface", "deleteProduct called")
            if (!checkPermission("products", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteProduct(id)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على المنتج")
            } catch (e: Exception) {
                DebugLogger.logException("Product", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getFuelTypes(): String {
            DebugLogger.info("WebAppInterface", "getFuelTypes called")
            if (!checkPermission("fuel", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val types = db.getFuelTypes()
                dataResponse(types)
            } catch (e: Exception) {
                DebugLogger.logException("Fuel", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCategories(): String {
            DebugLogger.info("WebAppInterface", "getCategories called")
            if (!checkPermission("categories", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val categories = db.getProductCategories()
                dataResponse(categories)
            } catch (e: Exception) {
                DebugLogger.logException("Categories", e)
                errorResponse(e.message)
            }
        }


        @JavascriptInterface
        fun getUnits(): String {
            if (!checkPermission("products", "read")) return errorResponse("لا تملك صلاحية قراءة الوحدات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getUnits()) } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getProductByBarcode(barcode: String): String {
            if (!checkPermission("products", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { db.getProductByBarcode(barcode)?.let { dataResponse(it) } ?: errorResponse("المنتج غير موجود") }
            catch (e: Exception) { DebugLogger.logException("ProductBarcode", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun addProductCategory(jsonData: String): String {
            if (!checkPermission("categories", "create")) return errorResponse("لا تملك صلاحية إضافة الفئات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { successResponse(db.insertProductCategory(JSONObject(jsonData)), "تمت إضافة الفئة") }
            catch (e: Exception) { DebugLogger.logException("Category", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun updateProductCategory(id: Long, jsonData: String): String {
            if (!checkPermission("categories", "update")) return errorResponse("لا تملك صلاحية تحديث الفئات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.updateProductCategory(id, JSONObject(jsonData)); successResponse(rows > 0, if (rows > 0) "تم تحديث الفئة" else "الفئة غير موجودة") }
            catch (e: Exception) { DebugLogger.logException("Category", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun deleteProductCategory(id: Long): String {
            if (!checkPermission("categories", "delete")) return errorResponse("لا تملك صلاحية حذف الفئات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.deleteProductCategory(id); successResponse(rows > 0, if (rows > 0) "تم حذف الفئة" else "الفئة غير موجودة") }
            catch (e: Exception) { DebugLogger.logException("Category", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun searchProductCategories(query: String): String {
            if (!checkPermission("categories", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.searchProductCategories(query)) } catch (e: Exception) { errorResponse(e.message) }
        }


        // ============================================================
        // 19. المركبات، الخزانات والمضخات - مختصر
        // ============================================================

        @JavascriptInterface
        fun getVehicles(): String {
            DebugLogger.info("WebAppInterface", "getVehicles called")
            if (!checkPermission("vehicles", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val vehicles = db.getVehicles()
                dataResponse(vehicles)
            } catch (e: Exception) {
                DebugLogger.logException("Vehicles", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTanks(): String {
            DebugLogger.info("WebAppInterface", "getTanks called")
            if (!checkPermission("tanks", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val tanks = db.getTanks()
                dataResponse(tanks)
            } catch (e: Exception) {
                DebugLogger.logException("Tanks", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPumps(): String {
            DebugLogger.info("WebAppInterface", "getPumps called")
            if (!checkPermission("pumps", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val pumps = db.getPumps()
                dataResponse(pumps)
            } catch (e: Exception) {
                DebugLogger.logException("Pumps", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTankStats(): String {
            DebugLogger.info("WebAppInterface", "getTankStats called")
            if (!checkPermission("tanks", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stats = db.getTankStats()
                dataResponse(stats)
            } catch (e: Exception) {
                DebugLogger.logException("Tanks", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateTankQuantity(tankId: Int, quantity: Double): String {
            DebugLogger.info("WebAppInterface", "updateTankQuantity called")
            if (!checkPermission("tanks", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                db.updateTankQuantity(tankId, quantity, "System")
                DebugLogger.info("Tank", "Updated quantity for tank $tankId")
                successResponse(0, "تم التحديث")
            } catch (e: Exception) {
                DebugLogger.logException("Tank", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 20. طلبات الصيانة
        // ============================================================

        @JavascriptInterface
        fun getMaintenanceRequests(jsonData: String?): String {
            DebugLogger.info("WebAppInterface", "getMaintenanceRequests called")
            if (!checkPermission("maintenance", "read")) {
                return errorResponse("لا تملك صلاحية القراءة")
            }
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val stationId = data.optInt("station_id", 0)
                val status = data.optString("status").takeIf { it.isNotBlank() }
                if (stationId <= 0) {
                    return errorResponse("رقم المحطة غير صحيح")
                }
                val requests = db.getMaintenanceRequests(stationId, status)
                dataResponse(requests)
            } catch (e: Exception) {
                DebugLogger.logException("Maintenance", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addMaintenanceRequest(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addMaintenanceRequest called")
            if (!checkPermission("maintenance", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val assetType = data.optString("asset_type", "tank")
                val assetId = data.optInt("asset_id", 0)
                val requestType = data.optString("request_type", "")
                val priority = data.optString("priority", "medium")
                val title = data.optString("title", "")
                val description = data.optString("description", "")
                if (assetId <= 0 || requestType.isBlank() || title.isBlank() || description.isBlank()) {
                    return errorResponse("بيانات غير صالحة")
                }
                val id = db.addMaintenanceRequest(assetType, assetId, requestType, priority, title, description, 1, 1)
                DebugLogger.info("Maintenance", "Added request id=$id")
                successResponse(id, "تم إضافة طلب الصيانة بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Maintenance", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateMaintenanceStatus(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updateMaintenanceStatus called")
            if (!checkPermission("maintenance", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val requestId = data.optLong("request_id", 0)
                val status = data.optString("status", "")
                if (requestId <= 0 || status.isBlank()) return errorResponse("بيانات غير صالحة")
                val rows = db.updateMaintenanceRequestStatus(requestId, status)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على الطلب")
            } catch (e: Exception) {
                DebugLogger.logException("Maintenance", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteMaintenance(requestId: Long): String {
            DebugLogger.info("WebAppInterface", "deleteMaintenance called")
            if (!checkPermission("maintenance", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val dbWritable = db.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("maintenance_requests", cv, "id=?", arrayOf(requestId.toString()))
                if (rows > 0) db.logActivity("system", "delete_maintenance", "حذف طلب صيانة $requestId")
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على الطلب")
            } catch (e: Exception) {
                DebugLogger.logException("Maintenance", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 21. المدفوعات والإيداعات - مختصر
        // ============================================================

        @JavascriptInterface
        fun getPayments(): String {
            DebugLogger.info("WebAppInterface", "getPayments called")
            if (!checkPermission("payments", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val payments = db.getPaymentsWithCustomer()
                dataResponse(payments)
            } catch (e: Exception) {
                DebugLogger.logException("Payments", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun makePayment(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "makePayment called")
            if (!checkPermission("payments", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val customerId = data.optInt("customer_party_id", 0)
                val amount = data.optDouble("amount", 0.0)
                val method = data.optString("payment_method", "cash")
                val operator = data.optString("operator", "System")
                val notes = data.optString("notes", "").trim()
                if (customerId <= 0 || amount <= 0) return errorResponse("بيانات غير صالحة")
                val success = db.processPayment(customerId, amount, method, operator, notes)
                successResponse(success, if (success) "تم التسديد بنجاح" else "فشل التسديد")
            } catch (e: Exception) {
                DebugLogger.logException("Payment", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addDeposit(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addDeposit called")
            if (!checkPermission("payments", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val customerId = data.optInt("customer_party_id", 0)
                val amount = data.optDouble("amount", 0.0)
                val notes = data.optString("notes", "")
                val operator = data.optString("operator", "System")
                if (customerId <= 0 || amount <= 0) return errorResponse("بيانات غير صالحة")
                val success = db.addCashDeposit(customerId, amount, notes, operator)
                successResponse(success, if (success) "تم الإيداع بنجاح" else "فشل الإيداع")
            } catch (e: Exception) {
                DebugLogger.logException("Deposit", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deletePayment(paymentId: Long): String {
            DebugLogger.info("WebAppInterface", "deletePayment called")
            if (!checkPermission("payments", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val dbWritable = db.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("payments", cv, "id=?", arrayOf(paymentId.toString()))
                if (rows > 0) db.logActivity("system", "delete_payment", "حذف دفعة $paymentId")
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على الدفعة")
            } catch (e: Exception) {
                DebugLogger.logException("Payment", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 22. تقارير إضافية - مختصر
        // ============================================================

        @JavascriptInterface
        fun getMonthlySales(): String {
            DebugLogger.info("WebAppInterface", "getMonthlySales called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getMonthlySales(1)
                dataResponse(sales)
            } catch (e: Exception) {
                DebugLogger.logException("Reports", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getDailySales(date: String?): String {
            DebugLogger.info("WebAppInterface", "getDailySales called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getDailySales(1, date)
                dataResponse(sales)
            } catch (e: Exception) {
                DebugLogger.logException("Reports", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getEodReport(): String {
            DebugLogger.info("WebAppInterface", "getEodReport called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val report = db.getEodReport(1)
                dataResponse(report)
            } catch (e: Exception) {
                DebugLogger.logException("Reports", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getProfitReport(fromDate: String?, toDate: String?): String {
            DebugLogger.info("WebAppInterface", "getProfitReport called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val report = db.getEodReport(1, fromDate, toDate)
                val profit = report.optDouble("total_sales", 0.0) - report.optDouble("total_payments", 0.0)
                report.put("profit", profit)
                report.put("revenue", report.optDouble("total_sales", 0.0))
                report.put("cost", report.optDouble("total_payments", 0.0))
                dataResponse(report)
            } catch (e: Exception) {
                DebugLogger.logException("Reports", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getOverdueReport(): String {
            DebugLogger.info("WebAppInterface", "getOverdueReport called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val overdue = db.getOverduePayments()
                dataResponse(overdue)
            } catch (e: Exception) {
                DebugLogger.logException("Reports", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getFuelSales(): String {
            DebugLogger.info("WebAppInterface", "getFuelSales called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getSalesByFuelType()
                dataResponse(sales)
            } catch (e: Exception) {
                DebugLogger.logException("Reports", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 23. النسخ الاحتياطي والتصدير
        // ============================================================

        @JavascriptInterface
        fun backupDatabase(): String {
            DebugLogger.info("WebAppInterface", "backupDatabase called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (!checkPermission("backup", "export")) return errorResponse("لا تملك صلاحية النسخ الاحتياطي")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val path = db.backupDatabase()
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("path", path)
                            put("message", "تم إنشاء النسخة الاحتياطية بنجاح")
                        }
                        activity.safeEvaluateJs("window.onBackupResult && window.onBackupResult(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onBackupResult && window.onBackupResult(${result})")
                    }
                    DebugLogger.logException("Backup", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun restoreDatabase(path: String): String {
            DebugLogger.info("WebAppInterface", "restoreDatabase called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (!checkPermission("backup", "import")) return errorResponse("لا تملك صلاحية الاستعادة")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val success = db.restoreDatabase(path)
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", success)
                            put("message", if (success) "تم الاستعادة بنجاح" else "فشل الاستعادة")
                        }
                        activity.safeEvaluateJs("window.onRestoreResult && window.onRestoreResult(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onRestoreResult && window.onRestoreResult(${result})")
                    }
                    DebugLogger.logException("Restore", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun exportToCSV(tableName: String): String {
            DebugLogger.info("WebAppInterface", "exportToCSV called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (!checkPermission("export", "export")) return errorResponse("لا تملك صلاحية التصدير")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val path = db.exportToCSV(tableName)
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("path", path)
                            put("message", "تم التصدير بنجاح")
                        }
                        activity.safeEvaluateJs("window.onExportResult && window.onExportResult(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onExportResult && window.onExportResult(${result})")
                    }
                    DebugLogger.logException("Export", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun importFromCSV(tableName: String, path: String): String {
            DebugLogger.info("WebAppInterface", "importFromCSV called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (!checkPermission("import", "import")) return errorResponse("لا تملك صلاحية الاستيراد")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val count = db.importFromCSV(tableName, path)
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("count", count)
                            put("message", "تم استيراد $count سجل بنجاح")
                        }
                        activity.safeEvaluateJs("window.onImportResult && window.onImportResult(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onImportResult && window.onImportResult(${result})")
                    }
                    DebugLogger.logException("Import", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun exportAllData(): String {
            DebugLogger.info("WebAppInterface", "exportAllData called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (!checkPermission("export", "export")) return errorResponse("لا تملك صلاحية التصدير")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val data = db.exportAllData()
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("data", data)
                            put("message", "تم تصدير جميع البيانات بنجاح")
                        }
                        activity.safeEvaluateJs("window.onExportAllResult && window.onExportAllResult(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onExportAllResult && window.onExportAllResult(${result})")
                    }
                    DebugLogger.logException("ExportAll", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun vacuumDatabase(): String {
            DebugLogger.info("WebAppInterface", "vacuumDatabase called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            if (!checkPermission("maintenance", "update")) return errorResponse("لا تملك صلاحية الصيانة")

            val job = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    db.vacuumDatabase()
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("message", "تم تحسين قاعدة البيانات بنجاح")
                        }
                        activity.safeEvaluateJs("window.onVacuumResult && window.onVacuumResult(${result})")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                        activity.safeEvaluateJs("window.onVacuumResult && window.onVacuumResult(${result})")
                    }
                    DebugLogger.logException("Vacuum", e)
                }
            }
            activity.backgroundJob?.cancel()
            activity.backgroundJob = job

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        // ============================================================
        // 24. دوال مساعدة وأدوات
        // ============================================================

        @JavascriptInterface
        fun showToast(message: String) {
            val context = contextRef.get() ?: return
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun isServerReady(): Boolean = serverReady

        @JavascriptInterface
        fun getAppVersion(): String {
            val context = contextRef.get() ?: return "unknown"
            return try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }

        @JavascriptInterface
        fun getDatabaseInfo(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val json = JSONObject().apply {
                    put("version", DatabaseHelper.VERSION)
                    put("tables_count", db.getTableCounts().length())
                    put("is_encrypted", false)
                    put("size_bytes", db.getDatabaseSize())
                }
                dataResponse(json)
            } catch (e: Exception) {
                DebugLogger.logException("DatabaseInfo", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getDatabaseSize(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                JSONObject().apply {
                    put("success", true)
                    put("size", db.getDatabaseSize())
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("DatabaseSize", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTableCounts(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val counts = db.getTableCounts()
                dataResponse(counts)
            } catch (e: Exception) {
                DebugLogger.logException("TableCounts", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomerCount(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val count = db.getParties("customer").length()
                JSONObject().apply {
                    put("success", true)
                    put("count", count)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("CustomerCount", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSalesByFuelType(): String = getFuelSales()

        @JavascriptInterface
        fun getLatestMeterReadings(): String {
            if (!checkPermission("meter", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val readings = db.getLatestMeterReadings()
                dataResponse(readings)
            } catch (e: Exception) {
                DebugLogger.logException("Meter", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getAssetMaintenanceHistory(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "getAssetMaintenanceHistory called")
            if (!checkPermission("maintenance", "read")) {
                return errorResponse("لا تملك صلاحية القراءة")
            }
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val assetType = data.optString("asset_type")
                val assetId = data.optInt("asset_id")
                val limit = data.optInt("limit", 20)
                if (assetType.isBlank() || assetId <= 0) {
                    return errorResponse("بيانات الأصل غير صحيحة")
                }
                val history = db.getAssetMaintenanceHistory(assetType, assetId, limit)
                dataResponse(history)
            } catch (e: Exception) {
                DebugLogger.logException("Maintenance", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUserNotifications(userId: Long): String {
            DebugLogger.info("WebAppInterface", "getUserNotifications called")
            if (!checkPermission("notifications", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val notifications = db.getUserNotifications(userId)
                dataResponse(notifications)
            } catch (e: Exception) {
                DebugLogger.logException("Notifications", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUserScreens(userId: Long): String {
            DebugLogger.info("WebAppInterface", "getUserScreens called for user=$userId")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponse(getScreensForUser(activity, db, userId))
            } catch (e: Exception) {
                DebugLogger.logException("Screens", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUserPermissions(userId: Long): String {
            DebugLogger.info("WebAppInterface", "getUserPermissions called for user=$userId")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val permissions = if (activity.isDebugMode && userId == DEV_MODE_ADMIN_USER_ID) {
                    // DEV_MODE: قراءة كل الصلاحيات النشطة من SQLite مع كل القدرات.
                    db.getAllActivePermissions()
                } else {
                    db.getUserPermissions(userId)
                }
                dataResponse(permissions)
            } catch (e: Exception) {
                DebugLogger.logException("Permissions", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun checkLowStock(): String {
            DebugLogger.info("WebAppInterface", "checkLowStock called")
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val items = db.checkLowStock()
                dataResponse(items)
            } catch (e: Exception) {
                DebugLogger.logException("Stock", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun createStockAlert(productId: Long, threshold: Double): String {
            DebugLogger.info("WebAppInterface", "createStockAlert called")
            if (!checkPermission("stock", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val id = db.createStockAlert(productId, threshold)
                DebugLogger.info("Stock", "Created alert id=$id")
                successResponse(id, "تم إنشاء التنبيه بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Stock", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getDieselPrice(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val price = db.getDieselPrice()
                JSONObject().apply {
                    put("success", true)
                    put("price", price)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("Price", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getGasolinePrice(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val price = db.getGasolinePrice()
                JSONObject().apply {
                    put("success", true)
                    put("price", price)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("Price", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getManagerPhone(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val phone = db.getManagerPhone() ?: ""
                JSONObject().apply {
                    put("success", true)
                    put("phone", phone)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("ManagerPhone", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getDriverPhones(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val phones = db.getDriverPhones()
                dataResponse(phones)
            } catch (e: Exception) {
                DebugLogger.logException("DriverPhones", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTrustedSmscList(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val list = db.getTrustedSmscList()
                dataResponse(list)
            } catch (e: Exception) {
                DebugLogger.logException("SmscList", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomerBalanceByPhone(phone: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val balance = db.getCustomerBalanceByPhone(phone)
                JSONObject().apply {
                    put("success", true)
                    put("balance", balance)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("Balance", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getLastOrderByPhone(phone: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val order = db.getLastOrderByPhone(phone)
                order?.toString() ?: errorResponse("لا توجد طلبات")
            } catch (e: Exception) {
                DebugLogger.logException("Order", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getOrderHistoryByPhone(phone: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val history = db.getOrderHistoryByPhone(phone)
                dataResponse(history)
            } catch (e: Exception) {
                DebugLogger.logException("OrderHistory", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun recordDieselDelivery(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "recordDieselDelivery called")
            if (!checkPermission("deliveries", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val customerId = data.optString("customerId", "")
                val customerName = data.optString("customerName", "")
                val quantityLiters = data.optDouble("quantityLiters", 0.0)
                val quantityDabbas = data.optDouble("quantityDabbas", 0.0)
                val location = data.optString("location", "")
                val deliveryTime = data.optString("deliveryTime", "")
                val unitPrice = data.optDouble("unitPrice", 0.0)
                val totalAmount = data.optDouble("totalAmount", 0.0)
                val orderId = data.optString("orderId", "")
                val success = db.recordDieselDelivery(
                    customerId, customerName, quantityLiters, quantityDabbas,
                    location, deliveryTime, unitPrice, totalAmount, orderId
                )
                successResponse(success, if (success) "تم تسجيل التسليم بنجاح" else "فشل تسجيل التسليم")
            } catch (e: Exception) {
                DebugLogger.logException("Delivery", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 25. البنوك والحسابات البنكية - Bridge typed ومقيد بالصلاحيات
        // ============================================================

        @JavascriptInterface
        fun getBanks(): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة البنوك")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getBanks()) } catch (e: Exception) {
                DebugLogger.logException("Banks", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getBankAccounts(): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة الحسابات البنكية")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getBankAccounts()) } catch (e: Exception) {
                DebugLogger.logException("BankAccounts", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun saveBank(jsonData: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = data.optLong("id", 0L)
                val permission = if (id > 0) "update" else "create"
                if (!checkPermission("accounting", permission)) return errorResponse("لا تملك صلاحية هذه العملية")
                if (data.optString("bank_code").trim().isEmpty() || data.optString("bank_name_ar").trim().isEmpty()) {
                    return errorResponse("كود البنك والاسم العربي مطلوبان")
                }
                val rowsOrId = if (id > 0) db.updateBank(id, data).toLong() else db.insertBank(data)
                if (rowsOrId > 0) successResponse(rowsOrId, if (id > 0) "تم تحديث البنك" else "تم إضافة البنك")
                else errorResponse("لم يتم العثور على البنك أو لم يتم حفظه")
            } catch (e: Exception) {
                DebugLogger.logException("Bank", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteBank(id: Long): String {
            if (!checkPermission("accounting", "delete")) return errorResponse("لا تملك صلاحية حذف البنوك")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteBank(id)
                if (rows > 0) successResponse(true, "تم حذف البنك") else errorResponse("لم يتم العثور على البنك")
            } catch (e: Exception) {
                DebugLogger.logException("Bank", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun saveBankAccount(jsonData: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val data = JSONObject(jsonData)
                val id = data.optLong("id", 0L)
                val permission = if (id > 0) "update" else "create"
                if (!checkPermission("accounting", permission)) return errorResponse("لا تملك صلاحية هذه العملية")
                val accountType = data.optString("account_type", "current")
                val status = data.optString("status", "active")
                if (data.optString("account_code").trim().isEmpty() || data.optString("account_name_ar").trim().isEmpty() ||
                    data.optString("account_number").trim().isEmpty() || data.optLong("bank_id", 0L) <= 0 || data.optLong("currency_id", 0L) <= 0) {
                    return errorResponse("بيانات الحساب الأساسية غير مكتملة")
                }
                if (accountType !in setOf("current", "savings", "deposit", "loan")) return errorResponse("نوع الحساب غير صالح")
                if (status !in setOf("active", "inactive", "closed", "frozen")) return errorResponse("حالة الحساب غير صالحة")
                val result = if (id > 0) {
                    db.updateBankAccount(id, data, activity.currentUserId).toLong()
                } else {
                    db.insertBankAccount(data, getCurrentStationId(db, activity.currentUserId), activity.currentUserId)
                }
                if (result > 0) successResponse(result, if (id > 0) "تم تحديث الحساب" else "تم إضافة الحساب")
                else errorResponse("لم يتم العثور على الحساب أو لم يتم حفظه")
            } catch (e: Exception) {
                DebugLogger.logException("BankAccount", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteBankAccount(id: Long): String {
            if (!checkPermission("accounting", "delete")) return errorResponse("لا تملك صلاحية حذف الحسابات البنكية")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val rows = db.deleteBankAccount(id, activity.currentUserId)
                if (rows > 0) successResponse(true, "تم حذف الحساب") else errorResponse("لم يتم العثور على الحساب")
            } catch (e: Exception) {
                DebugLogger.logException("BankAccount", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun generateBankReport(jsonData: String): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة تقارير الحسابات البنكية")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData.ifBlank { "{}" })
                val type = data.optString("report_type", "all")
                val extra = data.optString("extra", "")
                if (extra == "export" && !checkPermission("accounting", "export")) return errorResponse("لا تملك صلاحية التصدير")
                val requestedStatus = if (data.isNull("status")) null else data.optInt("status", -1)
                val result = when {
                    extra == "stats" -> {
                        val banks = db.getBanks()
                        val accounts = db.getBankAccounts()
                        val totalBalance = (0 until accounts.length()).sumOf { accounts.optJSONObject(it)?.optDouble("current_balance", 0.0) ?: 0.0 }
                        JSONArray().put(JSONObject().apply {
                            put("total_banks", banks.length())
                            put("total_accounts", accounts.length())
                            put("total_balance", totalBalance)
                            put("active_accounts", (0 until accounts.length()).count { accounts.optJSONObject(it)?.optString("status") == "active" })
                        })
                    }
                    type == "banks" -> {
                        val banks = db.getBanks()
                        if (requestedStatus == null || requestedStatus < 0) banks else JSONArray().also { filtered ->
                            for (i in 0 until banks.length()) {
                                val item = banks.optJSONObject(i) ?: continue
                                val isActive = if (item.optInt("is_active", 0) == 1) 1 else 0
                                if (isActive == requestedStatus) filtered.put(item)
                            }
                        }
                    }
                    type == "accounts" || type == "balance" -> {
                        val accounts = db.getBankAccounts()
                        if (requestedStatus == null || requestedStatus < 0) accounts else JSONArray().also { filtered ->
                            for (i in 0 until accounts.length()) {
                                val item = accounts.optJSONObject(i) ?: continue
                                val isActive = if (item.optString("status") == "active") 1 else 0
                                if (isActive == requestedStatus) filtered.put(item)
                            }
                        }
                    }
                    type == "transactions" -> db.getBankLedger(data.optString("start_date", ""), data.optString("end_date", ""))
                    else -> {
                        val all = JSONArray()
                        val banks = db.getBanks()
                        for (i in 0 until banks.length()) banks.optJSONObject(i)?.let { it.put("record_type", "bank"); all.put(it) }
                        val accounts = db.getBankAccounts()
                        for (i in 0 until accounts.length()) accounts.optJSONObject(i)?.let { it.put("record_type", "account"); all.put(it) }
                        all
                    }
                }
                dataResponse(result)
            } catch (e: Exception) {
                DebugLogger.logException("BankReport", e)
                errorResponse(e.message)
            }
        }




        // ============================================================
        // CRM_BUNDLE_V1_BRIDGE: العقود Typed الخاصة بشاشة CRM.
        // ============================================================

        @JavascriptInterface
        fun savePartyBundle(jsonData: String): String {
            val payload = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("بيانات الطرف غير صالحة") }
            val action = if (payload.optLong("id", 0L) > 0) "update" else "create"
            if (!checkPermission("parties", action)) return errorResponse("لا تملك صلاحية هذه العملية على الأطراف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val id = db.savePartyBundle(payload, activity.currentUserId)
                successResponse(id, "تم حفظ الطرف وجهات اتصاله في SQLite")
            } catch (e: Exception) {
                DebugLogger.logException("PartyBundle", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPartyCrmBundle(id: Long): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية قراءة تفاصيل الطرف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getPartyCrmBundle(id)) } catch (e: Exception) {
                DebugLogger.logException("PartyCrmBundle", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updatePartyCreditLimit(jsonData: String): String {
            val payload = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("بيانات الحد الائتماني غير صالحة") }
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية تعديل الحد الائتماني")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val id = payload.optLong("id", 0L)
                val creditLimit = payload.optDouble("credit_limit", Double.NaN)
                val reason = payload.optString("reason", "تعديل من تقرير العملاء").trim()
                if (id <= 0L || !creditLimit.isFinite() || creditLimit < 0.0) return errorResponse("بيانات الحد الائتماني غير صالحة")
                val rows = db.updatePartyCreditLimit(id, creditLimit, reason, activity.currentUserId)
                successResponse(rows > 0, if (rows > 0) "تم تعديل الحد الائتماني وتسجيل العملية" else "لم يتم العثور على العميل")
            } catch (e: Exception) {
                DebugLogger.logException("PartyCreditLimit", e)
                errorResponse(e.message)
            }
        }
        @JavascriptInterface
        fun generateCRMReport(jsonData: String): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية قراءة تقارير CRM")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.generateCRMReport(JSONObject(jsonData.ifBlank { "{}" }))) } catch (e: Exception) {
                DebugLogger.logException("CRMReport", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // CONTRACTS_V15_BRIDGE: WebView -> DatabaseHelper -> SQLite
        // ============================================================
        @JavascriptInterface
        fun getContracts(includeArchived: Boolean): String {
            if (!checkPermission("contracts", "read")) return errorResponse("لا تملك صلاحية قراءة العقود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getContracts(includeArchived)) } catch (e: Exception) {
                DebugLogger.logException("ContractsRead", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getContractParties(): String {
            if (!checkPermission("contracts", "read")) return errorResponse("لا تملك صلاحية قراءة أطراف العقود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getParties("")) } catch (e: Exception) {
                DebugLogger.logException("ContractParties", e)
                errorResponse(e.message)
            }
        }
        @JavascriptInterface
        fun getContractBundle(id: Long): String {
            if (!checkPermission("contracts", "read")) return errorResponse("لا تملك صلاحية قراءة العقد")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getContractBundle(id)) } catch (e: Exception) {
                DebugLogger.logException("ContractBundle", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun saveContract(jsonData: String): String {
            val payload = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("بيانات العقد غير صالحة") }
            val required = if (payload.optLong("id", 0L) > 0) "update" else "create"
            if (!checkPermission("contracts", required)) return errorResponse("لا تملك صلاحية هذه العملية على العقود")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val id = db.saveContractBundle(payload, activity.currentUserId)
                successResponse(id, "تم حفظ العقد فعلياً في SQLite")
            } catch (e: Exception) {
                DebugLogger.logException("ContractSave", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteContract(id: Long): String {
            if (!checkPermission("contracts", "delete")) return errorResponse("لا تملك صلاحية حذف العقود")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteContract(id, activity.currentUserId)
                if (rows > 0) successResponse(true, "تم حذف العقد منطقياً وتسجيل العملية") else errorResponse("لم يتم حذف العقد")
            } catch (e: Exception) {
                DebugLogger.logException("ContractDelete", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun archiveContract(id: Long): String {
            if (!checkPermission("contracts", "update")) return errorResponse("لا تملك صلاحية أرشفة العقود")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.archiveContract(id, activity.currentUserId)
                if (rows > 0) successResponse(true, "تمت أرشفة العقد فعلياً") else errorResponse("لم تتم أرشفة العقد")
            } catch (e: Exception) {
                DebugLogger.logException("ContractArchive", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun restoreContract(id: Long): String {
            if (!checkPermission("contracts", "update")) return errorResponse("لا تملك صلاحية استعادة العقود")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.restoreContract(id, activity.currentUserId)
                if (rows > 0) successResponse(true, "تمت استعادة العقد فعلياً") else errorResponse("لم تتم استعادة العقد")
            } catch (e: Exception) {
                DebugLogger.logException("ContractRestore", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun cloneContract(id: Long, jsonData: String): String {
            if (!checkPermission("contracts", "create")) return errorResponse("لا تملك صلاحية نسخ العقود")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val newId = db.cloneContract(id, JSONObject(jsonData.ifBlank { "{}" }), activity.currentUserId)
                successResponse(newId, "تم نسخ العقد وبنوده وجدول دفعاته")
            } catch (e: Exception) {
                DebugLogger.logException("ContractClone", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun changeContractStatus(id: Long, status: String, reason: String?): String {
            if (!checkPermission("contracts", "update")) return errorResponse("لا تملك صلاحية تغيير حالة العقود")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.changeContractStatus(id, status, reason, activity.currentUserId)
                if (rows > 0) successResponse(true, "تم تغيير حالة العقد وتسجيلها") else errorResponse("لم تتغير حالة العقد")
            } catch (e: Exception) {
                DebugLogger.logException("ContractStatus", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun generateContractReport(jsonData: String): String {
            if (!checkPermission("contracts", "read")) return errorResponse("لا تملك صلاحية قراءة تقارير العقود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.generateContractReport(JSONObject(jsonData.ifBlank { "{}" }))) } catch (e: Exception) {
                DebugLogger.logException("ContractReport", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getContractAudit(id: Long): String {
            if (!checkPermission("contracts", "audit")) return errorResponse("لا تملك صلاحية قراءة سجل العقود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getContractAudit(id, 100)) } catch (e: Exception) {
                DebugLogger.logException("ContractAudit", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // COA_RECOMMENDATIONS_BRIDGE_V1: جسر شاشة شجرة الحسابات.
        // المصدر الوحيد للبيانات هو DatabaseHelper/SQLite المحلي.
        // ============================================================
        @JavascriptInterface
        fun getChartAccounts(): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة شجرة الحسابات")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { reportCacheResponse(db, "chart_accounts", JSONObject(), 3600L) { dataResponseObject(db.getChartAccounts()) } } catch (e: Exception) {
                DebugLogger.logException("ChartAccounts", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun saveChartAccount(jsonData: String): String {
            val payload = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("بيانات الحساب غير صالحة") }
            val requiredPermission = if (payload.optLong("id", 0L) > 0) "update" else "create"
            if (!checkPermission("accounting", requiredPermission)) return errorResponse("لا تملك صلاحية هذه العملية على الحسابات")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val id = db.saveChartAccount(payload, activity.currentUserId)
                successResponse(id, "تم حفظ الحساب فعلياً في SQLite")
            } catch (e: Exception) {
                DebugLogger.logException("ChartAccountSave", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteChartAccount(id: Long, cascade: Boolean): String {
            if (!checkPermission("accounting", "delete")) return errorResponse("لا تملك صلاحية حذف الحسابات")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteChartAccount(id, cascade, activity.currentUserId)
                if (rows > 0) successResponse(true, "تم الحذف الفعلي من SQLite") else errorResponse("لم يتم حذف الحساب")
            } catch (e: Exception) {
                DebugLogger.logException("ChartAccountDelete", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun archiveChartAccount(id: Long): String {
            if (!checkPermission("accounting", "update")) return errorResponse("لا تملك صلاحية أرشفة الحسابات")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.archiveChartAccount(id, activity.currentUserId)
                if (rows > 0) successResponse(true, "تمت أرشفة الحساب فعلياً") else errorResponse("لم تتم أرشفة الحساب")
            } catch (e: Exception) {
                DebugLogger.logException("ChartAccountArchive", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun restoreChartAccount(id: Long): String {
            if (!checkPermission("accounting", "update")) return errorResponse("لا تملك صلاحية استعادة الحسابات")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.restoreChartAccount(id, activity.currentUserId)
                if (rows > 0) successResponse(true, "تمت استعادة الحساب فعلياً") else errorResponse("لم تتم استعادة الحساب")
            } catch (e: Exception) {
                DebugLogger.logException("ChartAccountRestore", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun cloneChartAccount(id: Long, jsonData: String): String {
            if (!checkPermission("accounting", "create")) return errorResponse("لا تملك صلاحية نسخ الحسابات")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val newId = db.cloneChartAccount(id, JSONObject(jsonData.ifBlank { "{}" }), activity.currentUserId)
                successResponse(newId, "تم نسخ الحساب فعلياً")
            } catch (e: Exception) {
                DebugLogger.logException("ChartAccountClone", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun moveChartAccount(id: Long, parentId: Long): String {
            if (!checkPermission("accounting", "update")) return errorResponse("لا تملك صلاحية نقل الحسابات")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.moveChartAccount(id, parentId, activity.currentUserId)
                if (rows > 0) successResponse(true, "تم نقل الحساب وتحديث مستويات فروعه") else errorResponse("لم يتم نقل الحساب")
            } catch (e: Exception) {
                DebugLogger.logException("ChartAccountMove", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getChartAccountAudit(id: Long): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة سجل الحساب")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getChartAccountAudit(id)) } catch (e: Exception) {
                DebugLogger.logException("ChartAccountAudit", e)
                errorResponse(e.message)
            }
        }


        // ============================================================
        // COA_TRIAL_BALANCE_BRIDGE_V1
        // ============================================================
        @JavascriptInterface
        fun getChartTrialBalance(fromDate: String?, toDate: String?): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة ميزان المراجعة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getChartTrialBalance(fromDate, toDate)) } catch (e: Exception) {
                DebugLogger.logException("ChartTrialBalance", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 25. دوال إضافية للشاشات الجديدة - مختصر
        // ============================================================

        @JavascriptInterface
        fun getPartyTypes(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val types = db.getPartyTypes()
                dataResponse(types)
            } catch (e: Exception) {
                DebugLogger.logException("PartyTypes", e)
                errorResponse(e.message)
            }
        }


        @JavascriptInterface
        fun addPartyType(jsonData: String): String {
            if (!checkPermission("party_types", "create")) return errorResponse("لا تملك صلاحية إضافة أنواع الأطراف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { successResponse(db.insertPartyType(JSONObject(jsonData)), "تمت إضافة نوع الطرف") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun updatePartyType(id: Long, jsonData: String): String {
            if (!checkPermission("party_types", "update")) return errorResponse("لا تملك صلاحية تحديث أنواع الأطراف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.updatePartyType(id, JSONObject(jsonData)); successResponse(rows > 0, if (rows > 0) "تم تحديث نوع الطرف" else "النوع غير موجود") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun deletePartyType(id: Long): String {
            if (!checkPermission("party_types", "delete")) return errorResponse("لا تملك صلاحية حذف أنواع الأطراف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.deletePartyType(id); successResponse(rows > 0, if (rows > 0) "تم حذف نوع الطرف" else "النوع غير موجود") } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun generatePartyTypeReport(jsonData: String): String {
            if (!checkPermission("party_types", "read")) return errorResponse("لا تملك صلاحية قراءة تقارير أنواع الأطراف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getPartyTypeReport(JSONObject(jsonData.ifBlank { "{}" }).optString("report_type", "types"))) } catch (e: Exception) { errorResponse(e.message) }
        }


        @JavascriptInterface
        fun getCurrencies(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                reportCacheResponse(db, "currencies", JSONObject(), 3600L) { dataResponseObject(db.getCurrencies()) }
            } catch (e: Exception) {
                DebugLogger.logException("Currencies", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomerLedger(partyId: Long): String {
            DebugLogger.info("WebAppInterface", "getCustomerLedger called")
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val ledger = db.getCustomerLedger(partyId)
                dataResponse(ledger)
            } catch (e: Exception) {
                DebugLogger.logException("Ledger", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomerSales(partyId: Long): String {
            DebugLogger.info("WebAppInterface", "getCustomerSales called")
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getCustomerSales(partyId)
                dataResponse(sales)
            } catch (e: Exception) {
                DebugLogger.logException("CustomerSales", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPartyContacts(partyId: Long): String {
            DebugLogger.info("WebAppInterface", "getPartyContacts called")
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val contacts = db.getPartyContacts(partyId)
                dataResponse(contacts)
            } catch (e: Exception) {
                DebugLogger.logException("Contacts", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPartyAddresses(partyId: Long): String {
            DebugLogger.info("WebAppInterface", "getPartyAddresses called")
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val addresses = db.getPartyAddresses(partyId)
                dataResponse(addresses)
            } catch (e: Exception) {
                DebugLogger.logException("Addresses", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addPartyContact(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addPartyContact called")
            if (!checkPermission("parties", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addPartyContact(data)
                DebugLogger.info("Party", "Added contact id=$id")
                successResponse(id, "تم إضافة جهة الاتصال بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updatePartyContact(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updatePartyContact called")
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = data.optLong("id", 0)
                val rows = db.updatePartyContact(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deletePartyContact(contactId: Long): String {
            DebugLogger.info("WebAppInterface", "deletePartyContact called")
            if (!checkPermission("parties", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deletePartyContact(contactId)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addPartyAddress(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "addPartyAddress called")
            if (!checkPermission("parties", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addPartyAddress(data)
                DebugLogger.info("Party", "Added address id=$id")
                successResponse(id, "تم إضافة العنوان بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updatePartyAddress(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "updatePartyAddress called")
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = data.optLong("id", 0)
                val rows = db.updatePartyAddress(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deletePartyAddress(addressId: Long): String {
            DebugLogger.info("WebAppInterface", "deletePartyAddress called")
            if (!checkPermission("parties", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deletePartyAddress(addressId)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                DebugLogger.logException("Party", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomerDebts(fromDate: String?, toDate: String?): String {
            DebugLogger.info("WebAppInterface", "getCustomerDebts called")
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val debts = db.getCustomerDebts(fromDate, toDate)
                dataResponse(debts)
            } catch (e: Exception) {
                DebugLogger.logException("Debts", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 26. إدارة بيانات الاعتماد (Remember Me + Biometric Auto-Login)
        // ============================================================

        @JavascriptInterface
        fun saveCredentials(username: String, password: String, remember: Boolean): String {
            DebugLogger.info("WebAppInterface", "saveCredentials called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                activity.sharedPrefs.edit().apply {
                    putBoolean("remember_me", remember)
                    if (remember) {
                        putString("saved_username", username)
                        putString("saved_token", activity.currentAuthToken ?: "")
                        putLong("saved_user_id", activity.currentUserId)
                        putLong("saved_timestamp", System.currentTimeMillis())
                    } else {
                        remove("saved_username")
                        remove("saved_token")
                        remove("saved_user_id")
                        remove("saved_timestamp")
                    }
                    apply()
                }
                DebugLogger.info("Credentials", "Saved credentials for $username (remember=$remember)")
                successResponse(0, if (remember) "تم حفظ بيانات التسجيل" else "تم إلغاء التذكر")
            } catch (e: Exception) {
                DebugLogger.logException("Credentials", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun clearRememberedCredentials(): String {
            DebugLogger.info("WebAppInterface", "clearRememberedCredentials called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                activity.sharedPrefs.edit().apply {
                    remove("remember_me")
                    remove("saved_username")
                    remove("saved_token")
                    remove("saved_user_id")
                    remove("saved_timestamp")
                    apply()
                }
                // لا نلمس auth_token/user_id: هذه جلسة الدخول النشطة وليست بيانات Remember-Me.
                successResponse(0, "تم مسح بيانات التذكر فقط")
            } catch (e: Exception) {
                DebugLogger.logException("Credentials", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun loadCredentials(): String {
            DebugLogger.info("WebAppInterface", "loadCredentials called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val prefs = activity.sharedPrefs
                val remember = prefs.getBoolean("remember_me", false)
                val username = prefs.getString("saved_username", "") ?: ""
                val userId = prefs.getLong("saved_user_id", 0)
                val timestamp = prefs.getLong("saved_timestamp", 0)
                val hasToken = !prefs.getString("saved_token", "").isNullOrEmpty()

                JSONObject().apply {
                    put("success", true)
                    put("hasCredentials", remember && hasToken && userId != 0L && username.isNotEmpty())
                    put("username", username)
                    put("userId", userId)
                    put("timestamp", timestamp)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("Credentials", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun hasSavedCredentials(): String {
            DebugLogger.info("WebAppInterface", "hasSavedCredentials called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val prefs = activity.sharedPrefs
                val remember = prefs.getBoolean("remember_me", false)
                val hasToken = !prefs.getString("saved_token", "").isNullOrEmpty()
                val username = prefs.getString("saved_username", "") ?: ""
                val userId = prefs.getLong("saved_user_id", 0)

                JSONObject().apply {
                    put("success", true)
                    put("hasCredentials", remember && hasToken && userId != 0L)
                    put("username", username)
                    put("userId", userId)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("Credentials", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // Biometric Auto-Login – المسار الكامل المحدث
        // ============================================================

        @JavascriptInterface
        fun biometricAutoLogin(): String {
            DebugLogger.info("BiometricAuto", "biometricAutoLogin called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val prefs = activity.sharedPrefs

            // 1. التحقق من Remember Me
            val remember = prefs.getBoolean("remember_me", false)
            if (!remember) {
                DebugLogger.warn("BiometricAuto", "Remember Me not enabled")
                return errorResponse("لم يتم تفعيل Remember Me")
            }

            val savedUserId = prefs.getLong("saved_user_id", 0)
            val savedUsername = prefs.getString("saved_username", "") ?: ""

            if (savedUserId == 0L || savedUsername.isEmpty()) {
                DebugLogger.warn("BiometricAuto", "Invalid saved credentials")
                return errorResponse("بيانات تسجيل الدخول المحفوظة غير صالحة")
            }

            // 2. جلب المستخدم من قاعدة البيانات
            val user = db.getUserByUsername(savedUsername)
            if (user == null) {
                DebugLogger.warn("BiometricAuto", "User not found: $savedUsername")
                return errorResponse("المستخدم غير موجود")
            }

            val userIdFromDb = user.optLong("user_id", 0)
            if (userIdFromDb != savedUserId) {
                DebugLogger.warn("BiometricAuto", "User ID mismatch: DB=$userIdFromDb, saved=$savedUserId")
                return errorResponse("بيانات المستخدم غير متطابقة")
            }

            // 3. التحقق من حالة الحساب
            val status = user.optString("status", "")
            val isDeleted = user.optInt("is_deleted", 0)
            if (status != "active") {
                DebugLogger.warn("BiometricAuto", "User account not active: $status")
                return errorResponse("الحساب غير نشط")
            }
            if (isDeleted != 0) {
                DebugLogger.warn("BiometricAuto", "User account deleted")
                return errorResponse("الحساب محذوف")
            }

            // 4. عرض BiometricPrompt
            val role = user.optString("role", "USER")
            val fullName = user.optString("full_name", savedUsername)

            DebugLogger.info("BiometricAuto", "Prompting biometric for user $savedUsername (ID=$savedUserId)")

            activity.runOnUiThread {
                activity.showBiometricPrompt(
                    onSuccess = {
                        DebugLogger.info("BiometricAuto", "Biometric success for $savedUsername")
                        try {
                            // 5. إنشاء توكن جديد
                            val newToken = UUID.randomUUID().toString()

                            // 6. تحديث الجلسة
                            activity.currentAuthToken = newToken
                            activity.currentUserId = savedUserId
                            activity.currentUserRole = role
                            activity.currentUserName = savedUsername

                            // 7. جلب الصلاحيات والشاشات بنفس آلية login التقليدية
                            val permissionsArray = db.getUserPermissions(savedUserId)
                            val permissionsObject = JSONObject()
                            for (i in 0 until permissionsArray.length()) {
                                val item = permissionsArray.getJSONObject(i)
                                val code = item.getString("permission_code")
                                permissionsObject.put(
                                    code,
                                    JSONObject().apply {
                                        put("can_create", item.optBoolean("can_create"))
                                        put("can_read", item.optBoolean("can_read"))
                                        put("can_update", item.optBoolean("can_update"))
                                        put("can_delete", item.optBoolean("can_delete"))
                                        put("can_export", item.optBoolean("can_export"))
                                        put("can_print", item.optBoolean("can_print"))
                                        put("can_approve", item.optBoolean("can_approve"))
                                    }
                                )
                            }
                            val screensArray = db.getUserScreens(savedUserId)

                            // 8. تحديث البيانات المحفوظة (تحديث التوكن)
                            prefs.edit().apply {
                                putString("saved_token", newToken)
                                putLong("saved_timestamp", System.currentTimeMillis())
                                apply()
                            }

                            // 9. تحضير النتيجة
                            val userJson = JSONObject().apply {
                                put("user_id", savedUserId)
                                put("username", savedUsername)
                                put("full_name", fullName)
                                put("role", role)
                                put("permissions", permissionsObject)
                                put("screens", screensArray)
                                put("is_admin", role == "SUPER_ADMIN" || role == "ADMIN")
                            }

                            val result = JSONObject().apply {
                                put("success", true)
                                put("user", userJson)
                                put("token", newToken)
                                put("message", "تم تسجيل الدخول بالبصمة")
                            }

                            DebugLogger.info("BiometricAuto", "Login success for $savedUsername, new token generated")
                            activity.safeEvaluateJs("window.onBiometricAutoLogin && window.onBiometricAutoLogin(${result})")

                        } catch (e: Exception) {
                            DebugLogger.logException("BiometricAuto", e)
                            val errResult = JSONObject().apply {
                                put("success", false)
                                put("error", e.message)
                            }
                            activity.safeEvaluateJs("window.onBiometricAutoLogin && window.onBiometricAutoLogin(${errResult})")
                        }
                    },
                    onError = { error ->
                        DebugLogger.warn("BiometricAuto", "Biometric error: $error")
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", error)
                        }
                        activity.safeEvaluateJs("window.onBiometricAutoLogin && window.onBiometricAutoLogin(${result})")
                    }
                )
            }

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun exitApplication(): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                activity.clearSessionState()
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        activity.finishAndRemoveTask()
                    }
                }
                successResponse(0, "تم إنهاء التطبيق")
            } catch (e: Exception) {
                DebugLogger.logException("ExitApplication", e)
                errorResponse("تعذر إنهاء التطبيق")
            }
        }

        @JavascriptInterface
        fun clearCredentials(): String {
            DebugLogger.info("WebAppInterface", "clearCredentials called")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                activity.sharedPrefs.edit().apply {
                    remove("remember_me")
                    remove("saved_username")
                    remove("saved_token")
                    remove("saved_user_id")
                    remove("saved_timestamp")
                    apply()
                }
                activity.currentAuthToken = null
                if (activity.currentUserId > 0) { try { getDbHelper()?.clearReportCacheForUser(activity.currentUserId) } catch (e: Exception) { DebugLogger.warn("ReportCache", "Logout cache clear failed: ${e.message}") } }
                activity.currentUserId = 0
                activity.currentUserRole = ""
                activity.currentUserName = ""
                DebugLogger.info("Credentials", "Cleared all credentials")
                successResponse(0, "تم مسح البيانات وجلسة التطبيق")
            } catch (e: Exception) {
                DebugLogger.logException("Credentials", e)
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 12. تقارير الوقود والصلاحية والرسائل
        // ============================================================

        @JavascriptInterface
        fun getExpirySoonProducts(days: Int): String {
            DebugLogger.info("WebAppInterface", "getExpirySoonProducts called")
            if (!checkPermission("inventory", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponse(db.getExpirySoonProducts(days))
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun extendProductExpiry(id: Long, newDate: String): String {
            DebugLogger.info("WebAppInterface", "extendProductExpiry called")
            if (!checkPermission("inventory", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.extendProductExpiry(id, newDate)
                successResponse(rows > 0, if (rows > 0) "تم تمديد الصلاحية بنجاح" else "لم يتم العثور على المنتج")
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun markProductExpired(id: Long): String {
            DebugLogger.info("WebAppInterface", "markProductExpired called")
            if (!checkPermission("inventory", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.markProductExpired(id)
                successResponse(rows > 0, if (rows > 0) "تم تمييز المنتج كمنتهي الصلاحية" else "لم يتم العثور على المنتج")
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getFuelReport(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "getFuelReport called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(if (jsonData.isBlank()) "{}" else jsonData)
                dataResponse(db.getFuelReport(data))
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getFuelTransactionDetails(id: Long, type: String): String {
            DebugLogger.info("WebAppInterface", "getFuelTransactionDetails called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val details = db.getFuelTransactionDetails(id, type)
                if (details != null) dataResponse(details) else errorResponse("لم يتم العثور على التفاصيل")
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getSmsLogs(): String {
            DebugLogger.info("WebAppInterface", "getSmsLogs called")
            if (!checkPermission("notifications", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponse(db.getSmsLogs())
            } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getFuelInventoryReconciliation(jsonData: String): String {
            DebugLogger.info("WebAppInterface", "getFuelInventoryReconciliation called")
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(if (jsonData.isBlank()) "{}" else jsonData)
                dataResponse(db.getFuelInventoryReconciliation(data))
            } catch (e: Exception) { errorResponse(e.message) }
        }



        private fun reportCacheResponse(
            db: DatabaseHelper,
            cacheKey: String,
            params: JSONObject,
            ttlSeconds: Long,
            loader: () -> JSONObject
        ): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val userId = activity.currentUserId
            val stationId = getCurrentStationId(db, userId)
            return try {
                val payload = loader()
                val cacheMeta = try {
                    db.putReportCache(cacheKey, params.toString(), userId, stationId, payload.toString(), ttlSeconds)
                } catch (cacheError: Exception) {
                    DebugLogger.warn("ReportCache", "Cache write skipped for $cacheKey: ${cacheError.message}")
                    JSONObject().apply { put("source", "sqlite"); put("read_only", false); put("stale", false) }
                }
                payload.put("cache", cacheMeta)
                payload.toString()
            } catch (liveError: Exception) {
                val cached = try { db.getReportCache(cacheKey, params.toString(), userId, stationId) } catch (cacheReadError: Exception) { null }
                if (cached != null) {
                    val payload = JSONObject(cached.optString("payload_json", "{}"))
                    payload.put("cache", cached.optJSONObject("meta") ?: JSONObject().apply { put("source", "cache"); put("read_only", true); put("stale", true) })
                    payload.put("message", "تم عرض آخر نسخة مؤقتة للقراءة فقط؛ لم تُنفّذ أي عملية محاسبية")
                    payload.toString()
                } else {
                    throw liveError
                }
            }
        }

        private fun invalidateCurrentReportCache(db: DatabaseHelper, userId: Long) {
            try { db.invalidateReportCache(userId, getCurrentStationId(db, userId)) } catch (e: Exception) { DebugLogger.warn("ReportCache", "Invalidation failed: ${e.message}") }
        }

        // ========================================================================
        // ACCOUNTING_REPORTS_V1_BRIDGE: typed contracts for accounting screens.
        // ========================================================================
        @JavascriptInterface
        fun getJournalEntries(jsonData: String): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة القيود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val params = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("معاملات التقرير غير صالحة") }
            return try {
                reportCacheResponse(db, "journal_entries", params, 900L) {
                    val page = db.getJournalEntries(params)
                    JSONObject().apply { put("success", true); put("data", page.optJSONArray("entries") ?: JSONArray()); put("total", page.optInt("total", 0)); put("stats", page.optJSONObject("stats") ?: JSONObject()) }
                }
            } catch (e: Exception) { DebugLogger.logException("JournalEntries", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getJournalItems(jsonData: String): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة بنود القيود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val params = JSONObject()
            return try { reportCacheResponse(db, "journal_items", params, 900L) { JSONObject().apply { put("success", true); put("data", db.getJournalItems()) } } }
            catch (e: Exception) { DebugLogger.logException("JournalItems", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getNextEntryNumber(): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة رقم القيد")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { JSONObject().apply { put("success", true); put("data", JSONObject().put("next_number", db.getNextJournalEntryNumber())) }.toString() } catch (e: Exception) { errorResponse(e.message) }
        }

        @JavascriptInterface
        fun saveJournalEntry(jsonData: String): String {
            val payload = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("بيانات القيد غير صالحة") }
            val permission = if (payload.optLong("id", 0L) > 0) "update" else "create"
            if (!checkPermission("accounting", permission)) return errorResponse("لا تملك صلاحية حفظ القيود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try { val saved = db.saveJournalEntry(payload, activity.currentUserId); invalidateCurrentReportCache(db, activity.currentUserId); successResponse(saved, "تم حفظ القيد فعلياً في SQLite") } catch (e: Exception) { DebugLogger.logException("JournalSave", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun updateJournalEntry(jsonData: String): String = saveJournalEntry(jsonData)

        @JavascriptInterface
        fun deleteJournalEntry(id: Long): String {
            if (!checkPermission("accounting", "delete")) return errorResponse("لا تملك صلاحية حذف القيود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try { val rows = db.deleteJournalEntry(id, activity.currentUserId); if (rows > 0) invalidateCurrentReportCache(db, activity.currentUserId); if (rows > 0) successResponse(true, "تم حذف القيد من SQLite") else errorResponse("لم يتم حذف القيد") } catch (e: Exception) { DebugLogger.logException("JournalDelete", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun postJournalEntry(id: Long): String {
            if (!checkPermission("accounting", "update")) return errorResponse("لا تملك صلاحية ترحيل القيود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try { val rows = db.postJournalEntry(id, activity.currentUserId); if (rows > 0) invalidateCurrentReportCache(db, activity.currentUserId); if (rows > 0) successResponse(true, "تم ترحيل القيد في SQLite") else errorResponse("لم يتم ترحيل القيد") } catch (e: Exception) { DebugLogger.logException("JournalPost", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun reverseJournalEntry(id: Long, reason: String): String {
            if (!checkPermission("accounting", "update")) return errorResponse("لا تملك صلاحية إلغاء القيود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try { val reversed = db.reverseJournalEntry(id, reason, activity.currentUserId); invalidateCurrentReportCache(db, activity.currentUserId); successResponse(reversed, "تم إنشاء القيد العكسي وتحديث القيد الأصلي") } catch (e: Exception) { DebugLogger.logException("JournalReverse", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getJournalEntryDetails(id: Long): String {
            if (!checkPermission("accounting", "read")) return errorResponse("لا تملك صلاحية قراءة تفاصيل القيد")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val params = JSONObject().put("id", id)
            return try { reportCacheResponse(db, "journal_entry_details", params, 900L) { db.getJournalEntryDetails(id)?.let { dataResponseObject(it) } ?: throw IllegalArgumentException("القيد غير موجود") } }
            catch (e: Exception) { DebugLogger.logException("JournalDetails", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun generateJournalReport(jsonData: String): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية قراءة تقارير القيود")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val params = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("معاملات التقرير غير صالحة") }
            return try { reportCacheResponse(db, "journal_report", params, 3600L) { dataResponseObject(db.generateJournalReport(params)) } }
            catch (e: Exception) { DebugLogger.logException("JournalReport", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getKPIDashboard(jsonData: String): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية قراءة مؤشرات الأداء")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val params = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("معاملات التقرير غير صالحة") }
            return try { reportCacheResponse(db, "kpi_dashboard", params, 900L) { dataResponseObject(db.getKPIDashboard(params)) } }
            catch (e: Exception) { DebugLogger.logException("KPIDashboard", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getKPIDetails(code: String): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية قراءة تفاصيل المؤشر")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val params = JSONObject().put("kpi_code", code)
            return try { reportCacheResponse(db, "kpi_details", params, 900L) { db.getKPIDetails(code)?.let { dataResponseObject(it) } ?: throw IllegalArgumentException("المؤشر غير موجود") } }
            catch (e: Exception) { DebugLogger.logException("KPIDetails", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getLedgerStats(): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية قراءة إحصائيات الأستاذ")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { reportCacheResponse(db, "ledger_stats", JSONObject(), 900L) { dataResponseObject(db.getLedgerStats()) } }
            catch (e: Exception) { DebugLogger.logException("LedgerStats", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getLedgerEntries(jsonData: String): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية قراءة دفتر الأستاذ")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val params = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("معاملات التقرير غير صالحة") }
            return try { reportCacheResponse(db, "ledger_entries", params, 3600L) { dataResponseObject(db.getLedgerEntries(params)) } }
            catch (e: Exception) { DebugLogger.logException("LedgerEntries", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun generateLedgerReport(jsonData: String): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية قراءة تقارير الأستاذ")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            val params = try { JSONObject(jsonData.ifBlank { "{}" }) } catch (e: Exception) { return errorResponse("معاملات التقرير غير صالحة") }
            return try { reportCacheResponse(db, "ledger_report", params, 3600L) { dataResponseObject(db.generateLedgerReport(params)) } }
            catch (e: Exception) { DebugLogger.logException("LedgerReport", e); errorResponse(e.message) }
        }


        @JavascriptInterface
        fun printCurrentPage(): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية طباعة التقارير")
            return try {
                runOnUiThread {
                    val manager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    val currentWebView = webView
                    if (manager == null || currentWebView == null) {
                        Toast.makeText(this@MainActivity, "خدمة الطباعة غير متاحة حالياً", Toast.LENGTH_SHORT).show()
                    } else {
                        val adapter = currentWebView.createPrintDocumentAdapter("accounting-report")
                        manager.print(
                            "تقرير المحاسبة",
                            adapter,
                            PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                .build()
                        )
                    }
                }
                successResponse(true, "تم فتح نافذة الطباعة")
            } catch (e: Exception) {
                DebugLogger.logException("NativePrint", e)
                errorResponse(e.message)
            }
        }


        private fun operationalJson(jsonData: String): JSONObject = try {
            JSONObject(jsonData.ifBlank { "{}" })
        } catch (e: Exception) { throw IllegalArgumentException("بيانات الشاشة غير صالحة") }

        private fun operationalList(permission: String, key: String, jsonData: String): String {
            if (!checkPermission(permission, "read")) return errorResponse("لا تملك صلاحية قراءة هذه الشاشة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getOperationalRows(key, operationalJson(jsonData))) }
            catch (e: Exception) { DebugLogger.logException("OperationalList-$key", e); errorResponse(e.message) }
        }

        private fun operationalReport(permission: String, key: String, jsonData: String): String {
            if (!checkPermission(permission, "read")) return errorResponse("لا تملك صلاحية التقرير")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponseObject(db.getOperationalReport(key, operationalJson(jsonData))) .toString() }
            catch (e: Exception) { DebugLogger.logException("OperationalReport-$key", e); errorResponse(e.message) }
        }

        private fun operationalSave(permission: String, key: String, jsonData: String): String {
            if (!checkPermission(permission, "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { successResponse(db.saveOperationalRecord(key, operationalJson(jsonData), activity.currentUserId), "تم الحفظ فعلياً") }
            catch (e: Exception) { DebugLogger.logException("OperationalSave-$key", e); errorResponse(e.message) }
        }

        private fun operationalUpdate(permission: String, key: String, id: Long, jsonData: String): String {
            if (!checkPermission(permission, "update")) return errorResponse("لا تملك صلاحية التعديل")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.updateOperationalRecord(key, id, operationalJson(jsonData), activity.currentUserId); successResponse(rows > 0, if (rows > 0) "تم التعديل فعلياً" else "لم يتم العثور على السجل") }
            catch (e: Exception) { DebugLogger.logException("OperationalUpdate-$key", e); errorResponse(e.message) }
        }

        private fun operationalDelete(permission: String, key: String, id: Long): String {
            if (!checkPermission(permission, "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.deleteOperationalRecord(key, id); successResponse(rows > 0, if (rows > 0) "تم الحذف فعلياً" else "لم يتم العثور على السجل") }
            catch (e: Exception) { DebugLogger.logException("OperationalDelete-$key", e); errorResponse(e.message) }
        }

        private fun operationalResolve(permission: String, key: String, id: Long, note: String): String {
            if (!checkPermission(permission, "update")) return errorResponse("لا تملك صلاحية الاعتماد")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { val rows = db.resolveOperationalRecord(key, id, note); successResponse(rows > 0, if (rows > 0) "تم تنفيذ العملية فعلياً" else "لم يتم العثور على السجل") }
            catch (e: Exception) { DebugLogger.logException("OperationalResolve-$key", e); errorResponse(e.message) }
        }

        // ============================================================
        // Tasks Bridge - explicit allow-list for tasks.html
        // ============================================================

        @JavascriptInterface
        fun getPendingTasks(jsonData: String = "{}"): String {
            if (!checkPermission("tasks", "read")) return errorResponse("لا تملك صلاحية قراءة المهام")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.getPendingTasks(operationalJson(jsonData))) }
            catch (e: Exception) { DebugLogger.logException("TasksList", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun addTask(jsonData: String): String {
            if (!checkPermission("tasks", "create")) return errorResponse("لا تملك صلاحية إضافة المهام")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val id = db.addTask(operationalJson(jsonData), activity.currentUserId)
                TaskNotificationManager.synchronizeTask(activity.applicationContext, id)
                successResponse(id, "تمت إضافة المهمة فعلياً")
            } catch (e: Exception) {
                DebugLogger.logException("TaskAdd", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateTask(id: Long, jsonData: String): String {
            if (!checkPermission("tasks", "update")) return errorResponse("لا تملك صلاحية تحديث المهام")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.updateTask(id, operationalJson(jsonData), activity.currentUserId)
                if (rows > 0) TaskNotificationManager.synchronizeTask(activity.applicationContext, id)
                successResponse(rows > 0, if (rows > 0) "تم تحديث المهمة فعلياً" else "لم يتم العثور على المهمة")
            } catch (e: Exception) {
                DebugLogger.logException("TaskUpdate", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun archiveTask(id: Long): String {
            if (!checkPermission("tasks", "delete")) return errorResponse("لا تملك صلاحية أرشفة المهام")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.archiveTask(id, activity.currentUserId)
                if (rows > 0) TaskNotificationManager.cancelTaskNotification(activity.applicationContext, id)
                successResponse(rows > 0, if (rows > 0) "تمت أرشفة المهمة فعلياً" else "لم يتم العثور على المهمة")
            } catch (e: Exception) {
                DebugLogger.logException("TaskArchive", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun restoreTask(id: Long): String {
            if (!checkPermission("tasks", "update")) return errorResponse("لا تملك صلاحية استعادة المهام")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.restoreTask(id, activity.currentUserId)
                if (rows > 0) TaskNotificationManager.synchronizeTask(activity.applicationContext, id)
                successResponse(rows > 0, if (rows > 0) "تمت استعادة المهمة فعلياً" else "لم يتم العثور على المهمة")
            } catch (e: Exception) {
                DebugLogger.logException("TaskRestore", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun resolveTask(id: Long): String {
            if (!checkPermission("tasks", "update")) return errorResponse("لا تملك صلاحية حل المهام")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.resolveTask(id, activity.currentUserId)
                if (rows > 0) TaskNotificationManager.cancelTaskNotification(activity.applicationContext, id)
                successResponse(rows > 0, if (rows > 0) "تم حل المهمة فعلياً" else "لم يتم العثور على المهمة")
            } catch (e: Exception) {
                DebugLogger.logException("TaskResolve", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteTask(id: Long): String {
            if (!checkPermission("tasks", "delete")) return errorResponse("لا تملك صلاحية حذف المهام")
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteTask(id, activity.currentUserId)
                if (rows > 0) TaskNotificationManager.cancelTaskNotification(activity.applicationContext, id)
                successResponse(rows > 0, if (rows > 0) "تم حذف المهمة فعلياً" else "لم يتم العثور على المهمة")
            } catch (e: Exception) {
                DebugLogger.logException("TaskDelete", e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun generateTaskReport(jsonData: String = "{}"): String {
            if (!checkPermission("tasks", "read")) return errorResponse("لا تملك صلاحية تقرير المهام")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try { dataResponse(db.generateTaskReport(operationalJson(jsonData))) }
            catch (e: Exception) { DebugLogger.logException("TaskReport", e); errorResponse(e.message) }
        }

        @JavascriptInterface
        fun getStationRecords(jsonData: String = "{}") = operationalList("stations", "stations", jsonData)
        @JavascriptInterface
        fun generateStationReport(jsonData: String = "{}") = operationalReport("stations", "stations", jsonData)
        @JavascriptInterface
        fun saveStationRecord(jsonData: String) = operationalSave("stations", "stations", jsonData)
        @JavascriptInterface
        fun updateStationRecord(id: Long, jsonData: String) = operationalUpdate("stations", "stations", id, jsonData)
        @JavascriptInterface
        fun deleteStationRecord(id: Long) = operationalDelete("stations", "stations", id)
        @JavascriptInterface
        fun resolveStationRecord(id: Long, note: String = "") = operationalResolve("stations", "stations", id, note)

        @JavascriptInterface
        fun getExchangeRateRecords(jsonData: String = "{}") = operationalList("finance", "exchange_rates", jsonData)
        @JavascriptInterface
        fun generateExchangeRateReport(jsonData: String = "{}") = operationalReport("finance", "exchange_rates", jsonData)
        @JavascriptInterface
        fun saveExchangeRateRecord(jsonData: String) = operationalSave("finance", "exchange_rates", jsonData)
        @JavascriptInterface
        fun updateExchangeRateRecord(id: Long, jsonData: String) = operationalUpdate("finance", "exchange_rates", id, jsonData)
        @JavascriptInterface
        fun deleteExchangeRateRecord(id: Long) = operationalDelete("finance", "exchange_rates", id)
        @JavascriptInterface
        fun resolveExchangeRateRecord(id: Long, note: String = "") = operationalResolve("finance", "exchange_rates", id, note)

        @JavascriptInterface
        fun getBadDebtRecords(jsonData: String = "{}") = operationalList("finance", "bad_debts", jsonData)
        @JavascriptInterface
        fun generateBadDebtReport(jsonData: String = "{}") = operationalReport("finance", "bad_debts", jsonData)
        @JavascriptInterface
        fun saveBadDebtRecord(jsonData: String) = operationalSave("finance", "bad_debts", jsonData)
        @JavascriptInterface
        fun updateBadDebtRecord(id: Long, jsonData: String) = operationalUpdate("finance", "bad_debts", id, jsonData)
        @JavascriptInterface
        fun deleteBadDebtRecord(id: Long) = operationalDelete("finance", "bad_debts", id)
        @JavascriptInterface
        fun resolveBadDebtRecord(id: Long, note: String = "") = operationalResolve("finance", "bad_debts", id, note)

        @JavascriptInterface
        fun getVehicleRecords(jsonData: String = "{}") = operationalList("vehicles", "vehicles", jsonData)
        @JavascriptInterface
        fun generateVehicleReport(jsonData: String = "{}") = operationalReport("vehicles", "vehicles", jsonData)
        @JavascriptInterface
        fun saveVehicleRecord(jsonData: String) = operationalSave("vehicles", "vehicles", jsonData)
        @JavascriptInterface
        fun updateVehicleRecord(id: Long, jsonData: String) = operationalUpdate("vehicles", "vehicles", id, jsonData)
        @JavascriptInterface
        fun deleteVehicleRecord(id: Long) = operationalDelete("vehicles", "vehicles", id)
        @JavascriptInterface
        fun resolveVehicleRecord(id: Long, note: String = "") = operationalResolve("vehicles", "vehicles", id, note)

        @JavascriptInterface
        fun getDriverRecords(jsonData: String = "{}") = operationalList("vehicles", "drivers", jsonData)
        @JavascriptInterface
        fun generateDriverReport(jsonData: String = "{}") = operationalReport("vehicles", "drivers", jsonData)
        @JavascriptInterface
        fun saveDriverRecord(jsonData: String) = operationalSave("vehicles", "drivers", jsonData)
        @JavascriptInterface
        fun updateDriverRecord(id: Long, jsonData: String) = operationalUpdate("vehicles", "drivers", id, jsonData)
        @JavascriptInterface
        fun deleteDriverRecord(id: Long) = operationalDelete("vehicles", "drivers", id)
        @JavascriptInterface
        fun resolveDriverRecord(id: Long, note: String = "") = operationalResolve("vehicles", "drivers", id, note)

        @JavascriptInterface
        fun getVehicleLocationRecords(jsonData: String = "{}") = operationalList("vehicles", "vehicle_locations", jsonData)
        @JavascriptInterface
        fun generateVehicleLocationReport(jsonData: String = "{}") = operationalReport("vehicles", "vehicle_locations", jsonData)
        @JavascriptInterface
        fun saveVehicleLocationRecord(jsonData: String) = operationalSave("vehicles", "vehicle_locations", jsonData)
        @JavascriptInterface
        fun updateVehicleLocationRecord(id: Long, jsonData: String) = operationalUpdate("vehicles", "vehicle_locations", id, jsonData)
        @JavascriptInterface
        fun deleteVehicleLocationRecord(id: Long) = operationalDelete("vehicles", "vehicle_locations", id)
        @JavascriptInterface
        fun resolveVehicleLocationRecord(id: Long, note: String = "") = operationalResolve("vehicles", "vehicle_locations", id, note)

        @JavascriptInterface
        fun getVehicleTripRecords(jsonData: String = "{}") = operationalList("vehicles", "vehicle_trips", jsonData)
        @JavascriptInterface
        fun generateVehicleTripReport(jsonData: String = "{}") = operationalReport("vehicles", "vehicle_trips", jsonData)
        @JavascriptInterface
        fun saveVehicleTripRecord(jsonData: String) = operationalSave("vehicles", "vehicle_trips", jsonData)
        @JavascriptInterface
        fun updateVehicleTripRecord(id: Long, jsonData: String) = operationalUpdate("vehicles", "vehicle_trips", id, jsonData)
        @JavascriptInterface
        fun deleteVehicleTripRecord(id: Long) = operationalDelete("vehicles", "vehicle_trips", id)
        @JavascriptInterface
        fun resolveVehicleTripRecord(id: Long, note: String = "") = operationalResolve("vehicles", "vehicle_trips", id, note)

        @JavascriptInterface
        fun getVehicleExpenseRecords(jsonData: String = "{}") = operationalList("vehicles", "vehicle_expenses", jsonData)
        @JavascriptInterface
        fun generateVehicleExpenseReport(jsonData: String = "{}") = operationalReport("vehicles", "vehicle_expenses", jsonData)
        @JavascriptInterface
        fun saveVehicleExpenseRecord(jsonData: String) = operationalSave("vehicles", "vehicle_expenses", jsonData)
        @JavascriptInterface
        fun updateVehicleExpenseRecord(id: Long, jsonData: String) = operationalUpdate("vehicles", "vehicle_expenses", id, jsonData)
        @JavascriptInterface
        fun deleteVehicleExpenseRecord(id: Long) = operationalDelete("vehicles", "vehicle_expenses", id)
        @JavascriptInterface
        fun resolveVehicleExpenseRecord(id: Long, note: String = "") = operationalResolve("vehicles", "vehicle_expenses", id, note)

        @JavascriptInterface
        fun getFuelTypeRecords(jsonData: String = "{}") = operationalList("products", "fuel_types", jsonData)
        @JavascriptInterface
        fun generateFuelTypeReport(jsonData: String = "{}") = operationalReport("products", "fuel_types", jsonData)
        @JavascriptInterface
        fun saveFuelTypeRecord(jsonData: String) = operationalSave("products", "fuel_types", jsonData)
        @JavascriptInterface
        fun updateFuelTypeRecord(id: Long, jsonData: String) = operationalUpdate("products", "fuel_types", id, jsonData)
        @JavascriptInterface
        fun deleteFuelTypeRecord(id: Long) = operationalDelete("products", "fuel_types", id)
        @JavascriptInterface
        fun resolveFuelTypeRecord(id: Long, note: String = "") = operationalResolve("products", "fuel_types", id, note)

        @JavascriptInterface
        fun getPriceListRecords(jsonData: String = "{}") = operationalList("products", "price_lists", jsonData)
        @JavascriptInterface
        fun generatePriceListReport(jsonData: String = "{}") = operationalReport("products", "price_lists", jsonData)
        @JavascriptInterface
        fun savePriceListRecord(jsonData: String) = operationalSave("products", "price_lists", jsonData)
        @JavascriptInterface
        fun updatePriceListRecord(id: Long, jsonData: String) = operationalUpdate("products", "price_lists", id, jsonData)
        @JavascriptInterface
        fun deletePriceListRecord(id: Long) = operationalDelete("products", "price_lists", id)
        @JavascriptInterface
        fun resolvePriceListRecord(id: Long, note: String = "") = operationalResolve("products", "price_lists", id, note)

        @JavascriptInterface
        fun getPriceListItemRecords(jsonData: String = "{}") = operationalList("products", "price_list_items", jsonData)
        @JavascriptInterface
        fun generatePriceListItemReport(jsonData: String = "{}") = operationalReport("products", "price_list_items", jsonData)
        @JavascriptInterface
        fun savePriceListItemRecord(jsonData: String) = operationalSave("products", "price_list_items", jsonData)
        @JavascriptInterface
        fun updatePriceListItemRecord(id: Long, jsonData: String) = operationalUpdate("products", "price_list_items", id, jsonData)
        @JavascriptInterface
        fun deletePriceListItemRecord(id: Long) = operationalDelete("products", "price_list_items", id)
        @JavascriptInterface
        fun resolvePriceListItemRecord(id: Long, note: String = "") = operationalResolve("products", "price_list_items", id, note)

        @JavascriptInterface
        fun getPriceHistoryRecords(jsonData: String = "{}") = operationalList("products", "price_history", jsonData)
        @JavascriptInterface
        fun generatePriceHistoryReport(jsonData: String = "{}") = operationalReport("products", "price_history", jsonData)
        @JavascriptInterface
        fun savePriceHistoryRecord(jsonData: String) = operationalSave("products", "price_history", jsonData)
        @JavascriptInterface
        fun updatePriceHistoryRecord(id: Long, jsonData: String) = operationalUpdate("products", "price_history", id, jsonData)
        @JavascriptInterface
        fun deletePriceHistoryRecord(id: Long) = operationalDelete("products", "price_history", id)
        @JavascriptInterface
        fun resolvePriceHistoryRecord(id: Long, note: String = "") = operationalResolve("products", "price_history", id, note)

        @JavascriptInterface
        fun getTankRecords(jsonData: String = "{}") = operationalList("tanks", "tanks", jsonData)
        @JavascriptInterface
        fun generateTankReport(jsonData: String = "{}") = operationalReport("tanks", "tanks", jsonData)
        @JavascriptInterface
        fun saveTankRecord(jsonData: String) = operationalSave("tanks", "tanks", jsonData)
        @JavascriptInterface
        fun updateTankRecord(id: Long, jsonData: String) = operationalUpdate("tanks", "tanks", id, jsonData)
        @JavascriptInterface
        fun deleteTankRecord(id: Long) = operationalDelete("tanks", "tanks", id)
        @JavascriptInterface
        fun resolveTankRecord(id: Long, note: String = "") = operationalResolve("tanks", "tanks", id, note)

        @JavascriptInterface
        fun getPumpRecords(jsonData: String = "{}") = operationalList("pumps", "pumps", jsonData)
        @JavascriptInterface
        fun generatePumpReport(jsonData: String = "{}") = operationalReport("pumps", "pumps", jsonData)
        @JavascriptInterface
        fun savePumpRecord(jsonData: String) = operationalSave("pumps", "pumps", jsonData)
        @JavascriptInterface
        fun updatePumpRecord(id: Long, jsonData: String) = operationalUpdate("pumps", "pumps", id, jsonData)
        @JavascriptInterface
        fun deletePumpRecord(id: Long) = operationalDelete("pumps", "pumps", id)
        @JavascriptInterface
        fun resolvePumpRecord(id: Long, note: String = "") = operationalResolve("pumps", "pumps", id, note)

        @JavascriptInterface
        fun getMeterReadingRecords(jsonData: String = "{}") = operationalList("tanks", "meter_readings", jsonData)
        @JavascriptInterface
        fun generateMeterReadingReport(jsonData: String = "{}") = operationalReport("tanks", "meter_readings", jsonData)
        @JavascriptInterface
        fun saveMeterReadingRecord(jsonData: String) = operationalSave("tanks", "meter_readings", jsonData)
        @JavascriptInterface
        fun updateMeterReadingRecord(id: Long, jsonData: String) = operationalUpdate("tanks", "meter_readings", id, jsonData)
        @JavascriptInterface
        fun deleteMeterReadingRecord(id: Long) = operationalDelete("tanks", "meter_readings", id)
        @JavascriptInterface
        fun resolveMeterReadingRecord(id: Long, note: String = "") = operationalResolve("tanks", "meter_readings", id, note)

        @JavascriptInterface
        fun getTankLevelRecords(jsonData: String = "{}") = operationalList("tanks", "tank_level_log", jsonData)
        @JavascriptInterface
        fun generateTankLevelReport(jsonData: String = "{}") = operationalReport("tanks", "tank_level_log", jsonData)
        @JavascriptInterface
        fun saveTankLevelRecord(jsonData: String) = operationalSave("tanks", "tank_level_log", jsonData)
        @JavascriptInterface
        fun updateTankLevelRecord(id: Long, jsonData: String) = operationalUpdate("tanks", "tank_level_log", id, jsonData)
        @JavascriptInterface
        fun deleteTankLevelRecord(id: Long) = operationalDelete("tanks", "tank_level_log", id)
        @JavascriptInterface
        fun resolveTankLevelRecord(id: Long, note: String = "") = operationalResolve("tanks", "tank_level_log", id, note)

        @JavascriptInterface
        fun getFuelQualityRecords(jsonData: String = "{}") = operationalList("tanks", "fuel_quality_tests", jsonData)
        @JavascriptInterface
        fun generateFuelQualityReport(jsonData: String = "{}") = operationalReport("tanks", "fuel_quality_tests", jsonData)
        @JavascriptInterface
        fun saveFuelQualityRecord(jsonData: String) = operationalSave("tanks", "fuel_quality_tests", jsonData)
        @JavascriptInterface
        fun updateFuelQualityRecord(id: Long, jsonData: String) = operationalUpdate("tanks", "fuel_quality_tests", id, jsonData)
        @JavascriptInterface
        fun deleteFuelQualityRecord(id: Long) = operationalDelete("tanks", "fuel_quality_tests", id)
        @JavascriptInterface
        fun resolveFuelQualityRecord(id: Long, note: String = "") = operationalResolve("tanks", "fuel_quality_tests", id, note)

        @JavascriptInterface
        fun getCalibrationRecords(jsonData: String = "{}") = operationalList("tanks", "calibration_records", jsonData)
        @JavascriptInterface
        fun generateCalibrationReport(jsonData: String = "{}") = operationalReport("tanks", "calibration_records", jsonData)
        @JavascriptInterface
        fun saveCalibrationRecord(jsonData: String) = operationalSave("tanks", "calibration_records", jsonData)
        @JavascriptInterface
        fun updateCalibrationRecord(id: Long, jsonData: String) = operationalUpdate("tanks", "calibration_records", id, jsonData)
        @JavascriptInterface
        fun deleteCalibrationRecord(id: Long) = operationalDelete("tanks", "calibration_records", id)
        @JavascriptInterface
        fun resolveCalibrationRecord(id: Long, note: String = "") = operationalResolve("tanks", "calibration_records", id, note)

        @JavascriptInterface
        fun getWarehouseRecords(jsonData: String = "{}") = operationalList("inventory", "warehouses", jsonData)
        @JavascriptInterface
        fun generateWarehouseReport(jsonData: String = "{}") = operationalReport("inventory", "warehouses", jsonData)
        @JavascriptInterface
        fun saveWarehouseRecord(jsonData: String) = operationalSave("inventory", "warehouses", jsonData)
        @JavascriptInterface
        fun updateWarehouseRecord(id: Long, jsonData: String) = operationalUpdate("inventory", "warehouses", id, jsonData)
        @JavascriptInterface
        fun deleteWarehouseRecord(id: Long) = operationalDelete("inventory", "warehouses", id)
        @JavascriptInterface
        fun resolveWarehouseRecord(id: Long, note: String = "") = operationalResolve("inventory", "warehouses", id, note)

        @JavascriptInterface
        fun getInventoryMovementRecords(jsonData: String = "{}") = operationalList("inventory", "inventory_movements", jsonData)
        @JavascriptInterface
        fun generateInventoryMovementReport(jsonData: String = "{}") = operationalReport("inventory", "inventory_movements", jsonData)
        @JavascriptInterface
        fun saveInventoryMovementRecord(jsonData: String) = operationalSave("inventory", "inventory_movements", jsonData)
        @JavascriptInterface
        fun updateInventoryMovementRecord(id: Long, jsonData: String) = operationalUpdate("inventory", "inventory_movements", id, jsonData)
        @JavascriptInterface
        fun deleteInventoryMovementRecord(id: Long) = operationalDelete("inventory", "inventory_movements", id)
        @JavascriptInterface
        fun resolveInventoryMovementRecord(id: Long, note: String = "") = operationalResolve("inventory", "inventory_movements", id, note)

        @JavascriptInterface
        fun getStockAlertRecords(jsonData: String = "{}") = operationalList("inventory", "stock_alerts", jsonData)
        @JavascriptInterface
        fun generateStockAlertReport(jsonData: String = "{}") = operationalReport("inventory", "stock_alerts", jsonData)
        @JavascriptInterface
        fun saveStockAlertRecord(jsonData: String) = operationalSave("inventory", "stock_alerts", jsonData)
        @JavascriptInterface
        fun updateStockAlertRecord(id: Long, jsonData: String) = operationalUpdate("inventory", "stock_alerts", id, jsonData)
        @JavascriptInterface
        fun deleteStockAlertRecord(id: Long) = operationalDelete("inventory", "stock_alerts", id)
        @JavascriptInterface
        fun resolveStockAlertRecord(id: Long, note: String = "") = operationalResolve("inventory", "stock_alerts", id, note)

        @JavascriptInterface
        fun getStocktakeRecords(jsonData: String = "{}") = operationalList("inventory", "stocktakes", jsonData)
        @JavascriptInterface
        fun generateStocktakeReport(jsonData: String = "{}") = operationalReport("inventory", "stocktakes", jsonData)
        @JavascriptInterface
        fun saveStocktakeRecord(jsonData: String) = operationalSave("inventory", "stocktakes", jsonData)
        @JavascriptInterface
        fun updateStocktakeRecord(id: Long, jsonData: String) = operationalUpdate("inventory", "stocktakes", id, jsonData)
        @JavascriptInterface
        fun deleteStocktakeRecord(id: Long) = operationalDelete("inventory", "stocktakes", id)
        @JavascriptInterface
        fun resolveStocktakeRecord(id: Long, note: String = "") = operationalResolve("inventory", "stocktakes", id, note)

        @JavascriptInterface
        fun getStocktakeDetailRecords(jsonData: String = "{}") = operationalList("inventory", "stocktake_details", jsonData)
        @JavascriptInterface
        fun generateStocktakeDetailReport(jsonData: String = "{}") = operationalReport("inventory", "stocktake_details", jsonData)
        @JavascriptInterface
        fun saveStocktakeDetailRecord(jsonData: String) = operationalSave("inventory", "stocktake_details", jsonData)
        @JavascriptInterface
        fun updateStocktakeDetailRecord(id: Long, jsonData: String) = operationalUpdate("inventory", "stocktake_details", id, jsonData)
        @JavascriptInterface
        fun deleteStocktakeDetailRecord(id: Long) = operationalDelete("inventory", "stocktake_details", id)
        @JavascriptInterface
        fun resolveStocktakeDetailRecord(id: Long, note: String = "") = operationalResolve("inventory", "stocktake_details", id, note)

        @JavascriptInterface
        fun getShiftRecords(jsonData: String = "{}") = operationalList("sales", "shifts", jsonData)
        @JavascriptInterface
        fun generateShiftReport(jsonData: String = "{}") = operationalReport("sales", "shifts", jsonData)
        @JavascriptInterface
        fun saveShiftRecord(jsonData: String) = operationalSave("sales", "shifts", jsonData)
        @JavascriptInterface
        fun updateShiftRecord(id: Long, jsonData: String) = operationalUpdate("sales", "shifts", id, jsonData)
        @JavascriptInterface
        fun deleteShiftRecord(id: Long) = operationalDelete("sales", "shifts", id)
        @JavascriptInterface
        fun resolveShiftRecord(id: Long, note: String = "") = operationalResolve("sales", "shifts", id, note)

        @JavascriptInterface
        fun getSalesTransactionRecords(jsonData: String = "{}") = operationalList("sales", "sales_transactions", jsonData)
        @JavascriptInterface
        fun generateSalesTransactionReport(jsonData: String = "{}") = operationalReport("sales", "sales_transactions", jsonData)
        @JavascriptInterface
        fun saveSalesTransactionRecord(jsonData: String) = operationalSave("sales", "sales_transactions", jsonData)
        @JavascriptInterface
        fun updateSalesTransactionRecord(id: Long, jsonData: String) = operationalUpdate("sales", "sales_transactions", id, jsonData)
        @JavascriptInterface
        fun deleteSalesTransactionRecord(id: Long) = operationalDelete("sales", "sales_transactions", id)
        @JavascriptInterface
        fun resolveSalesTransactionRecord(id: Long, note: String = "") = operationalResolve("sales", "sales_transactions", id, note)

        @JavascriptInterface
        fun getDeliveryRecords(jsonData: String = "{}") = operationalList("sales", "deliveries", jsonData)
        @JavascriptInterface
        fun generateDeliveryReport(jsonData: String = "{}") = operationalReport("sales", "deliveries", jsonData)
        @JavascriptInterface
        fun saveDeliveryRecord(jsonData: String) = operationalSave("sales", "deliveries", jsonData)
        @JavascriptInterface
        fun updateDeliveryRecord(id: Long, jsonData: String) = operationalUpdate("sales", "deliveries", id, jsonData)
        @JavascriptInterface
        fun deleteDeliveryRecord(id: Long) = operationalDelete("sales", "deliveries", id)
        @JavascriptInterface
        fun resolveDeliveryRecord(id: Long, note: String = "") = operationalResolve("sales", "deliveries", id, note)

        @JavascriptInterface
        fun getFuelSaleRecords(jsonData: String = "{}") = operationalList("sales", "fuel_sales", jsonData)
        @JavascriptInterface
        fun generateFuelSaleReport(jsonData: String = "{}") = operationalReport("sales", "fuel_sales", jsonData)
        @JavascriptInterface
        fun saveFuelSaleRecord(jsonData: String) = operationalSave("sales", "fuel_sales", jsonData)
        @JavascriptInterface
        fun updateFuelSaleRecord(id: Long, jsonData: String) = operationalUpdate("sales", "fuel_sales", id, jsonData)
        @JavascriptInterface
        fun deleteFuelSaleRecord(id: Long) = operationalDelete("sales", "fuel_sales", id)
        @JavascriptInterface
        fun resolveFuelSaleRecord(id: Long, note: String = "") = operationalResolve("sales", "fuel_sales", id, note)

        @JavascriptInterface
        fun getPaymentRecords(jsonData: String = "{}") = operationalList("finance", "payments", jsonData)
        @JavascriptInterface
        fun generatePaymentReport(jsonData: String = "{}") = operationalReport("finance", "payments", jsonData)
        @JavascriptInterface
        fun savePaymentRecord(jsonData: String) = operationalSave("finance", "payments", jsonData)
        @JavascriptInterface
        fun updatePaymentRecord(id: Long, jsonData: String) = operationalUpdate("finance", "payments", id, jsonData)
        @JavascriptInterface
        fun deletePaymentRecord(id: Long) = operationalDelete("finance", "payments", id)
        @JavascriptInterface
        fun resolvePaymentRecord(id: Long, note: String = "") = operationalResolve("finance", "payments", id, note)

        @JavascriptInterface
        fun getReceiptRecords(jsonData: String = "{}") = operationalList("finance", "receipts", jsonData)
        @JavascriptInterface
        fun generateReceiptReport(jsonData: String = "{}") = operationalReport("finance", "receipts", jsonData)
        @JavascriptInterface
        fun saveReceiptRecord(jsonData: String) = operationalSave("finance", "receipts", jsonData)
        @JavascriptInterface
        fun updateReceiptRecord(id: Long, jsonData: String) = operationalUpdate("finance", "receipts", id, jsonData)
        @JavascriptInterface
        fun deleteReceiptRecord(id: Long) = operationalDelete("finance", "receipts", id)
        @JavascriptInterface
        fun resolveReceiptRecord(id: Long, note: String = "") = operationalResolve("finance", "receipts", id, note)

        @JavascriptInterface
        fun getCashBoxRecords(jsonData: String = "{}") = operationalList("finance", "cash_boxes", jsonData)
        @JavascriptInterface
        fun generateCashBoxReport(jsonData: String = "{}") = operationalReport("finance", "cash_boxes", jsonData)
        @JavascriptInterface
        fun saveCashBoxRecord(jsonData: String) = operationalSave("finance", "cash_boxes", jsonData)
        @JavascriptInterface
        fun updateCashBoxRecord(id: Long, jsonData: String) = operationalUpdate("finance", "cash_boxes", id, jsonData)
        @JavascriptInterface
        fun deleteCashBoxRecord(id: Long) = operationalDelete("finance", "cash_boxes", id)
        @JavascriptInterface
        fun resolveCashBoxRecord(id: Long, note: String = "") = operationalResolve("finance", "cash_boxes", id, note)

        @JavascriptInterface
        fun getCashMovementRecords(jsonData: String = "{}") = operationalList("finance", "cash_movements", jsonData)
        @JavascriptInterface
        fun generateCashMovementReport(jsonData: String = "{}") = operationalReport("finance", "cash_movements", jsonData)
        @JavascriptInterface
        fun saveCashMovementRecord(jsonData: String) = operationalSave("finance", "cash_movements", jsonData)
        @JavascriptInterface
        fun updateCashMovementRecord(id: Long, jsonData: String) = operationalUpdate("finance", "cash_movements", id, jsonData)
        @JavascriptInterface
        fun deleteCashMovementRecord(id: Long) = operationalDelete("finance", "cash_movements", id)
        @JavascriptInterface
        fun resolveCashMovementRecord(id: Long, note: String = "") = operationalResolve("finance", "cash_movements", id, note)

        @JavascriptInterface
        fun getExpenseCategoryRecords(jsonData: String = "{}") = operationalList("finance", "expense_categories", jsonData)
        @JavascriptInterface
        fun generateExpenseCategoryReport(jsonData: String = "{}") = operationalReport("finance", "expense_categories", jsonData)
        @JavascriptInterface
        fun saveExpenseCategoryRecord(jsonData: String) = operationalSave("finance", "expense_categories", jsonData)
        @JavascriptInterface
        fun updateExpenseCategoryRecord(id: Long, jsonData: String) = operationalUpdate("finance", "expense_categories", id, jsonData)
        @JavascriptInterface
        fun deleteExpenseCategoryRecord(id: Long) = operationalDelete("finance", "expense_categories", id)
        @JavascriptInterface
        fun resolveExpenseCategoryRecord(id: Long, note: String = "") = operationalResolve("finance", "expense_categories", id, note)

        @JavascriptInterface
        fun getExpenseRecords(jsonData: String = "{}") = operationalList("finance", "expenses", jsonData)
        @JavascriptInterface
        fun generateExpenseReport(jsonData: String = "{}") = operationalReport("finance", "expenses", jsonData)
        @JavascriptInterface
        fun saveExpenseRecord(jsonData: String) = operationalSave("finance", "expenses", jsonData)
        @JavascriptInterface
        fun updateExpenseRecord(id: Long, jsonData: String) = operationalUpdate("finance", "expenses", id, jsonData)
        @JavascriptInterface
        fun deleteExpenseRecord(id: Long) = operationalDelete("finance", "expenses", id)
        @JavascriptInterface
        fun resolveExpenseRecord(id: Long, note: String = "") = operationalResolve("finance", "expenses", id, note)

        @JavascriptInterface
        fun getBudgetRecords(jsonData: String = "{}") = operationalList("finance", "budgets", jsonData)
        @JavascriptInterface
        fun generateBudgetReport(jsonData: String = "{}") = operationalReport("finance", "budgets", jsonData)
        @JavascriptInterface
        fun saveBudgetRecord(jsonData: String) = operationalSave("finance", "budgets", jsonData)
        @JavascriptInterface
        fun updateBudgetRecord(id: Long, jsonData: String) = operationalUpdate("finance", "budgets", id, jsonData)
        @JavascriptInterface
        fun deleteBudgetRecord(id: Long) = operationalDelete("finance", "budgets", id)
        @JavascriptInterface
        fun resolveBudgetRecord(id: Long, note: String = "") = operationalResolve("finance", "budgets", id, note)

        @JavascriptInterface
        fun getCashDepositRecords(jsonData: String = "{}") = operationalList("finance", "cash_deposits", jsonData)
        @JavascriptInterface
        fun generateCashDepositReport(jsonData: String = "{}") = operationalReport("finance", "cash_deposits", jsonData)
        @JavascriptInterface
        fun saveCashDepositRecord(jsonData: String) = operationalSave("finance", "cash_deposits", jsonData)
        @JavascriptInterface
        fun updateCashDepositRecord(id: Long, jsonData: String) = operationalUpdate("finance", "cash_deposits", id, jsonData)
        @JavascriptInterface
        fun deleteCashDepositRecord(id: Long) = operationalDelete("finance", "cash_deposits", id)
        @JavascriptInterface
        fun resolveCashDepositRecord(id: Long, note: String = "") = operationalResolve("finance", "cash_deposits", id, note)

        @JavascriptInterface
        fun getEmployeeRecords(jsonData: String = "{}") = operationalList("hr", "employees", jsonData)
        @JavascriptInterface
        fun generateEmployeeReport(jsonData: String = "{}") = operationalReport("hr", "employees", jsonData)
        @JavascriptInterface
        fun saveEmployeeRecord(jsonData: String) = operationalSave("hr", "employees", jsonData)
        @JavascriptInterface
        fun updateEmployeeRecord(id: Long, jsonData: String) = operationalUpdate("hr", "employees", id, jsonData)
        @JavascriptInterface
        fun deleteEmployeeRecord(id: Long) = operationalDelete("hr", "employees", id)
        @JavascriptInterface
        fun resolveEmployeeRecord(id: Long, note: String = "") = operationalResolve("hr", "employees", id, note)

        @JavascriptInterface
        fun getAttendanceRecords(jsonData: String = "{}") = operationalList("hr", "attendance", jsonData)
        @JavascriptInterface
        fun generateAttendanceReport(jsonData: String = "{}") = operationalReport("hr", "attendance", jsonData)
        @JavascriptInterface
        fun saveAttendanceRecord(jsonData: String) = operationalSave("hr", "attendance", jsonData)
        @JavascriptInterface
        fun updateAttendanceRecord(id: Long, jsonData: String) = operationalUpdate("hr", "attendance", id, jsonData)
        @JavascriptInterface
        fun deleteAttendanceRecord(id: Long) = operationalDelete("hr", "attendance", id)
        @JavascriptInterface
        fun resolveAttendanceRecord(id: Long, note: String = "") = operationalResolve("hr", "attendance", id, note)

        @JavascriptInterface
        fun getPayrollRecords(jsonData: String = "{}") = operationalList("hr", "payroll", jsonData)
        @JavascriptInterface
        fun generatePayrollReport(jsonData: String = "{}") = operationalReport("hr", "payroll", jsonData)
        @JavascriptInterface
        fun savePayrollRecord(jsonData: String) = operationalSave("hr", "payroll", jsonData)
        @JavascriptInterface
        fun updatePayrollRecord(id: Long, jsonData: String) = operationalUpdate("hr", "payroll", id, jsonData)
        @JavascriptInterface
        fun deletePayrollRecord(id: Long) = operationalDelete("hr", "payroll", id)
        @JavascriptInterface
        fun resolvePayrollRecord(id: Long, note: String = "") = operationalResolve("hr", "payroll", id, note)

        @JavascriptInterface
        fun getEmployeePaymentRecords(jsonData: String = "{}") = operationalList("hr", "employee_payments", jsonData)
        @JavascriptInterface
        fun generateEmployeePaymentReport(jsonData: String = "{}") = operationalReport("hr", "employee_payments", jsonData)
        @JavascriptInterface
        fun saveEmployeePaymentRecord(jsonData: String) = operationalSave("hr", "employee_payments", jsonData)
        @JavascriptInterface
        fun updateEmployeePaymentRecord(id: Long, jsonData: String) = operationalUpdate("hr", "employee_payments", id, jsonData)
        @JavascriptInterface
        fun deleteEmployeePaymentRecord(id: Long) = operationalDelete("hr", "employee_payments", id)
        @JavascriptInterface
        fun resolveEmployeePaymentRecord(id: Long, note: String = "") = operationalResolve("hr", "employee_payments", id, note)

        @JavascriptInterface
        fun getFixedAssetRecords(jsonData: String = "{}") = operationalList("assets", "fixed_assets", jsonData)
        @JavascriptInterface
        fun generateFixedAssetReport(jsonData: String = "{}") = operationalReport("assets", "fixed_assets", jsonData)
        @JavascriptInterface
        fun saveFixedAssetRecord(jsonData: String) = operationalSave("assets", "fixed_assets", jsonData)
        @JavascriptInterface
        fun updateFixedAssetRecord(id: Long, jsonData: String) = operationalUpdate("assets", "fixed_assets", id, jsonData)
        @JavascriptInterface
        fun deleteFixedAssetRecord(id: Long) = operationalDelete("assets", "fixed_assets", id)
        @JavascriptInterface
        fun resolveFixedAssetRecord(id: Long, note: String = "") = operationalResolve("assets", "fixed_assets", id, note)

        @JavascriptInterface
        fun getDepreciationRecords(jsonData: String = "{}") = operationalList("assets", "depreciation", jsonData)
        @JavascriptInterface
        fun generateDepreciationReport(jsonData: String = "{}") = operationalReport("assets", "depreciation", jsonData)
        @JavascriptInterface
        fun saveDepreciationRecord(jsonData: String) = operationalSave("assets", "depreciation", jsonData)
        @JavascriptInterface
        fun updateDepreciationRecord(id: Long, jsonData: String) = operationalUpdate("assets", "depreciation", id, jsonData)
        @JavascriptInterface
        fun deleteDepreciationRecord(id: Long) = operationalDelete("assets", "depreciation", id)
        @JavascriptInterface
        fun resolveDepreciationRecord(id: Long, note: String = "") = operationalResolve("assets", "depreciation", id, note)

        @JavascriptInterface
        fun getMaintenanceRequestRecords(jsonData: String = "{}") = operationalList("maintenance", "maintenance_requests", jsonData)
        @JavascriptInterface
        fun generateMaintenanceRequestReport(jsonData: String = "{}") = operationalReport("maintenance", "maintenance_requests", jsonData)
        @JavascriptInterface
        fun saveMaintenanceRequestRecord(jsonData: String) = operationalSave("maintenance", "maintenance_requests", jsonData)
        @JavascriptInterface
        fun updateMaintenanceRequestRecord(id: Long, jsonData: String) = operationalUpdate("maintenance", "maintenance_requests", id, jsonData)
        @JavascriptInterface
        fun deleteMaintenanceRequestRecord(id: Long) = operationalDelete("maintenance", "maintenance_requests", id)
        @JavascriptInterface
        fun resolveMaintenanceRequestRecord(id: Long, note: String = "") = operationalResolve("maintenance", "maintenance_requests", id, note)

        @JavascriptInterface
        fun getMaintenanceScheduleRecords(jsonData: String = "{}") = operationalList("maintenance", "maintenance_schedule", jsonData)
        @JavascriptInterface
        fun generateMaintenanceScheduleReport(jsonData: String = "{}") = operationalReport("maintenance", "maintenance_schedule", jsonData)
        @JavascriptInterface
        fun saveMaintenanceScheduleRecord(jsonData: String) = operationalSave("maintenance", "maintenance_schedule", jsonData)
        @JavascriptInterface
        fun updateMaintenanceScheduleRecord(id: Long, jsonData: String) = operationalUpdate("maintenance", "maintenance_schedule", id, jsonData)
        @JavascriptInterface
        fun deleteMaintenanceScheduleRecord(id: Long) = operationalDelete("maintenance", "maintenance_schedule", id)
        @JavascriptInterface
        fun resolveMaintenanceScheduleRecord(id: Long, note: String = "") = operationalResolve("maintenance", "maintenance_schedule", id, note)

        @JavascriptInterface
        fun getMaintenanceHistoryRecords(jsonData: String = "{}") = operationalList("maintenance", "maintenance_history", jsonData)
        @JavascriptInterface
        fun generateMaintenanceHistoryReport(jsonData: String = "{}") = operationalReport("maintenance", "maintenance_history", jsonData)
        @JavascriptInterface
        fun saveMaintenanceHistoryRecord(jsonData: String) = operationalSave("maintenance", "maintenance_history", jsonData)
        @JavascriptInterface
        fun updateMaintenanceHistoryRecord(id: Long, jsonData: String) = operationalUpdate("maintenance", "maintenance_history", id, jsonData)
        @JavascriptInterface
        fun deleteMaintenanceHistoryRecord(id: Long) = operationalDelete("maintenance", "maintenance_history", id)
        @JavascriptInterface
        fun resolveMaintenanceHistoryRecord(id: Long, note: String = "") = operationalResolve("maintenance", "maintenance_history", id, note)

        @JavascriptInterface
        fun getPredictionRecords(jsonData: String = "{}") = operationalList("reports", "predictions", jsonData)
        @JavascriptInterface
        fun generatePredictionReport(jsonData: String = "{}") = operationalReport("reports", "predictions", jsonData)
        @JavascriptInterface
        fun savePredictionRecord(jsonData: String) = operationalSave("reports", "predictions", jsonData)
        @JavascriptInterface
        fun updatePredictionRecord(id: Long, jsonData: String) = operationalUpdate("reports", "predictions", id, jsonData)
        @JavascriptInterface
        fun deletePredictionRecord(id: Long) = operationalDelete("reports", "predictions", id)
        @JavascriptInterface
        fun resolvePredictionRecord(id: Long, note: String = "") = operationalResolve("reports", "predictions", id, note)

        @JavascriptInterface
        fun getDocumentRecords(jsonData: String = "{}") = operationalList("system", "documents", jsonData)
        @JavascriptInterface
        fun generateDocumentReport(jsonData: String = "{}") = operationalReport("system", "documents", jsonData)
        @JavascriptInterface
        fun saveDocumentRecord(jsonData: String) = operationalSave("system", "documents", jsonData)
        @JavascriptInterface
        fun updateDocumentRecord(id: Long, jsonData: String) = operationalUpdate("system", "documents", id, jsonData)
        @JavascriptInterface
        fun deleteDocumentRecord(id: Long) = operationalDelete("system", "documents", id)
        @JavascriptInterface
        fun resolveDocumentRecord(id: Long, note: String = "") = operationalResolve("system", "documents", id, note)

        @JavascriptInterface
        fun getSyncDeviceRecords(jsonData: String = "{}") = operationalList("sync", "sync_devices", jsonData)
        @JavascriptInterface
        fun generateSyncDeviceReport(jsonData: String = "{}") = operationalReport("sync", "sync_devices", jsonData)
        @JavascriptInterface
        fun saveSyncDeviceRecord(jsonData: String) = operationalSave("sync", "sync_devices", jsonData)
        @JavascriptInterface
        fun updateSyncDeviceRecord(id: Long, jsonData: String) = operationalUpdate("sync", "sync_devices", id, jsonData)
        @JavascriptInterface
        fun deleteSyncDeviceRecord(id: Long) = operationalDelete("sync", "sync_devices", id)
        @JavascriptInterface
        fun resolveSyncDeviceRecord(id: Long, note: String = "") = operationalResolve("sync", "sync_devices", id, note)

        @JavascriptInterface
        fun getSyncLogRecords(jsonData: String = "{}") = operationalList("sync", "sync_logs", jsonData)
        @JavascriptInterface
        fun generateSyncLogReport(jsonData: String = "{}") = operationalReport("sync", "sync_logs", jsonData)
        @JavascriptInterface
        fun saveSyncLogRecord(jsonData: String) = operationalSave("sync", "sync_logs", jsonData)
        @JavascriptInterface
        fun updateSyncLogRecord(id: Long, jsonData: String) = operationalUpdate("sync", "sync_logs", id, jsonData)
        @JavascriptInterface
        fun deleteSyncLogRecord(id: Long) = operationalDelete("sync", "sync_logs", id)
        @JavascriptInterface
        fun resolveSyncLogRecord(id: Long, note: String = "") = operationalResolve("sync", "sync_logs", id, note)

        @JavascriptInterface
        fun getBackupHistoryRecords(jsonData: String = "{}") = operationalList("sync", "backup_history", jsonData)
        @JavascriptInterface
        fun generateBackupHistoryReport(jsonData: String = "{}") = operationalReport("sync", "backup_history", jsonData)
        @JavascriptInterface
        fun saveBackupHistoryRecord(jsonData: String) = operationalSave("sync", "backup_history", jsonData)
        @JavascriptInterface
        fun updateBackupHistoryRecord(id: Long, jsonData: String) = operationalUpdate("sync", "backup_history", id, jsonData)
        @JavascriptInterface
        fun deleteBackupHistoryRecord(id: Long) = operationalDelete("sync", "backup_history", id)
        @JavascriptInterface
        fun resolveBackupHistoryRecord(id: Long, note: String = "") = operationalResolve("sync", "backup_history", id, note)

        @JavascriptInterface
        fun getPrinterProfileRecords(jsonData: String = "{}") = operationalList("printing", "printer_profiles", jsonData)
        @JavascriptInterface
        fun generatePrinterProfileReport(jsonData: String = "{}") = operationalReport("printing", "printer_profiles", jsonData)
        @JavascriptInterface
        fun savePrinterProfileRecord(jsonData: String) = operationalSave("printing", "printer_profiles", jsonData)
        @JavascriptInterface
        fun updatePrinterProfileRecord(id: Long, jsonData: String) = operationalUpdate("printing", "printer_profiles", id, jsonData)
        @JavascriptInterface
        fun deletePrinterProfileRecord(id: Long) = operationalDelete("printing", "printer_profiles", id)
        @JavascriptInterface
        fun resolvePrinterProfileRecord(id: Long, note: String = "") = operationalResolve("printing", "printer_profiles", id, note)

        @JavascriptInterface
        fun getReceiptTemplateRecords(jsonData: String = "{}") = operationalList("printing", "receipt_templates", jsonData)
        @JavascriptInterface
        fun generateReceiptTemplateReport(jsonData: String = "{}") = operationalReport("printing", "receipt_templates", jsonData)
        @JavascriptInterface
        fun saveReceiptTemplateRecord(jsonData: String) = operationalSave("printing", "receipt_templates", jsonData)
        @JavascriptInterface
        fun updateReceiptTemplateRecord(id: Long, jsonData: String) = operationalUpdate("printing", "receipt_templates", id, jsonData)
        @JavascriptInterface
        fun deleteReceiptTemplateRecord(id: Long) = operationalDelete("printing", "receipt_templates", id)
        @JavascriptInterface
        fun resolveReceiptTemplateRecord(id: Long, note: String = "") = operationalResolve("printing", "receipt_templates", id, note)

        @JavascriptInterface
        fun getInvoiceTemplateRecords(jsonData: String = "{}") = operationalList("printing", "invoice_templates", jsonData)
        @JavascriptInterface
        fun generateInvoiceTemplateReport(jsonData: String = "{}") = operationalReport("printing", "invoice_templates", jsonData)
        @JavascriptInterface
        fun saveInvoiceTemplateRecord(jsonData: String) = operationalSave("printing", "invoice_templates", jsonData)
        @JavascriptInterface
        fun updateInvoiceTemplateRecord(id: Long, jsonData: String) = operationalUpdate("printing", "invoice_templates", id, jsonData)
        @JavascriptInterface
        fun deleteInvoiceTemplateRecord(id: Long) = operationalDelete("printing", "invoice_templates", id)
        @JavascriptInterface
        fun resolveInvoiceTemplateRecord(id: Long, note: String = "") = operationalResolve("printing", "invoice_templates", id, note)

        // ============================================================
        // دالة ping للتشخيص (اختبار Bridge)
        // ============================================================

        @JavascriptInterface
        fun ping(): String {
            DebugLogger.info("Bridge", "Ping received")
            return "PONG"
        }

        @JavascriptInterface
        fun logFromJS(level: String, message: String) {
            when (level.uppercase()) {
                "INFO" -> DebugLogger.info("JS", message)
                "WARN" -> DebugLogger.warn("JS", message)
                "ERROR" -> DebugLogger.error("JS", message)
                else -> DebugLogger.info("JS", message)
            }
        }
    }

    private fun clearSessionState() {
        currentAuthToken = null
        currentUserId = 0L
        currentUserRole = ""
        currentUserName = ""
        sharedPrefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_ROLE)
            .remove(KEY_USER_NAME)
            .remove("remember_me")
            .remove("saved_username")
            .remove("saved_token")
            .remove("saved_user_id")
            .remove("saved_timestamp")
            .apply()
    }

    // ✅ دوال مساعدة داخل النشاط (safeEvaluateJs)
    fun safeEvaluateJs(script: String) {
        if (isDestroyed.get()) return
        try {
            val wv = webView
            if (wv != null && wv.isAttachedToWindow) {
                DebugLogger.info("WebView", "safeEvaluateJs called on instance=${wv.hashCode()}")
                wv.evaluateJavascript(script, null)
            } else {
                DebugLogger.warn("WebView", "safeEvaluateJs: WebView not available or not attached")
            }
        } catch (e: Exception) {
            DebugLogger.warn("WebView", "safeEvaluateJs error: ${e.message}")
        }
    }
}