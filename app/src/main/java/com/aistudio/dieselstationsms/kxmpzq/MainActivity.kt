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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.aistudio.dieselstationsms.kxmpzq.receiver.*
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService
import com.aistudio.dieselstationsms.kxmpzq.sms.*
import com.aistudio.dieselstationsms.kxmpzq.ui.theme.MyApplicationTheme
import com.aistudio.dieselstationsms.kxmpzq.utils.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.util.UUID
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

        private var webViewInstanceId = 0

        // ============================
        // DebugLogger - نظام التشخيص المركزي (معدل للتصفية)
        // ============================
        object DebugLogger {
            private const val TAG = "DebugLogger"
            private var webViewRef: WeakReference<WebView>? = null
            private var isVConsoleReady = false

            fun attachWebView(webView: WebView?) {
                webViewRef = WeakReference(webView)
                isVConsoleReady = true
            }

            fun detachWebView() {
                webViewRef?.clear()
                webViewRef = null
                isVConsoleReady = false
            }

            // دوال التسجيل العامة - ترسل إلى Logcat فقط
            fun info(tag: String, message: String) {
                val full = "[$tag] $message"
                Log.i(TAG, full)
            }

            fun warn(tag: String, message: String) {
                val full = "[$tag] $message"
                Log.w(TAG, full)
            }

            fun error(tag: String, message: String, throwable: Throwable? = null) {
                val full = if (throwable != null) {
                    "$message\n${throwable.stackTraceToString()}"
                } else {
                    message
                }
                Log.e(TAG, "[$tag] $full")
            }

            // دوال خاصة لتسجيل أحداث تسجيل الدخول فقط (ترسل إلى VConsole و Logcat)
            fun logLogin(message: String) {
                val full = "[LOGIN] $message"
                Log.i(TAG, full)
                sendToVConsole("INFO", full)
            }

            fun logDatabase(message: String) {
                val full = "[DATABASE] $message"
                Log.i(TAG, full)
                sendToVConsole("INFO", full)
            }

            fun logSession(message: String) {
                val full = "[SESSION] $message"
                Log.i(TAG, full)
                sendToVConsole("INFO", full)
            }

            fun logNavigation(message: String) {
                val full = "[NAVIGATION] $message"
                Log.i(TAG, full)
                sendToVConsole("INFO", full)
            }

            fun logError(message: String) {
                val full = "[ERROR] $message"
                Log.e(TAG, full)
                sendToVConsole("ERROR", full)
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
                Log.e(TAG, "Uncaught exception", throwable)
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
    private lateinit var geminiHelper: GeminiAIHelper
    internal lateinit var sharedPrefs: SharedPreferences

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

        installGlobalExceptionHandler()

        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            // تجاهل
        }

        try {
            initEncryptedPrefs()
        } catch (e: Exception) {
            sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        try {
            dbHelper = DatabaseHelper.getInstance(applicationContext)
        } catch (e: Exception) {
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
            // تجاهل
        }

        createNotificationChannel()

        if (isDebugMode) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
            } catch (e: Exception) {
                // تجاهل
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
            // تجاهل
        }

        super.onDestroy()
    }

    // ============================================================
    // 2. تهيئة قاعدة البيانات
    // ============================================================

    private suspend fun initializeDatabase() {
        withContext(Dispatchers.IO) {
            try {
                val tables = dbHelper.getTableCounts()
                validateDatabaseSchema()
                migrateDatabaseIfNeeded()
            } catch (e: Exception) {
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
                handleApplicationError(e)
            }
        }
    }

    private suspend fun migrateDatabaseIfNeeded() {
        withContext(Dispatchers.IO) {
            try {
                val currentVersion = dbHelper.getVersion()
                if (currentVersion < DatabaseHelper.VERSION) {
                    // يقوم DatabaseHelper بالترحيل
                }
            } catch (e: Exception) {
                handleApplicationError(e)
            }
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
            // تجاهل
        }
    }

    private fun saveSmsConfiguration(config: Map<String, String>) {
        try {
            for ((key, value) in config) {
                dbHelper.setSetting(key, value)
            }
        } catch (e: Exception) {
            // تجاهل
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
        if (!isPermissionGranted(Manifest.permission.CAMERA)) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (!isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!isPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
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

        if (denied.isNotEmpty()) {
            val criticalPermissions = listOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS
            )
            val hasCriticalDenied = denied.any { it in criticalPermissions }

            if (hasCriticalDenied) {
                Toast.makeText(
                    this,
                    "بعض الأذونات الأساسية مفقودة. قد لا تعمل بعض الميزات.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
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
            val intent = Intent(this, SMSService::class.java)
            ContextCompat.startForegroundService(this, intent)
            DebugLogger.logEvent("sms_service_started", "Service started")
        } catch (e: Exception) {
            Toast.makeText(this, "فشل في بدء خدمة SMS", Toast.LENGTH_SHORT).show()
            handleApplicationError(e)
        }
    }

    private fun stopSMSService() {
        try {
            val intent = Intent(this, SMSService::class.java)
            stopService(intent)
            DebugLogger.logEvent("sms_service_stopped", "Service stopped")
        } catch (e: Exception) {
            // تجاهل
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
            handleApplicationError(e)
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
                // تجاهل
            }
        }
    }

    private fun cleanupOldRateLimits() {
        try {
            dbHelper.cleanupOldRateLimits()
        } catch (e: Exception) {
            // تجاهل
        }
    }

    private fun cleanupOldConversationContext() {
        try {
            dbHelper.cleanupOldConversationContext(30)
        } catch (e: Exception) {
            // تجاهل
        }
    }

    private fun cleanupOldMetrics() {
        try {
            dbHelper.cleanupOldMetrics(90)
        } catch (e: Exception) {
            // تجاهل
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
                    // تجاهل
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
            // تجاهل
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
            // تجاهل
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
                wv.loadUrl("file:///android_asset/screens/login.html")
            } else {
                handler.postDelayed({
                    if (!isDestroyed.get()) {
                        loadWebViewFromAssets()
                    }
                }, 500)
            }
        } catch (e: Exception) {
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
            // تجاهل
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
        } catch (e: Exception) {
            sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
                                    // نمرر رسائل Console إلى Logcat فقط، وليس إلى VConsole
                                    // لأن VConsole يعرضها بالفعل
                                    return true
                                }
                            }

                            if (getTag(BRIDGE_INITIALIZED_TAG) != true) {
                                webAppInterface = WebAppInterface(context, this@MainActivity)
                                addJavascriptInterface(webAppInterface!!, "AndroidInterface")
                                setTag(BRIDGE_INITIALIZED_TAG, true)
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
                    // لا نرسل إلى VConsole
                    Log.d(TAG, "WebView CREATED instance=$instanceId hash=${wv.hashCode()}")
                }
            },
            update = { }
        )
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isDestroyed.get()) return
                serverReady = true
                isErrorPageShown = false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (handleCustomUrl(url)) {
                    return true
                }
                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                if (handleCustomUrl(url)) {
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
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
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
        }
    }

    private fun recreateWebView() {
        if (isDestroyed.get()) return

        val wv = webView
        if (wv != null && wv.isAttachedToWindow) {
            wv.loadUrl("file:///android_asset/screens/login.html")
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
                    false
                }
            }
            url.startsWith("tel:") -> {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            url.startsWith("http") && !url.contains("127.0.0.1") && !url.contains("localhost") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            else -> false
        }
    }

    private fun destroyWebView(webView: WebView?) {
        if (webView == null) return

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
            // تجاهل
        }
    }

    // ============================================================
    // المصادقة البيومترية
    // ============================================================

    fun showBiometricPrompt(onSuccess: () -> Unit, onError: (String) -> Unit) {
        try {
            Class.forName("androidx.biometric.BiometricPrompt")
        } catch (e: ClassNotFoundException) {
            onError("unsupported")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
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
                        onSuccess()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onError("failed")
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
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

            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError("unsupported")
        }
    }

    // ============================================================
    // WebAppInterface - واجهة JavaScript الكاملة
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

        private fun getDbHelper(): DatabaseHelper? = dbHelperRef.get()
        private fun getGeminiHelper(): GeminiAIHelper? = geminiHelperRef.get()
        private fun getActivity(): MainActivity? = activityRef.get()

        private fun checkPermission(permissionCode: String, action: String): Boolean {
            val activity = getActivity() ?: return false
            val token = activity.currentAuthToken
            if (token.isNullOrEmpty()) return false
            val userId = activity.currentUserId
            if (userId == 0L) return false
            val db = getDbHelper() ?: return false
            return db.checkUserPermission(userId, permissionCode)
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

        // ============================================================
        // 1. المصادقة (مع سجلات تسجيل الدخول)
        // ============================================================

        @JavascriptInterface
        fun login(username: String, password: String): String {
            DebugLogger.logLogin("Request received")
            DebugLogger.logLogin("Username: $username")
            DebugLogger.logLogin("Password received: ${password.isNotEmpty()}")

            val activity = getActivity()
            if (activity == null) {
                DebugLogger.logError("Activity is null")
                return errorResponse("النشاط غير متاح")
            }
            DebugLogger.logLogin("Activity exists: ${activity.hashCode()}")

            val wv = activity.webView
            if (wv == null) {
                DebugLogger.logError("WebView is null")
                return errorResponse("WebView غير متاح")
            }
            DebugLogger.logLogin("WebView exists, attached: ${wv.isAttachedToWindow}")

            DebugLogger.logLogin("AndroidInterface is working")

            val db = getDbHelper()
            if (db == null) {
                DebugLogger.logError("DatabaseHelper is null")
                return errorResponse("قاعدة البيانات غير متاحة")
            }

            DebugLogger.logDatabase("Authenticating user...")

            return try {
                val authResult = db.authenticateUser(username, password)
                if (authResult != null) {
                    DebugLogger.logLogin("Authentication success")
                    val userId = authResult.optLong("user_id", 0)
                    val permissionsArray = db.getUserPermissions(userId)
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
                    authResult.put("permissions", permissionsObject)

                    val screensArray = db.getUserScreens(userId)
                    authResult.put("screens", screensArray)

                    val role = authResult.optString("role", "USER")
                    authResult.put("role", role)
                    authResult.put("is_admin", role == "SUPER_ADMIN" || role == "ADMIN")

                    val token = UUID.randomUUID().toString()
                    activity.currentAuthToken = token
                    activity.currentUserId = userId
                    activity.currentUserRole = role
                    activity.currentUserName = authResult.optString("username", "")

                    DebugLogger.logSession("Session created for user: ${authResult.optString("username")}")
                    DebugLogger.logNavigation("Navigating to main screen...")

                    JSONObject().apply {
                        put("success", true)
                        put("user", authResult)
                        put("token", token)
                    }.toString()
                } else {
                    DebugLogger.logLogin("Authentication failed - invalid credentials")
                    errorResponse("بيانات خاطئة")
                }
            } catch (e: Exception) {
                val exceptionType = e.javaClass.simpleName
                val errorMessage = e.message ?: "Unknown error"
                DebugLogger.logError("$exceptionType: $errorMessage in authenticateUser()")
                errorResponse("خطأ داخلي: ${e.message}")
            }
        }

        @JavascriptInterface
        fun requestBiometricAuth(): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            DebugLogger.logLogin("Biometric authentication requested")
            activity.runOnUiThread {
                activity.showBiometricPrompt(
                    onSuccess = {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("message", "authenticated")
                        }
                        activity.safeEvaluateJs("window.onBiometricResult && window.onBiometricResult(${result})")
                    },
                    onError = { error ->
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", error)
                        }
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
        // دوال استعادة كلمة المرور
        // ============================================================

        @JavascriptInterface
        fun forgotPassword(username: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val user = db.getUserByUsername(username)
                if (user == null) {
                    return errorResponse("المستخدم غير موجود")
                }

                val userId = user.optLong("id", 0L)
                if (userId == 0L) {
                    return errorResponse("معرف المستخدم غير صالح")
                }

                val token = UUID.randomUUID().toString()
                val stored = db.storeResetToken(userId, token)
                if (!stored) {
                    return errorResponse("فشل تخزين التوكن")
                }

                val resetUrl = "file:///android_asset/login.html?token=$token"

                JSONObject().apply {
                    put("success", true)
                    put("token", token)
                    put("reset_url", resetUrl)
                }.toString()
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun resetPassword(token: String, newPassword: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val userData = db.validateResetToken(token)
                if (userData == null) {
                    return errorResponse("رابط الاستعادة غير صالح أو منتهي الصلاحية")
                }

                val userId = userData.optLong("id", 0L)
                if (userId == 0L) {
                    return errorResponse("معرف المستخدم غير صالح")
                }

                val updated = db.updateUserPassword(userId, newPassword)
                if (!updated) {
                    return errorResponse("فشل تحديث كلمة المرور")
                }

                db.clearResetToken(token)

                successResponse(true, "تم تحديث كلمة المرور بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun verifyResetCode(phone: String, code: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val cursor = db.readableDatabase.rawQuery(
                    "SELECT id FROM users WHERE phone = ? AND is_deleted = 0 LIMIT 1",
                    arrayOf(phone)
                )
                val userId = cursor.use {
                    if (it.moveToFirst()) it.getLong(0) else null
                }
                if (userId == null) {
                    return errorResponse("المستخدم غير موجود")
                }

                val isValid = db.validateOtpCode(userId, code)
                if (isValid) {
                    db.clearOtpCode(userId)
                    successResponse(true, "تم التحقق بنجاح")
                } else {
                    errorResponse("الرمز غير صحيح أو منتهي الصلاحية")
                }
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUserData(username: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val user = db.getUserByUsername(username)
                if (user == null) {
                    return errorResponse("المستخدم غير موجود")
                }
                dataResponse(user)
            } catch (e: Exception) {
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
            if (!checkPermission("parties", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.insertParty(data)
                successResponse(id, "تمت الإضافة بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateParty(id: Long, jsonData: String): String {
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateParty(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteParty(id: Long): String {
            if (!checkPermission("parties", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteParty(id)
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun archiveParty(id: Long): String {
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية الأرشفة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.archiveParty(id)
                successResponse(rows > 0, if (rows > 0) "تم الأرشفة بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getParties(type: String?): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val parties = db.getParties(type ?: "")
                dataResponse(parties)
            } catch (e: Exception) {
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
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val results = db.searchParties(query)
                dataResponse(results)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPartyById(id: Long): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val party = db.getPartyById(id)
                party?.toString() ?: errorResponse("العميل غير موجود")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 4. الطلبات
        // ============================================================

        @JavascriptInterface
        fun addOrder(jsonData: String): String {
            if (!checkPermission("orders", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addOrder(data)
                successResponse(id, "تم إضافة الطلب بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getOrders(status: String?): String {
            if (!checkPermission("orders", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val orders = db.getOrders(status)
                dataResponse(orders)
            } catch (e: Exception) {
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
            if (!checkPermission("deliveries", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addDelivery(data)
                successResponse(id, "تم إضافة التسليم بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getDeliveries(): String {
            if (!checkPermission("deliveries", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val deliveries = db.getDeliveries()
                dataResponse(deliveries)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTodayDeliveries(): String {
            if (!checkPermission("deliveries", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val deliveries = db.getTodayDeliveries()
                dataResponse(deliveries)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 6. المبيعات
        // ============================================================

        @JavascriptInterface
        fun addSale(jsonData: String): String {
            if (!checkPermission("sales", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addFuelSale(data)
                successResponse(id, "تم إضافة البيع بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun completeSale(jsonData: String): String {
            if (!checkPermission("sales", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val result = db.completeSale(JSONObject(jsonData))
                dataResponse(result)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSales(): String {
            if (!checkPermission("sales", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getSales()
                dataResponse(sales)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTodaySales(): String {
            if (!checkPermission("sales", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getTodaySales()
                dataResponse(sales)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteSale(saleId: Long): String {
            if (!checkPermission("sales", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val dbWritable = db.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("sales_transactions", cv, "id=?", arrayOf(saleId.toString()))
                if (rows > 0) db.logActivity("system", "delete_sale", "حذف مبيعة $saleId")
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 7. الحركات النقدية
        // ============================================================

        @JavascriptInterface
        fun addCashMovement(jsonData: String): String {
            if (!checkPermission("cash", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addCashMovement(data)
                successResponse(id, "تم إضافة الحركة المالية بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCashMovements(): String {
            if (!checkPermission("cash", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val movements = db.getCashMovements()
                dataResponse(movements)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTodayCash(): String {
            if (!checkPermission("cash", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val cash = db.getTodayCash()
                dataResponse(cash)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 8. قراءات العدادات والخزانات
        // ============================================================

        @JavascriptInterface
        fun addMeterReading(jsonData: String): String {
            if (!checkPermission("meter", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addMeterReading(data)
                successResponse(id, "تم إضافة قراءة العداد بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getMeterReadings(): String {
            if (!checkPermission("meter", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val readings = db.getMeterReadings()
                dataResponse(readings)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addTankReading(jsonData: String): String {
            if (!checkPermission("tanks", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addTankReading(data)
                successResponse(id, "تم إضافة قراءة الخزان بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTankReadings(): String {
            if (!checkPermission("tanks", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val readings = db.getTankReadings()
                dataResponse(readings)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 9. المخزون
        // ============================================================

        @JavascriptInterface
        fun addStockMovement(jsonData: String): String {
            if (!checkPermission("stock", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addStockMovement(data)
                successResponse(id, "تم إضافة حركة المخزون بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getStockMovements(): String {
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val movements = db.getStockMovements()
                dataResponse(movements)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getLowStockItems(): String {
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val items = db.getLowStockItems()
                dataResponse(items)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 10. الأصول
        // ============================================================

        @JavascriptInterface
        fun addAsset(jsonData: String): String {
            if (!checkPermission("assets", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addAsset(data)
                successResponse(id, "تم إضافة الأصل بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getAssets(): String {
            if (!checkPermission("assets", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val assets = db.getAssets()
                dataResponse(assets)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 11. المستخدمين والموظفين
        // ============================================================

        @JavascriptInterface
        fun addUser(jsonData: String): String {
            if (!checkPermission("users", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addUser(data)
                successResponse(id, "تم إضافة المستخدم بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUsers(): String {
            if (!checkPermission("users", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val users = db.getUsers()
                dataResponse(users)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUsersByRole(role: String): String {
            if (!checkPermission("users", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val users = db.getUsersByRole(role)
                dataResponse(users)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateUser(id: Long, jsonData: String): String {
            if (!checkPermission("users", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateUser(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteUser(id: Long): String {
            if (!checkPermission("users", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteUser(id)
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addEmployee(jsonData: String): String {
            if (!checkPermission("employees", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addEmployee(data)
                successResponse(id, "تم إضافة الموظف بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getEmployees(): String {
            if (!checkPermission("employees", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val employees = db.getEmployees(1)
                dataResponse(employees)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateEmployee(id: Long, jsonData: String): String {
            if (!checkPermission("employees", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateEmployee(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteEmployee(id: Long): String {
            if (!checkPermission("employees", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                if (id > Int.MAX_VALUE || id < 0) return errorResponse("معرف غير صالح")
                val intId = id.toInt()
                val rows = db.deleteEmployee(intId)
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 12. الورديات
        // ============================================================

        @JavascriptInterface
        fun startShift(jsonData: String): String {
            if (!checkPermission("shifts", "create")) return errorResponse("لا تملك صلاحية بدء الوردية")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.startShift(data)
                successResponse(id, "تم بدء الوردية بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun endShift(id: Long, jsonData: String): String {
            if (!checkPermission("shifts", "update")) return errorResponse("لا تملك صلاحية إنهاء الوردية")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.endShift(id, data)
                successResponse(rows > 0, if (rows > 0) "تم إنهاء الوردية بنجاح" else "لم يتم العثور على الوردية")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCurrentShift(): String {
            if (!checkPermission("shifts", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val shift = db.getCurrentShift()
                shift?.toString() ?: errorResponse("لا توجد وردية نشطة")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getShifts(): String {
            if (!checkPermission("shifts", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val shifts = db.getShifts(1)
                dataResponse(shifts)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteShift(shiftId: Long): String {
            if (!checkPermission("shifts", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val dbWritable = db.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("shifts", cv, "id=?", arrayOf(shiftId.toString()))
                if (rows > 0) db.logActivity("system", "delete_shift", "حذف وردية $shiftId")
                successResponse(rows > 0, if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addShiftSale(jsonData: String): String {
            if (!checkPermission("shifts", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addShiftSale(data)
                successResponse(id, "تم إضافة بيع الوردية بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addShiftDelivery(jsonData: String): String {
            if (!checkPermission("shifts", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addShiftDelivery(data)
                successResponse(id, "تم إضافة تسليم الوردية بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addShiftExpense(jsonData: String): String {
            if (!checkPermission("shifts", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addShiftExpense(data)
                successResponse(id, "تم إضافة مصروف الوردية بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getShiftReport(shiftId: Long): String {
            if (!checkPermission("shifts", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val report = db.getShiftReport(shiftId)
                dataResponse(report)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 13. الإشعارات
        // ============================================================

        @JavascriptInterface
        fun addNotification(jsonData: String): String {
            if (!checkPermission("notifications", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addNotification(data)
                successResponse(id, "تم إضافة الإشعار بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getNotifications(): String {
            if (!checkPermission("notifications", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val notifications = db.getNotifications()
                dataResponse(notifications)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUnreadNotificationsCount(): String {
            if (!checkPermission("notifications", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val count = db.getUnreadNotificationsCount()
                JSONObject().apply {
                    put("success", true)
                    put("count", count)
                }.toString()
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun markNotificationRead(id: Long): String {
            if (!checkPermission("notifications", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.markNotificationRead(id)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على الإشعار")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 14. الرسائل النصية (SMS)
        // ============================================================

        @JavascriptInterface
        fun addSmsMessage(jsonData: String): String {
            if (!checkPermission("sms", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addSmsMessage(data)
                successResponse(id, "تم إضافة الرسالة بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsMessages(): String {
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val messages = db.getSmsMessages()
                dataResponse(messages)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsMessagesByPhone(phone: String): String {
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val messages = db.getSmsMessagesByPhone(phone)
                dataResponse(messages)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsMessagesByStatus(status: String): String {
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val messages = db.getSmsMessagesByStatus(status)
                dataResponse(messages)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateSmsStatus(id: Long, status: String): String {
            if (!checkPermission("sms", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.updateSmsStatus(id, status)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على الرسالة")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsStats(): String {
            if (!checkPermission("sms", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stats = db.getSmsStats()
                dataResponse(stats)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSmsTemplates(): String {
            if (!checkPermission("sms_templates", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val templates = db.getSmsTemplates()
                dataResponse(templates)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addSmsTemplate(jsonData: String): String {
            if (!checkPermission("sms_templates", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addSmsTemplate(data)
                successResponse(id, "تم إضافة القالب بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateSmsTemplate(id: Long, jsonData: String): String {
            if (!checkPermission("sms_templates", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateSmsTemplate(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على القالب")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteSmsTemplate(id: Long): String {
            if (!checkPermission("sms_templates", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteSmsTemplate(id)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على القالب")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 15. القائمة البيضاء والإعدادات
        // ============================================================

        @JavascriptInterface
        fun getWhitelist(): String {
            if (!checkPermission("whitelist", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val whitelist = db.getSmsWhitelist()
                dataResponse(whitelist)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addWhitelist(jsonData: String): String {
            if (!checkPermission("whitelist", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val phone = data.optString("phone", "")
                val name = data.optString("name", "")
                if (phone.isBlank()) return errorResponse("رقم الهاتف مطلوب")
                db.addToSmsWhitelist(phone, name)
                successResponse(0, "تمت الإضافة بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun removeWhitelist(jsonData: String): String {
            if (!checkPermission("whitelist", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val phone = data.optString("phone", "")
                if (phone.isBlank()) return errorResponse("رقم الهاتف مطلوب")
                db.removeFromSmsWhitelist(phone)
                successResponse(0, "تم الحذف بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addSetting(jsonData: String): String {
            if (!checkPermission("settings", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addSetting(data)
                successResponse(id, "تم إضافة الإعداد بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteSetting(key: String): String {
            if (!checkPermission("settings", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteSetting(key)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على الإعداد")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getSetting(key: String): String {
            if (!checkPermission("settings", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val value = db.getSetting(key)
                JSONObject().apply {
                    put("success", true)
                    put("value", value)
                }.toString()
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun setSetting(key: String, value: String): String {
            if (!checkPermission("settings", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                db.setSetting(key, value)
                successResponse(0, "تم التحديث")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getAllSettingsMap(): String {
            if (!checkPermission("settings", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val settings = db.getAllSettingsMap()
                dataResponse(settings)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 17. لوحة التحكم والتقارير
        // ============================================================

        @JavascriptInterface
        fun getDashboardStats(): String {
            if (!checkPermission("dashboard", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stats = db.getDashboardStats(1)
                dataResponse(stats)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getOverduePayments(): String {
            if (!checkPermission("payments", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val payments = db.getOverduePayments()
                dataResponse(payments)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getActiveAlerts(): String {
            if (!checkPermission("alerts", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val alerts = db.getActiveAlerts()
                dataResponse(alerts)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getRecentActivity(limit: Int): String {
            if (!checkPermission("activity", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val activity = db.getRecentActivity(limit)
                dataResponse(activity)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 18. المنتجات والوقود
        // ============================================================

        @JavascriptInterface
        fun getProducts(): String {
            if (!checkPermission("products", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val products = db.getProducts()
                dataResponse(products)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addProduct(jsonData: String): String {
            if (!checkPermission("products", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.insertProduct(data)
                successResponse(id, "تم إضافة المنتج بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateProduct(id: Long, jsonData: String): String {
            if (!checkPermission("products", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val rows = db.updateProduct(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على المنتج")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteProduct(id: Long): String {
            if (!checkPermission("products", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deleteProduct(id)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على المنتج")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getFuelTypes(): String {
            if (!checkPermission("fuel", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val types = db.getFuelTypes()
                dataResponse(types)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCategories(): String {
            if (!checkPermission("categories", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val categories = db.getProductCategories()
                dataResponse(categories)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 19. المركبات، الخزانات والمضخات
        // ============================================================

        @JavascriptInterface
        fun getVehicles(): String {
            if (!checkPermission("vehicles", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val vehicles = db.getVehicles()
                dataResponse(vehicles)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTanks(): String {
            if (!checkPermission("tanks", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val tanks = db.getTanks()
                dataResponse(tanks)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPumps(): String {
            if (!checkPermission("pumps", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val pumps = db.getPumps()
                dataResponse(pumps)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getTankStats(): String {
            if (!checkPermission("tanks", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stats = db.getTankStats()
                dataResponse(stats)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateTankQuantity(tankId: Int, quantity: Double): String {
            if (!checkPermission("tanks", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                db.updateTankQuantity(tankId, quantity, "System")
                successResponse(0, "تم التحديث")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 20. طلبات الصيانة
        // ============================================================

        @JavascriptInterface
        fun getMaintenanceRequests(jsonData: String?): String {
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
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addMaintenanceRequest(jsonData: String): String {
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
                successResponse(id, "تم إضافة طلب الصيانة بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updateMaintenanceStatus(jsonData: String): String {
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
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deleteMaintenance(requestId: Long): String {
            if (!checkPermission("maintenance", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val dbWritable = db.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("maintenance_requests", cv, "id=?", arrayOf(requestId.toString()))
                if (rows > 0) db.logActivity("system", "delete_maintenance", "حذف طلب صيانة $requestId")
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على الطلب")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 21. المدفوعات والإيداعات
        // ============================================================

        @JavascriptInterface
        fun getPayments(): String {
            if (!checkPermission("payments", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val payments = db.getPaymentsWithCustomer()
                dataResponse(payments)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun makePayment(jsonData: String): String {
            if (!checkPermission("payments", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val customerId = data.optInt("customer_party_id", 0)
                val amount = data.optDouble("amount", 0.0)
                val method = data.optString("payment_method", "cash")
                val operator = data.optString("operator", "System")
                if (customerId <= 0 || amount <= 0) return errorResponse("بيانات غير صالحة")
                val success = db.processPayment(customerId, amount, method, operator)
                successResponse(success, if (success) "تم التسديد بنجاح" else "فشل التسديد")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addDeposit(jsonData: String): String {
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
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deletePayment(paymentId: Long): String {
            if (!checkPermission("payments", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val dbWritable = db.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("payments", cv, "id=?", arrayOf(paymentId.toString()))
                if (rows > 0) db.logActivity("system", "delete_payment", "حذف دفعة $paymentId")
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على الدفعة")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 22. تقارير إضافية
        // ============================================================

        @JavascriptInterface
        fun getMonthlySales(): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getMonthlySales(1)
                dataResponse(sales)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getDailySales(date: String?): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getDailySales(1, date)
                dataResponse(sales)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getEodReport(): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val report = db.getEodReport(1)
                dataResponse(report)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getProfitReport(fromDate: String?, toDate: String?): String {
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
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getInventoryReport(): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val products = db.getProducts(1)
                val result = JSONArray()
                for (i in 0 until products.length()) {
                    val p = products.getJSONObject(i)
                    val item = JSONObject().apply {
                        put("product_name", p.optString("product_name", ""))
                        put("quantity", p.optDouble("quantity", 0.0))
                        put("unit", p.optString("unit_id", "لتر"))
                    }
                    result.put(item)
                }
                dataResponse(result)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getOverdueReport(): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val overdue = db.getOverduePayments()
                dataResponse(overdue)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getFuelSales(): String {
            if (!checkPermission("reports", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getSalesByFuelType()
                dataResponse(sales)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 23. النسخ الاحتياطي والتصدير
        // ============================================================

        @JavascriptInterface
        fun backupDatabase(): String {
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
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getAssetMaintenanceHistory(jsonData: String): String {
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
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUserNotifications(userId: Long): String {
            if (!checkPermission("notifications", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val notifications = db.getUserNotifications(userId)
                dataResponse(notifications)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getUserPermissions(userId: Long): String {
            if (!checkPermission("users", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val permissions = db.getUserPermissions(userId)
                dataResponse(permissions)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun checkLowStock(): String {
            if (!checkPermission("stock", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val items = db.checkLowStock()
                dataResponse(items)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun createStockAlert(productId: Long, threshold: Double): String {
            if (!checkPermission("stock", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val id = db.createStockAlert(productId, threshold)
                successResponse(id, "تم إنشاء التنبيه بنجاح")
            } catch (e: Exception) {
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
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun recordDieselDelivery(jsonData: String): String {
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
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 25. دوال إضافية للشاشات الجديدة
        // ============================================================

        @JavascriptInterface
        fun getPartyTypes(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val types = db.getPartyTypes()
                dataResponse(types)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCurrencies(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val currencies = db.getCurrencies()
                dataResponse(currencies)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomerLedger(partyId: Long): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val ledger = db.getCustomerLedger(partyId)
                dataResponse(ledger)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomerSales(partyId: Long): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val sales = db.getCustomerSales(partyId)
                dataResponse(sales)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPartyContacts(partyId: Long): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val contacts = db.getPartyContacts(partyId)
                dataResponse(contacts)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getPartyAddresses(partyId: Long): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val addresses = db.getPartyAddresses(partyId)
                dataResponse(addresses)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addPartyContact(jsonData: String): String {
            if (!checkPermission("parties", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addPartyContact(data)
                successResponse(id, "تم إضافة جهة الاتصال بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updatePartyContact(jsonData: String): String {
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = data.optLong("id", 0)
                val rows = db.updatePartyContact(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deletePartyContact(contactId: Long): String {
            if (!checkPermission("parties", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deletePartyContact(contactId)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun addPartyAddress(jsonData: String): String {
            if (!checkPermission("parties", "create")) return errorResponse("لا تملك صلاحية الإضافة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = db.addPartyAddress(data)
                successResponse(id, "تم إضافة العنوان بنجاح")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun updatePartyAddress(jsonData: String): String {
            if (!checkPermission("parties", "update")) return errorResponse("لا تملك صلاحية التحديث")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val data = JSONObject(jsonData)
                val id = data.optLong("id", 0)
                val rows = db.updatePartyAddress(id, data)
                successResponse(rows > 0, if (rows > 0) "تم التحديث" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun deletePartyAddress(addressId: Long): String {
            if (!checkPermission("parties", "delete")) return errorResponse("لا تملك صلاحية الحذف")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val rows = db.deletePartyAddress(addressId)
                successResponse(rows > 0, if (rows > 0) "تم الحذف" else "لم يتم العثور على السجل")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun getCustomerDebts(fromDate: String?, toDate: String?): String {
            if (!checkPermission("parties", "read")) return errorResponse("لا تملك صلاحية القراءة")
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val debts = db.getCustomerDebts()
                dataResponse(debts)
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // 26. إدارة بيانات الاعتماد (Remember Me + Biometric Auto-Login)
        // ============================================================

        @JavascriptInterface
        fun saveCredentials(username: String, password: String, remember: Boolean): String {
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
                successResponse(0, if (remember) "تم حفظ بيانات التسجيل" else "تم إلغاء التذكر")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun loadCredentials(): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            return try {
                val prefs = activity.sharedPrefs
                val remember = prefs.getBoolean("remember_me", false)
                val username = prefs.getString("saved_username", "") ?: ""
                val userId = prefs.getLong("saved_user_id", 0)
                val timestamp = prefs.getLong("saved_timestamp", 0)

                JSONObject().apply {
                    put("success", true)
                    put("hasCredentials", remember && userId != 0L && username.isNotEmpty())
                    put("username", username)
                    put("userId", userId)
                    put("timestamp", timestamp)
                }.toString()
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun hasSavedCredentials(): String {
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
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun biometricAutoLogin(): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val prefs = activity.sharedPrefs

            val token = prefs.getString("saved_token", "") ?: ""
            val userId = prefs.getLong("saved_user_id", 0)
            val savedUsername = prefs.getString("saved_username", "") ?: ""

            if (token.isEmpty() || userId == 0L || savedUsername.isEmpty()) {
                return errorResponse("لا توجد بيانات محفوظة")
            }

            activity.runOnUiThread {
                activity.showBiometricPrompt(
                    onSuccess = {
                        val db = getDbHelper()
                        if (db == null) {
                            activity.safeEvaluateJs("""window.onBiometricAutoLogin && window.onBiometricAutoLogin(${errorResponse("قاعدة البيانات غير متاحة")})""")
                            return@showBiometricPrompt
                        }
                        try {
                            activity.currentAuthToken = token
                            activity.currentUserId = userId

                            val user = db.getUserByUsername(savedUsername)
                            if (user != null) {
                                val permissionsArray = db.getUserPermissions(userId)
                                val permissionsObject = JSONObject()
                                for (i in 0 until permissionsArray.length()) {
                                    val item = permissionsArray.getJSONObject(i)
                                    val code = item.getString("permission_code")
                                    permissionsObject.put(code, JSONObject().apply {
                                        put("can_create", item.optBoolean("can_create"))
                                        put("can_read", item.optBoolean("can_read"))
                                        put("can_update", item.optBoolean("can_update"))
                                        put("can_delete", item.optBoolean("can_delete"))
                                        put("can_export", item.optBoolean("can_export"))
                                        put("can_print", item.optBoolean("can_print"))
                                        put("can_approve", item.optBoolean("can_approve"))
                                    })
                                }
                                user.put("permissions", permissionsObject)
                                user.put("screens", db.getUserScreens(userId))
                                val role = user.optString("role", "USER")
                                user.put("role", role)
                                user.put("is_admin", role == "SUPER_ADMIN" || role == "ADMIN")

                                activity.currentUserRole = role
                                activity.currentUserName = user.optString("username", "")

                                val result = JSONObject().apply {
                                    put("success", true)
                                    put("user", user)
                                    put("token", token)
                                    put("message", "تم تسجيل الدخول عبر البصمة")
                                }
                                activity.safeEvaluateJs("""window.onBiometricAutoLogin && window.onBiometricAutoLogin($result)""")
                            } else {
                                activity.safeEvaluateJs("""window.onBiometricAutoLogin && window.onBiometricAutoLogin(${errorResponse("المستخدم غير موجود")})""")
                            }
                        } catch (e: Exception) {
                            activity.safeEvaluateJs("""window.onBiometricAutoLogin && window.onBiometricAutoLogin(${errorResponse(e.message)})""")
                        }
                    },
                    onError = { error ->
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", error)
                        }
                        activity.safeEvaluateJs("""window.onBiometricAutoLogin && window.onBiometricAutoLogin($result)""")
                    }
                )
            }

            return JSONObject().apply {
                put("success", true)
                put("status", "processing")
            }.toString()
        }

        @JavascriptInterface
        fun clearCredentials(): String {
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
                activity.currentUserId = 0
                activity.currentUserRole = ""
                activity.currentUserName = ""
                successResponse(0, "تم مسح البيانات وجلسة التطبيق")
            } catch (e: Exception) {
                errorResponse(e.message)
            }
        }

        // ============================================================
        // دالة ping للتشخيص (اختبار Bridge)
        // ============================================================

        @JavascriptInterface
        fun ping(): String {
            return "PONG"
        }

        // دالة لإرسال السجلات من JavaScript إلى DebugLogger
        @JavascriptInterface
        fun logFromJS(level: String, message: String) {
            // نرسل فقط الرسائل التي تبدأ ببادئات تسجيل الدخول
            if (message.startsWith("[LOGIN]") || message.startsWith("[ERROR]") || message.startsWith("[DATABASE]") || message.startsWith("[SESSION]") || message.startsWith("[NAVIGATION]")) {
                when (level.uppercase()) {
                    "INFO" -> DebugLogger.logLogin(message.replace("[LOGIN] ", ""))
                    "WARN" -> DebugLogger.logLogin("[WARN] " + message)
                    "ERROR" -> DebugLogger.logError(message.replace("[ERROR] ", ""))
                    else -> DebugLogger.logLogin(message)
                }
            }
        }

    // نهاية WebAppInterface
    }

    // ============================================================
    // دوال مساعدة داخل النشاط
    // ============================================================

    fun safeEvaluateJs(script: String) {
        if (isDestroyed.get()) return
        try {
            val wv = webView
            if (wv != null && wv.isAttachedToWindow) {
                wv.evaluateJavascript(script, null)
            }
        } catch (e: Exception) {
            // تجاهل
        }
    }
}