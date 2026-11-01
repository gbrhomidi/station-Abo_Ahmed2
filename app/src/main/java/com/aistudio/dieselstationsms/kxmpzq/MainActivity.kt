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
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.provider.OpenableColumns
import android.provider.DocumentsContract
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.aistudio.dieselstationsms.kxmpzq.receiver.*
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService
import com.aistudio.dieselstationsms.kxmpzq.notifications.TaskNotificationManager
import com.aistudio.dieselstationsms.kxmpzq.startup.DatabaseInitializationCoordinator
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupPerformanceMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceStatusRepository
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceLaunchResult
import com.aistudio.dieselstationsms.kxmpzq.startup.SmsServiceLauncher
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.sms.*
import com.aistudio.dieselstationsms.kxmpzq.settings.backup.BackupEntry
import com.aistudio.dieselstationsms.kxmpzq.settings.backup.FullDatabaseBackupManager
import com.aistudio.dieselstationsms.kxmpzq.settings.backup.FullDatabaseBackupScheduler
import com.aistudio.dieselstationsms.kxmpzq.settings.di.SettingsModule
import com.aistudio.dieselstationsms.kxmpzq.settings.security.SecuritySettingsStore
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.ui.theme.MyApplicationTheme
import com.aistudio.dieselstationsms.kxmpzq.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream

/**
 * ═══════════════════════════════════════════════════════════════
 * MainActivity - النشاط الرئيسي لتطبيق محطة أبو أحمد
 * ═══════════════════════════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

    private var pendingInvoiceFolderHtml: String? = null
    private var pendingInvoiceFolderPhone: String = ""
    private var pendingInvoiceFolderAction: String = ""
    private var isDatabaseBackupPickerActive: Boolean = false
    // Bundle restoration marks an Activity recreation (e.g. rotation), not a fresh launch.
    private var isActivityRecreation: Boolean = false
    private var wasAuthenticatedShellAtRecreation: Boolean = false

    private fun currentDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

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
        private const val KEY_SYSTEM_SETUP_COMPLETED = "system_setup_completed"
        private const val KEY_REQUIRE_LOGIN_ON_NEXT_LAUNCH = "require_login_on_next_launch"
        private const val KEY_LAST_BOOT_COUNT = "last_boot_count"
        private const val KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME = "security_background_at_elapsed_realtime"
        private const val KEY_SECURITY_REAUTH_PENDING = "security_reauthentication_pending"
        private const val STATE_WAS_AUTHENTICATED_SHELL = "state_was_authenticated_shell"


        private var webViewInstanceId = 0
        private var pendingWebPermissionRequest: android.webkit.PermissionRequest? = null
        private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null
        private val fileChooserRequestCode = 4207
        private val mapPickerRequestCode = 4208
        private val vehicleFilePickerRequestCode = 4209
        private val stationFilePickerRequestCode = 4210
        private val invoiceFolderPickerRequestCode = 4211
        private val databaseBackupFolderPickerRequestCode = 4212
        private val databaseBackupRestorePickerRequestCode = 4213
        private val SECURITY_DEVICE_UNLOCK_REQUEST = 4214
        private var pendingVehicleFileKind: String? = null
        private var pendingStationFileKind: String? = null

        // ============================
        // DebugLogger - نظام التشخيص المركزي
        // ============================
        object DebugLogger {
            private const val TAG = "DebugLogger"
            private var webViewRef: WeakReference<WebView>? = null
            @Volatile private var developerModeEnabled = BuildConfig.DEBUG
            @Volatile private var isVConsoleReady = BuildConfig.DEBUG

            fun attachWebView(webView: WebView?) {
                webViewRef = WeakReference(webView)
                isVConsoleReady = developerModeEnabled
                info("DebugLogger", "Attached to WebView")
            }

            fun setDeveloperModeEnabled(enabled: Boolean) {
                developerModeEnabled = enabled
                isVConsoleReady = developerModeEnabled
                info("DebugLogger", "Developer diagnostics ${if (developerModeEnabled) "enabled" else "disabled"}")
            }

            fun isDeveloperModeEnabled(): Boolean = developerModeEnabled

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
    // Updating this Compose state recreates AndroidView when Chromium's renderer dies.
    private val webViewRecoveryTrigger = androidx.compose.runtime.mutableIntStateOf(0)
    private var lastKnownAssetUrl: String? = null
    private val liveUpdateListener: (String) -> Unit = { payload ->
        runOnUiThread { webView?.evaluateJavascript("window.dispatchEvent(new CustomEvent('sms-live-update',{detail:$payload}))", null) }
    }
    private var geminiApiKey: String = ""
    private var serverReady = false
    @Volatile private var startupDatabaseReady = false
    private var startupHealthCheckCompleted = false
    @Volatile private var startupSetupRequired = false
    @Volatile private var startupUiState = "initializing"
    @Volatile private var startupErrorMessage = ""
    private var startupExpectedAsset = "login.html"
    private var activityCreatePerformanceSpan: StartupPerformanceMonitor.Span? = null
    private var currentPageLoadPerformanceSpan: StartupPerformanceMonitor.Span? = null
    private var currentPageLoadAsset: String? = null
    private var firstAssetPageFinished = false
    private var startupPerformanceReportedFullyDrawn = false
    private var startupPermissionsRequested = false
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
    private lateinit var securitySettingsStore: SecuritySettingsStore
    private var securityLockPending: Boolean = false
    private var securityPromptActive: Boolean = false
    private var appInForeground: Boolean = false
    private var biometricPrompt: androidx.biometric.BiometricPrompt? = null
    private var biometricCancellationExpected: Boolean = false
    private var securityFallbackDialog: androidx.appcompat.app.AlertDialog? = null
    private var lastSecurityInteractionElapsed: Long = android.os.SystemClock.elapsedRealtime()

    private val securityIdleRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed.get()) return
            if (startupDatabaseReady && hasWindowFocus() && !securityPromptActive &&
                !currentAuthToken.isNullOrBlank()
            ) {
                val timeoutMinutes = securitySettingValue("autoLogoutMinutes").toIntOrNull()?.coerceIn(0, 1440) ?: 0
                if (timeoutMinutes > 0 &&
                    android.os.SystemClock.elapsedRealtime() - lastSecurityInteractionElapsed >= timeoutMinutes * 60_000L
                ) {
                    forceSecurityLogout("security_auto_logout")
                }
            }
            if (!isDestroyed.get()) handler.postDelayed(this, 5_000L)
        }
    }
    private val settingsModule: SettingsModule by lazy {
        SettingsModule(applicationContext)
    }
    private lateinit var geminiHelper: GeminiAIHelper
    private lateinit var smsServiceLauncher: SmsServiceLauncher
    internal lateinit var sharedPrefs: SharedPreferences
    private var pendingNotificationTaskId: Long? = null
    private var pendingVehicleTrackingId: Long? = null

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
        StartupPerformanceMonitor.attachWindow(window)
        activityCreatePerformanceSpan = StartupPerformanceMonitor.beginSpan("activity.onCreate")
        StartupPerformanceMonitor.mark(
            "activity.create",
            mapOf("recreated" to (savedInstanceState != null).toString())
        )
        isActivityRecreation = savedInstanceState != null
        wasAuthenticatedShellAtRecreation =
            savedInstanceState?.getBoolean(STATE_WAS_AUTHENTICATED_SHELL, false) == true
        captureNotificationIntent(intent)

        installGlobalExceptionHandler()

        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            DebugLogger.warn("onCreate", "enableEdgeToEdge failed: ${e.message}")
        }

        // يفشل التطبيق صراحةً إذا تعذر إنشاء التفضيلات المشفرة؛ لا يوجد مسار تخزين بديل غير آمن.
        initEncryptedPrefs()

        try {
            // إنشاء مكونات الحالة لا يفتح SQLite؛ فتح قاعدة البيانات يتم لاحقاً
            // عبر المنسق المشترك على Dispatchers.IO.
            smsServiceLauncher = SmsServiceLauncher(
                applicationContext,
                ServiceStatusRepository(applicationContext)
            )
            LiveUpdateHub.subscribe(liveUpdateListener)
            DebugLogger.info("Database", "SQLite initialization delegated to the process coordinator")
        } catch (e: Exception) {
            DebugLogger.logException("Startup", e)
            startupErrorMessage = e.message ?: "تعذر تجهيز مكونات بدء التشغيل"
            startupUiState = "failed"
            Toast.makeText(this, "تعذر بدء التطبيق", Toast.LENGTH_LONG).show()
            StartupPerformanceMonitor.endSpan(activityCreatePerformanceSpan, success = false)
            StartupPerformanceMonitor.flushReport("activity_initialization_failed")
            finish()
            return
        }

        try {
            geminiHelper = GeminiAIHelper(this)
            val aiConfig = SmsAiConfigStore(this).get()
            geminiApiKey = aiConfig.apiKey.takeIf {
                aiConfig.provider.equals("gemini", ignoreCase = true)
            }.orEmpty()
            if (geminiApiKey.isNotEmpty()) {
                geminiHelper.initialize(geminiApiKey)
            }
        } catch (e: Exception) {
            geminiApiKey = ""
            DebugLogger.warn("Gemini", "Secure AI configuration unavailable: ${e.javaClass.simpleName}")
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

        // Keep the interactive WebView on Android's viewport-optimized drawing path.
        // Invoice capture measures and lays out its dedicated renderer explicitly before drawing.

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        lifecycleScope.launch {
            try {
                // This await does not block the Activity. Concurrent startup callers share one IO open.
                val databaseAwaitSpan = StartupPerformanceMonitor.beginSpan("startup.database.await")
                try {
                    dbHelper = DatabaseInitializationCoordinator.awaitDatabase(applicationContext)
                } finally {
                    StartupPerformanceMonitor.endSpan(databaseAwaitSpan)
                }
                StartupPerformanceMonitor.mark("startup.database.ready")
                DebugLogger.info("Database", "Shared SQLite initialization completed")
                if (initializeStartupDatabase()) {
                    val systemInitializationSpan =
                        StartupPerformanceMonitor.beginSpan("startup.system.initialize")
                    try {
                        initializeSystem()
                    } finally {
                        StartupPerformanceMonitor.endSpan(systemInitializationSpan, startupHealthCheckCompleted)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                DebugLogger.logException("DatabaseInitialization", e)
                startupDatabaseReady = false
                startupErrorMessage = e.message?.takeIf { it.isNotBlank() }
                    ?: "تعذر تجهيز قاعدة البيانات المحلية"
                startupUiState = "failed"
                StartupPerformanceMonitor.mark(
                    "startup.failed",
                    mapOf("stage" to "database_await", "exception" to e.javaClass.simpleName)
                )
                StartupPerformanceMonitor.flushReport("startup_failed")
                withContext(Dispatchers.Main) {
                    if (!isDestroyed.get() && !isFinishing) {
                        webView?.evaluateJavascript(
                            "window.onAppStartupFailed && window.onAppStartupFailed()",
                            null
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "تعذر تجهيز قاعدة البيانات؛ أعد المحاولة بعد معالجة السبب",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                TaskNotificationManager.reschedulePendingTasksAsync(applicationContext)
            }
        }

        scheduleBackgroundTasks()

        DebugLogger.info("MainActivity", "onCreate finished")
        StartupPerformanceMonitor.endSpan(activityCreatePerformanceSpan)
        StartupPerformanceMonitor.mark("activity.onCreate.completed")
    }

    private data class StartupPreparation(
        val setupRequired: Boolean,
        val securityLockPending: Boolean,
        val developerModeEnabled: Boolean
    )

    /**
     * Opening/upgrading SQLite can create many tables and indexes. Keep that work off
     * the UI thread so the bundled login screen can render immediately on cold start.
     */
    private suspend fun initializeStartupDatabase(): Boolean {
        val performanceSpan = StartupPerformanceMonitor.beginSpan("startup.database.prepare")
        var preparationSucceeded = false
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            return try {
            val preparation = withContext(Dispatchers.IO) {
                StartupPerformanceMonitor.measure("startup.schema.debt_due") {
                    DebtDueDateRepository.ensureSchema(dbHelper)
                }
                StartupPerformanceMonitor.measure("startup.security.migrate_legacy") {
                    migrateLegacySecuritySettings(dbHelper)
                }
                StartupPerformanceMonitor.measure("startup.session.boot_validation") {
                    clearSessionAfterDeviceRebootIfNeeded(dbHelper)
                }

                val setupRequired = querySystemSetupRequired()
                val loginRequiredOnLaunch = sharedPrefs.getBoolean(KEY_REQUIRE_LOGIN_ON_NEXT_LAUNCH, false)
                val hasActiveSession = !currentAuthToken.isNullOrBlank()
                val previouslyPending = sharedPrefs.getBoolean(KEY_SECURITY_REAUTH_PENDING, false)
                val backgroundedAt = sharedPrefs.getLong(KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME, -1L)
                    .takeIf { it >= 0L }
                val backgroundTimeoutReached = BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                    backgroundedAtElapsedRealtime = backgroundedAt,
                    resumedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                )
                val lockPending = BackgroundReauthenticationPolicy.shouldLockOnStartup(
                    hasActiveSession = hasActiveSession,
                    loginRequiredOnLaunch = loginRequiredOnLaunch,
                    previouslyPending = previouslyPending,
                    isActivityRecreation = isActivityRecreation,
                    wasAuthenticatedShell = wasAuthenticatedShellAtRecreation,
                    backgroundTimeoutReached = backgroundTimeoutReached,
                    securityReauthenticationEnabled = requiresSecurityReauthentication()
                )
                // Consume an elapsed-realtime background marker only after startup security
                // settings and the current session have both been read successfully.
                sharedPrefs.edit()
                    .remove(KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME)
                    .putBoolean(KEY_SECURITY_REAUTH_PENDING, lockPending)
                    .apply()
                val developerModeEnabled = dbHelper.getSetting("developerMode").trim().lowercase(Locale.ROOT) in
                    setOf("1", "true", "yes", "on")
                StartupPreparation(setupRequired, lockPending, developerModeEnabled)
            }

            withContext(Dispatchers.Main) {
                startupSetupRequired = preparation.setupRequired
                securityLockPending = preparation.securityLockPending
                startupDatabaseReady = true
                startupErrorMessage = ""
                DebugLogger.setDeveloperModeEnabled(preparation.developerModeEnabled)
                startupExpectedAsset = initialEntryAsset()
                startupUiState = if (startupExpectedAsset == "login.html") "ready" else "redirecting"

                if (startupExpectedAsset == "main.html" && securityLockPending) {
                    webView?.visibility = View.INVISIBLE
                }
                if (startupExpectedAsset != "login.html") {
                    loadWebViewFromAssets()
                } else if (webView?.url?.substringBefore('?')?.substringBefore('#')
                        ?.endsWith("/login.html") == true
                ) {
                    applySecurityRuntimeSettings()
                }
                if (startupExpectedAsset == "login.html") {
                    startupUiState = "ready"
                }
                scheduleStartupPermissionsRequest()
                handler.removeCallbacks(securityIdleRunnable)
                handler.postDelayed(securityIdleRunnable, 5_000L)
            }

            DebugLogger.info(
                "Startup",
                "SQLite startup preparation completed in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms; " +
                    "entry=$startupExpectedAsset"
            )
            StartupPerformanceMonitor.mark(
                "startup.database_preparation.completed",
                mapOf("entry" to startupExpectedAsset)
            )
            preparationSucceeded = true
            true
        } catch (e: Exception) {
            StartupPerformanceMonitor.mark(
                "startup.database_preparation.failed",
                mapOf("exception" to e.javaClass.simpleName)
            )
            StartupPerformanceMonitor.flushReport("startup_database_preparation_failed")
            DebugLogger.logException("StartupDatabase", e)
            withContext(Dispatchers.Main) {
                startupDatabaseReady = false
                startupErrorMessage = e.message?.takeIf { it.isNotBlank() } ?: "تعذر تجهيز قاعدة البيانات المحلية"
                startupUiState = "failed"
                webView?.evaluateJavascript("window.onAppStartupFailed && window.onAppStartupFailed()", null)
            }
            false
            }
        } finally {
            StartupPerformanceMonitor.endSpan(performanceSpan, preparationSucceeded)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationIntent(intent)
    }

    private fun captureNotificationIntent(intent: Intent?) {
        if (intent?.getStringExtra("open_screen") == "vehicle-tracking.html") {
            val vehicleId = intent.getLongExtra("vehicle_id", 0L)
            val target = "file:///android_asset/screens/vehicle-tracking.html" +
                    if (vehicleId > 0L) "?vehicle_id=$vehicleId" else ""
            val currentUrl = webView?.url.orEmpty()
            if (webView != null && (currentUrl.contains("main.html") || currentUrl.contains("vehicle-tracking.html"))) {
                webView?.loadUrl(target)
            } else {
                pendingVehicleTrackingId = vehicleId
            }
            return
        }
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
        StartupPerformanceMonitor.mark("activity.onStart")
        appInForeground = true
        updateUIState()
        if (!isDestroyed.get() && startupDatabaseReady && startupHealthCheckCompleted) {
            // Re-run diagnostic SQLite checks only after startup initialization has finished.
            lifecycleScope.launch(Dispatchers.IO) { checkSmsSystemHealth() }
        }
    }

    override fun onResume() {
        super.onResume()
        StartupPerformanceMonitor.mark("activity.onResume")
        val nowElapsed = android.os.SystemClock.elapsedRealtime()

        if (startupDatabaseReady) {
            // Read the persisted monotonic timestamp so Activity recreation does not
            // reset the interval. Do not consume it before startup security settings load.
            val backgroundedAt = sharedPrefs.getLong(KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME, -1L)
                .takeIf { it >= 0L }
            val backgroundDurationExceeded = BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAtElapsedRealtime = backgroundedAt,
                resumedAtElapsedRealtime = nowElapsed
            )
            if (backgroundedAt != null) {
                sharedPrefs.edit().remove(KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME).apply()
            }

            applySecurityRuntimeSettings(target = null)
            val timeoutMinutes = securitySettingValue("autoLogoutMinutes").toIntOrNull()?.coerceIn(0, 1440) ?: 0

            // onStop records the actual background transition. onPause is intentionally
            // ignored because it also runs for temporary system UI and short interruptions.
            val authenticationPageOwnsFlow = webPageOwnsAuthentication()
            if (backgroundDurationExceeded &&
                !authenticationPageOwnsFlow &&
                !currentAuthToken.isNullOrBlank() &&
                requiresSecurityReauthentication()
            ) {
                updateSecurityLockPending(true)
                webView?.visibility = View.INVISIBLE
                DebugLogger.logEvent(
                    "security_background_timeout",
                    "Background duration reached at least 60000ms; reauthentication required"
                )
            }

            if (timeoutMinutes > 0 && !currentAuthToken.isNullOrBlank() &&
                nowElapsed - lastSecurityInteractionElapsed >= timeoutMinutes * 60_000L
            ) {
                forceSecurityLogout("security_auto_logout")
            } else if (securityLockPending &&
                !authenticationPageOwnsFlow &&
                !currentAuthToken.isNullOrBlank()
            ) {
                handler.post { requestSecurityReauthentication() }
            }
            handler.removeCallbacks(securityIdleRunnable)
            handler.postDelayed(securityIdleRunnable, 5_000L)
        }
        if (!securityLockPending && !securityPromptActive && webView?.visibility == View.INVISIBLE) {
            webView?.visibility = View.VISIBLE
        }
        webView?.onResume()
        updateUIState()
        val businessDayPayload = JSONObject().apply {
            put("business_day", BusinessDay.currentDate())
            put("start", BusinessDay.context().startDateTime)
            put("end", BusinessDay.context().endDateTime)
        }
        webView?.evaluateJavascript("window.dispatchEvent(new CustomEvent('business-day-changed',{detail:$businessDayPayload}))", null)
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

    override fun onSaveInstanceState(outState: Bundle) {
        val currentPage = webView?.url
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('/')
            .orEmpty()
        val hasAuthenticatedSession = !currentAuthToken.isNullOrBlank() && currentUserId > 0L
        val wasAuthenticatedShell = hasAuthenticatedSession && (
            currentPage.isNotBlank() &&
                currentPage !in setOf("login.html", "lock-screen.html", "first-user-setup.html") ||
                securityLockPending
            )
        outState.putBoolean(STATE_WAS_AUTHENTICATED_SHELL, wasAuthenticatedShell)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        StartupPerformanceMonitor.mark("activity.onPause")
        // onPause also fires for transient system UI and short interruptions. It must
        // not lock the user; the 60-second timer starts only from onStop.
        super.onPause()
        webView?.onPause()
        DebugLogger.logEvent("app_paused", "Application paused")
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!securityLockPending && !securityPromptActive) {
            lastSecurityInteractionElapsed = android.os.SystemClock.elapsedRealtime()
        }
    }

    override fun onStop() {
        StartupPerformanceMonitor.mark("activity.onStop")
        StartupPerformanceMonitor.flushReport("activity_stopped")
        appInForeground = false
        // Persist the monotonic start time in encrypted preferences. This survives
        // Activity recreation while avoiding wall-clock changes and Activity-age timing.
        val shouldStartBackgroundClock = BackgroundReauthenticationPolicy.shouldRecordBackgroundTransition(
            isChangingConfigurations = isChangingConfigurations,
            isExternalPickerActive = isDatabaseBackupPickerActive,
            hasOtherStartedActivity = MyApplication.hasOtherStartedActivity(this)
        )
        if (shouldStartBackgroundClock &&
            sharedPrefs.getLong(KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME, -1L) < 0L
        ) {
            sharedPrefs.edit()
                .putLong(KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime())
                .apply()
        }

        // A biometric sheet may be cancelled when another app comes forward. Preserve
        // the existing pending-lock state, but do not create a new lock just because
        // the Activity stopped; onResume applies the exact 60-second threshold.
        if (securityPromptActive && !isChangingConfigurations && !isDatabaseBackupPickerActive) {
            biometricCancellationExpected = true
            biometricPrompt?.cancelAuthentication()
            biometricPrompt = null
            securityPromptActive = false
            securityFallbackDialog?.dismiss()
            securityFallbackDialog = null
        }
        // Don't treat opening the system document picker as leaving the app.
        if (!isChangingConfigurations && !isDatabaseBackupPickerActive) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    if (::dbHelper.isInitialized &&
                        dbHelper.getSetting("automaticBackupOnExit").trim().lowercase() in setOf("1", "true", "yes", "on") &&
                        dbHelper.getSetting("includeDatabaseBackup").trim().lowercase() !in setOf("0", "false", "no", "off") &&
                        FullDatabaseBackupManager.selectedTreeUri(this@MainActivity) != null
                    ) {
                        val lifecyclePrefs = getSharedPreferences("database_backup_lifecycle", Context.MODE_PRIVATE)
                        val now = System.currentTimeMillis()
                        val lastQueued = lifecyclePrefs.getLong("last_exit_backup_at", 0L)
                        if (now - lastQueued >= 5 * 60 * 1000L) {
                            lifecyclePrefs.edit().putLong("last_exit_backup_at", now).apply()
                            FullDatabaseBackupScheduler.scheduleOnExit(applicationContext)
                        }
                    }
                }.onFailure { DebugLogger.logException("DatabaseBackupOnExit", it) }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        StartupPerformanceMonitor.mark("activity.onDestroy")
        StartupPerformanceMonitor.detachWindow(window)
        StartupPerformanceMonitor.flushReport("activity_destroyed")
        isDestroyed.set(true)

        backgroundJob?.cancel()
        backgroundJob = null
        maintenanceJob?.cancel()
        maintenanceJob = null

        handler.removeCallbacksAndMessages(null)

        LiveUpdateHub.unsubscribe(liveUpdateListener)
        DebugLogger.detachWebView()
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null

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
        StartupPerformanceMonitor.endSpan(activityCreatePerformanceSpan, success = false)
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

        pendingWebPermissionRequest?.let { request ->
            if (isPermissionGranted(Manifest.permission.CAMERA)) {
                request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                request.deny()
            }
            pendingWebPermissionRequest = null
        }

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

        // Resume durable outbox entries after SMS permission is granted and
        // persist a station-scoped due-date reminder schedule for background use.
        if (isPermissionGranted(Manifest.permission.SEND_SMS)) {
            runCatching {
                val db = dbHelper
                SmsOutboxRepository.requeuePermissionFailures(db)
                if (SmsOutboxRepository.hasPending(db)) {
                    SmsOutboxWorker.schedule(this)
                }

                val userId = currentUserId
                val stationId = db.getUserById(userId)?.optInt("station_id", 0) ?: 0
                if (userId > 0L && stationId > 0) {
                    DebtDueReminderWorker.schedule(this, stationId, userId)
                }
            }.onFailure {
                DebugLogger.warn("SMS", "Unable to resume pending SMS/reminder work: ${it.message}")
            }
        }
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

    private fun scheduleDebtReminderWork(userId: Long) {
        if (userId <= 0L || !isPermissionGranted(Manifest.permission.SEND_SMS)) return
        runCatching {
            val stationId = dbHelper.getUserById(userId)?.optInt("station_id", 0) ?: 0
            if (stationId > 0) {
                DebtDueReminderWorker.schedule(this, stationId, userId)
            }
        }.onFailure {
            DebugLogger.warn("SMS", "Unable to schedule due-date debt reminders: ${it.message}")
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
        val performanceSpan = StartupPerformanceMonitor.beginSpan("startup.system.initialize_work")
        var initializationSucceeded = false
        try {
            val databaseSpan = StartupPerformanceMonitor.beginSpan("startup.system.database")
            try {
                initializeDatabase()
            } finally {
                StartupPerformanceMonitor.endSpan(databaseSpan)
            }
            val smsSettingsSpan = StartupPerformanceMonitor.beginSpan("startup.system.sms_settings")
            try {
                initializeSmsSettings()
            } finally {
                StartupPerformanceMonitor.endSpan(smsSettingsSpan)
            }
            DebugLogger.logEvent("app_started", "Application started")
            val healthSpan = StartupPerformanceMonitor.beginSpan("startup.system.health_check")
            try {
                withContext(Dispatchers.IO) { checkSmsSystemHealth() }
            } finally {
                StartupPerformanceMonitor.endSpan(healthSpan)
            }
            startupHealthCheckCompleted = true
            initializationSucceeded = true
            StartupPerformanceMonitor.mark("startup.system.ready")
            maybeReportFullyDrawn()
        } catch (e: Exception) {
            StartupPerformanceMonitor.mark(
                "startup.system.failed",
                mapOf("exception" to e.javaClass.simpleName)
            )
            StartupPerformanceMonitor.flushReport("startup_system_failed")
            DebugLogger.logException("Init", e)
            handleApplicationError(e)
        } finally {
            StartupPerformanceMonitor.endSpan(performanceSpan, initializationSucceeded)
        }
    }

    private fun maybeReportFullyDrawn() {
        if (startupPerformanceReportedFullyDrawn || !firstAssetPageFinished ||
            !startupDatabaseReady || !startupHealthCheckCompleted || isDestroyed.get()
        ) return
        startupPerformanceReportedFullyDrawn = true
        StartupPerformanceMonitor.mark("startup.fully_drawn")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            reportFullyDrawn()
        }
        StartupPerformanceMonitor.flushReport("fully_drawn")
    }

    // ============================================================
    // دوال مساعدة (WebView، تحميل الأصول، إلخ)
    // ============================================================

    /**
     * Clears authentication and Remember-Me only after Android reports a different
     * device boot. Ordinary activity/process recreation and explicit app close preserve it.
     * BOOT_COUNT is read when the app next starts because an app cannot reliably run at power-off.
     */
    private fun clearSessionAfterDeviceRebootIfNeeded(db: DatabaseHelper) {
        val bootCount = try {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            DebugLogger.warn("SessionBoot", "Unable to read Android BOOT_COUNT: ${e.javaClass.simpleName}")
            -1
        }
        if (bootCount < 0) {
            DebugLogger.warn("SessionBoot", "BOOT_COUNT unavailable; authentication state was preserved")
            return
        }

        val previousBootCount = sharedPrefs.getInt(KEY_LAST_BOOT_COUNT, -1)
        if (previousBootCount < 0) {
            sharedPrefs.edit().putInt(KEY_LAST_BOOT_COUNT, bootCount).apply()
            DebugLogger.info("SessionBoot", "Initialized boot marker=$bootCount without clearing authentication")
            return
        }
        if (previousBootCount == bootCount) return

        val tokens = listOf(
            sharedPrefs.getString(KEY_TOKEN, null),
            sharedPrefs.getString("saved_token", null)
        ).filterNot { it.isNullOrBlank() }.distinct()

        tokens.forEach { token ->
            try {
                db.invalidateUserSession(token!!, "device_reboot")
            } catch (e: Exception) {
                DebugLogger.warn("SessionBoot", "Session invalidation after reboot failed: ${e.javaClass.simpleName}")
            }
        }

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
            .remove(KEY_REQUIRE_LOGIN_ON_NEXT_LAUNCH)
            .putInt(KEY_LAST_BOOT_COUNT, bootCount)
            .apply()
        DebugLogger.info("SessionBoot", "Device reboot detected ($previousBootCount -> $bootCount); authentication and Remember-Me cleared")
    }

    /**
     * All application screens are packaged assets backed by the Android/SQLite bridge.
     * Normalize legacy banners that imply changes are merely queued for later syncing:
     * local CRUD is committed to SQLite immediately; Internet-dependent integrations
     * (for example external AI/SMS delivery) still report their own connection errors.
     */
    private fun normalizeOfflineFirstUi(target: WebView?) {
        if (target == null || isDestroyed.get()) return
        val script = """
            (function () {
                try {
                    var replacement = ' لا يوجد اتصال بالإنترنت؛ يعمل النظام محلياً عبر SQLite، وتُحفظ التغييرات مباشرة على الجهاز.';
                    var selector = '.offline-banner,.offline,.connection-bar,[role="alert"],.toast,.notification';
                    function rewrite(node) {
                        if (!node || !node.textContent) return;
                        var text = String(node.textContent).replace(/\s+/g, ' ').trim();
                        var offline = /عدم\s+الاتصال|غير\s+متصل|لا\s+يوجد\s+اتصال/i.test(text);
                        var suggestsQueue = /سيتم.{0,40}(?:حفظ|تخزين).{0,30}محلي.{0,8}/i.test(text);
                        if (!offline || !suggestsQueue) return;
                        var icon = node.querySelector ? node.querySelector('i') : null;
                        node.textContent = '';
                        if (icon) node.appendChild(icon);
                        node.appendChild(document.createTextNode(replacement));
                    }
                    function scan(root) {
                        if (!root) return;
                        if (root.nodeType === 1 && root.matches && root.matches(selector)) rewrite(root);
                        if (root.querySelectorAll) root.querySelectorAll(selector).forEach(rewrite);
                    }
                    scan(document);
                    if (window.__stationOfflineUiObserver) window.__stationOfflineUiObserver.disconnect();

                    // Avoid whole-screen DOM walks for every notification/toast mutation.
                    // Scan only the nearest candidate; inspect descendants only for new subtrees.
                    var pendingOfflineNodes = new Set();
                    var offlineScanScheduled = false;
                    function scheduleOfflineRewrite(node, includeDescendants) {
                        if (!node) return;
                        var element = node.nodeType === 1 ? node : node.parentElement;
                        if (!element) return;
                        var nearest = element.closest ? element.closest(selector) : null;
                        if (nearest) pendingOfflineNodes.add(nearest);
                        if (element.matches && element.matches(selector)) pendingOfflineNodes.add(element);
                        if (includeDescendants && element.querySelectorAll) {
                            element.querySelectorAll(selector).forEach(function (candidate) {
                                pendingOfflineNodes.add(candidate);
                            });
                        }
                        if (offlineScanScheduled) return;
                        offlineScanScheduled = true;
                        var runOfflineRewrite = function () {
                            offlineScanScheduled = false;
                            var candidates = Array.from(pendingOfflineNodes);
                            pendingOfflineNodes.clear();
                            candidates.forEach(rewrite);
                        };
                        if (window.requestAnimationFrame) window.requestAnimationFrame(runOfflineRewrite);
                        else setTimeout(runOfflineRewrite, 0);
                    }

                    window.__stationOfflineUiObserver = new MutationObserver(function (records) {
                        records.forEach(function (record) {
                            scheduleOfflineRewrite(record.target, false);
                            if (record.addedNodes) {
                                record.addedNodes.forEach(function (node) {
                                    if (node && node.nodeType === 1) scheduleOfflineRewrite(node, true);
                                });
                            }
                        });
                    });
                    if (document.body) {
                        window.__stationOfflineUiObserver.observe(document.body, {
                            childList: true, subtree: true, characterData: true
                        });
                    }
                } catch (_) {}
            })();
        """.trimIndent()
        runCatching { target.evaluateJavascript(script, null) }
            .onFailure { DebugLogger.warn("OfflineUi", "Could not normalize offline banner: ${it.javaClass.simpleName}") }
    }

    private fun shouldOpenSystemSetupScreen(): Boolean {
        if (!::sharedPrefs.isInitialized || sharedPrefs.getBoolean(KEY_SYSTEM_SETUP_COMPLETED, false)) {
            return false
        }
        return startupDatabaseReady && startupSetupRequired
    }

    /** Called only from the startup IO dispatcher after the database schema is ready. */
    private fun querySystemSetupRequired(): Boolean {
        if (!::sharedPrefs.isInitialized || sharedPrefs.getBoolean(KEY_SYSTEM_SETUP_COMPLETED, false)) {
            return false
        }
        return try {
            val realUserCount = dbHelper.readableDatabase.rawQuery(
                """
                SELECT COUNT(*)
                FROM users
                WHERE is_deleted = 0
                """.trimIndent(),
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            realUserCount <= 0L
        } catch (e: Exception) {
            // Fail closed: إذا تعذر إثبات أن الإعداد اكتمل، لا نفتح login قبل التحقق.
            DebugLogger.logException("SystemSetupGate", e)
            true
        }
    }

    private fun initialEntryAsset(): String {
        if (!startupDatabaseReady) return "login.html"
        if (shouldOpenSystemSetupScreen()) return "first-user-setup.html"

        // Explicit close requests a fresh biometric/password login on the next launch.
        if (sharedPrefs.getBoolean(KEY_REQUIRE_LOGIN_ON_NEXT_LAUNCH, false)) return "login.html"

        // Restore the authenticated shell after Activity recreation. A rotation must
        // not send an already-authenticated user through login.html / Remember-Me again.
        // Fresh launches retain the existing login and Remember-Me behavior; explicit
        // logout and invalid sessions still route to login.html.
        val hasActiveSession = !currentAuthToken.isNullOrBlank() && currentUserId > 0L
        return if (BackgroundReauthenticationPolicy.shouldRestoreAuthenticatedShell(
                hasActiveSession = hasActiveSession,
                loginRequiredOnLaunch = sharedPrefs.getBoolean(KEY_REQUIRE_LOGIN_ON_NEXT_LAUNCH, false),
                wasAuthenticatedShell = wasAuthenticatedShellAtRecreation,
                securityLockPending = securityLockPending
            )
        ) {
            "main.html"
        } else {
            "login.html"
        }
    }

    private fun updateStartupRouteAfterPageFinished(url: String?) {
        if (!startupDatabaseReady || url.isNullOrBlank()) return
        val page = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val completedSetupLogin = page == "login.html" && !shouldOpenSystemSetupScreen()
        if (page != startupExpectedAsset && !completedSetupLogin) return
        if (completedSetupLogin) startupExpectedAsset = "login.html"

        startupUiState = when (page) {
            "login.html" -> if (shouldOpenSystemSetupScreen()) "redirecting" else "ready"
            "first-user-setup.html" -> if (shouldOpenSystemSetupScreen()) "setup_required" else "ready"
            "main.html" -> "protected"
            else -> startupUiState
        }

        if (page == "login.html" && shouldOpenSystemSetupScreen()) {
            startupExpectedAsset = "first-user-setup.html"
            startupUiState = "redirecting"
            webView?.post { webView?.loadUrl(entryAssetUrl(startupExpectedAsset)) }
        } else if (page == "main.html" && securityLockPending && !currentAuthToken.isNullOrBlank()) {
            webView?.visibility = View.INVISIBLE
            handler.post { requestSecurityReauthentication() }
        }
    }

    private fun scheduleStartupPermissionsRequest() {
        if (startupPermissionsRequested || !startupDatabaseReady || !serverReady ||
            startupUiState == "initializing" || startupUiState == "redirecting"
        ) return
        startupPermissionsRequested = true
        handler.postDelayed({
            if (!isDestroyed.get()) requestAllPermissions()
        }, 1_200L)
    }

    private fun startupStateForBridge(): String = startupUiState

    private fun startupErrorForBridge(): String = startupErrorMessage

    private fun entryAssetUrl(entryAsset: String): String =
        if (entryAsset == "main.html") {
            "file:///android_asset/main.html"
        } else {
            "file:///android_asset/screens/$entryAsset"
        }

    private fun loadWebViewFromAssets() {
        if (isDestroyed.get()) return
        val wv = webView ?: return

        try {
            if (wv.isAttachedToWindow) {
                val entryAsset = initialEntryAsset()
                val entryUrl = entryAssetUrl(entryAsset)
                val currentUrl = wv.url?.substringBefore('?')?.substringBefore('#')
                if (currentUrl == entryUrl) return
                DebugLogger.info("WebView", "Loading $entryUrl")
                wv.loadUrl(entryUrl)
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


    private fun isSecuritySettingKey(key: String): Boolean =
        key in SecuritySettingsStore.SETTING_KEYS || key == SecuritySettingsStore.KEY_PIN_CODE

    private fun isTrueSetting(value: String?): Boolean =
        value?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

    private fun securitySettingValue(key: String): String {
        if (!::securitySettingsStore.isInitialized) return SecuritySettingsStore.DEFAULTS[key] ?: ""
        if (key == SecuritySettingsStore.KEY_PIN_CODE) return securitySettingsStore.pinValueForUi()
        if (key == SecuritySettingsStore.KEY_ENCRYPT_STORAGE) {
            return if (securitySettingsStore.encryptionEnabled()) "1" else "0"
        }
        if (securitySettingsStore.encryptionEnabled()) {
            return securitySettingsStore.getSetting(key) ?: SecuritySettingsStore.DEFAULTS[key] ?: "0"
        }
        if (!::dbHelper.isInitialized) return SecuritySettingsStore.DEFAULTS[key] ?: "0"
        return dbHelper.getSetting(key).takeIf { it.isNotBlank() }
            ?: SecuritySettingsStore.DEFAULTS[key] ?: "0"
    }

    /**
     * Remove legacy plaintext copies from system_settings as well as the generic settings
     * table. DatabaseHelper.getSetting() falls back to system_settings, so removing only
     * the generic row can leave a readable plaintext copy behind.
     */
    private fun removeLegacySystemSetting(db: DatabaseHelper, key: String): Int {
        val sqlite = db.writableDatabase
        val tableExists = sqlite.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'system_settings' LIMIT 1",
            null
        ).use { it.moveToFirst() }
        if (!tableExists) return 0

        val removed = sqlite.delete("system_settings", "setting_key = ?", arrayOf(key))
        val remains = sqlite.rawQuery(
            "SELECT 1 FROM system_settings WHERE setting_key = ? LIMIT 1",
            arrayOf(key)
        ).use { it.moveToFirst() }
        check(!remains) { "تعذر حذف النسخة القديمة من system_settings: $key" }
        return removed
    }

    private fun removeLegacySecuritySetting(db: DatabaseHelper, key: String): Int {
        val settingsRows = db.deleteSetting(key)
        val systemRows = removeLegacySystemSetting(db, key)
        check(db.getSetting(key).isBlank()) {
            "بقيت نسخة غير مشفرة من إعداد الأمان في SQLite: $key"
        }
        return settingsRows + systemRows
    }

    private fun normalizeSecuritySetting(key: String, value: String): String {
        return when (key) {
            "requireDeviceUnlock", "encryptStorage", "biometricLockEnabled",
            "hideSensitiveData", "preventScreenshots", "secureBackupEnabled" ->
                if (isTrueSetting(value)) "1" else "0"
            "autoLogoutMinutes" -> (value.trim().toIntOrNull() ?: 0).coerceIn(0, 1440).toString()
            else -> value
        }
    }

    /** Moves only the security panel's allowlisted settings; the operational SQLite database is untouched. */
    private fun migrateLegacySecuritySettings(db: DatabaseHelper) {
        val legacyEncryption = runCatching {
            db.getSetting(SecuritySettingsStore.KEY_ENCRYPT_STORAGE)
        }.getOrDefault("")
        if (!securitySettingsStore.hasStoredSetting(SecuritySettingsStore.KEY_ENCRYPT_STORAGE)) {
            val initialMode = if (legacyEncryption.isBlank()) "1" else normalizeSecuritySetting(
                SecuritySettingsStore.KEY_ENCRYPT_STORAGE, legacyEncryption
            )
            securitySettingsStore.putSetting(SecuritySettingsStore.KEY_ENCRYPT_STORAGE, initialMode)
        }
        if (legacyEncryption.isNotBlank()) {
            removeLegacySecuritySetting(db, SecuritySettingsStore.KEY_ENCRYPT_STORAGE)
        }

        val legacyPin = runCatching { db.getSetting(SecuritySettingsStore.KEY_PIN_CODE) }.getOrDefault("")
        if (legacyPin.isNotBlank() && !securitySettingsStore.isPinConfigured()) {
            runCatching { securitySettingsStore.savePinCode(legacyPin) }
                .onFailure { DebugLogger.warn("SecuritySettings", "Legacy PIN could not be migrated; it was not retained in plaintext") }
        }
        // PIN must never remain in either SQLite settings table, even if it was invalid or
        // had been written by the older system-settings screen.
        removeLegacySecuritySetting(db, SecuritySettingsStore.KEY_PIN_CODE)

        val encrypt = securitySettingsStore.encryptionEnabled()
        val migratable = SecuritySettingsStore.SETTING_KEYS - SecuritySettingsStore.KEY_ENCRYPT_STORAGE
        if (encrypt) {
            migratable.forEach { key ->
                val legacy = runCatching { db.getSetting(key) }.getOrDefault("")
                if (legacy.isNotBlank() && !securitySettingsStore.hasStoredSetting(key)) {
                    securitySettingsStore.putSetting(key, normalizeSecuritySetting(key, legacy))
                }
                // Clear both historical storage paths after the encrypted write has committed.
                removeLegacySecuritySetting(db, key)
            }
        } else {
            migratable.forEach { key ->
                val secureValue = if (securitySettingsStore.hasStoredSetting(key)) {
                    securitySettingsStore.getSetting(key)
                } else null
                val legacy = runCatching { db.getSetting(key) }.getOrDefault("")
                val effective = secureValue ?: legacy.takeIf { it.isNotBlank() }
                if (effective != null) {
                    check(db.setSetting(key, normalizeSecuritySetting(key, effective))) {
                        "تعذر ترحيل الإعداد إلى SQLite: $key"
                    }
                }
                removeLegacySystemSetting(db, key)
                if (secureValue != null) securitySettingsStore.removeSetting(key)
            }
        }
    }

    private fun switchSecurityStorage(db: DatabaseHelper, enableEncryption: Boolean) {
        if (securitySettingsStore.encryptionEnabled() == enableEncryption) {
            securitySettingsStore.putSetting(SecuritySettingsStore.KEY_ENCRYPT_STORAGE, if (enableEncryption) "1" else "0")
            return
        }
        val migratable = SecuritySettingsStore.SETTING_KEYS - SecuritySettingsStore.KEY_ENCRYPT_STORAGE
        if (enableEncryption) {
            migratable.forEach { key ->
                val legacy = runCatching { db.getSetting(key) }.getOrDefault("")
                if (legacy.isNotBlank() && !securitySettingsStore.hasStoredSetting(key)) {
                    securitySettingsStore.putSetting(key, normalizeSecuritySetting(key, legacy))
                }
                removeLegacySecuritySetting(db, key)
            }
            securitySettingsStore.putSetting(SecuritySettingsStore.KEY_ENCRYPT_STORAGE, "1")
        } else {
            migratable.forEach { key ->
                val secureValue = if (securitySettingsStore.hasStoredSetting(key)) {
                    securitySettingsStore.getSetting(key)
                } else null
                val legacy = runCatching { db.getSetting(key) }.getOrDefault("")
                val effective = secureValue ?: legacy.takeIf { it.isNotBlank() }
                if (effective != null) {
                    check(db.setSetting(key, normalizeSecuritySetting(key, effective))) {
                        "تعذر ترحيل الإعداد إلى SQLite: $key"
                    }
                }
                removeLegacySystemSetting(db, key)
                if (secureValue != null) securitySettingsStore.removeSetting(key)
            }
            securitySettingsStore.putSetting(SecuritySettingsStore.KEY_ENCRYPT_STORAGE, "0")
        }
    }

    private fun saveSecuritySetting(db: DatabaseHelper, key: String, value: String) {
        when (key) {
            SecuritySettingsStore.KEY_PIN_CODE -> {
                securitySettingsStore.savePinCode(value)
                removeLegacySecuritySetting(db, SecuritySettingsStore.KEY_PIN_CODE)
            }
            SecuritySettingsStore.KEY_ENCRYPT_STORAGE -> {
                switchSecurityStorage(db, isTrueSetting(value))
            }
            else -> {
                val normalized = normalizeSecuritySetting(key, value)
                if (securitySettingsStore.encryptionEnabled()) {
                    securitySettingsStore.putSetting(key, normalized)
                    removeLegacySecuritySetting(db, key)
                } else {
                    check(db.setSetting(key, normalized)) { "تعذر حفظ إعداد الأمان في SQLite" }
                    removeLegacySystemSetting(db, key)
                    securitySettingsStore.removeSetting(key)
                }
            }
        }
        applySecurityRuntimeSettings()
    }

    private fun applySecurityRuntimeSettings(target: WebView? = webView) {
        if (!::securitySettingsStore.isInitialized) return
        val blockScreenshots = isTrueSetting(securitySettingValue("preventScreenshots"))
        val hideSensitive = isTrueSetting(securitySettingValue("hideSensitiveData"))
        val script = if (hideSensitive) {
            """(function(){
                var id='__station_sensitive_mask', style=document.getElementById(id);
                if(!style){style=document.createElement('style');style.id=id;(document.head||document.documentElement).appendChild(style);}
                style.textContent='[data-sensitive], [data-sensitive="true"], .sensitive, .sensitive-data, .sensitive-value, .customer-phone, .customer-id, .account-number, .bank-account-number, .national-id, input[type="tel"], input[type="email"], input[autocomplete*="tel"], input[autocomplete*="email"], input[autocomplete*="cc-number"], input[autocomplete*="off"][name*="phone" i], input[autocomplete*="off"][name*="mobile" i], input[autocomplete*="off"][name*="account" i], input[autocomplete*="off"][name*="identity" i] { filter: blur(7px) !important; user-select: none !important; }';
                function maskSensitiveNodes(root){
                  (root||document).querySelectorAll('table').forEach(function(table){
                    var rows=Array.from(table.rows||[]); if(!rows.length)return;
                    var headers=Array.from(rows[0].cells||[]).map(function(c){return (c.innerText||c.textContent||'').trim().toLowerCase();});
                    var sensitive=headers.map(function(h){return /phone|mobile|contact|customer|client|account|bank|identity|national|رقم الهاتف|الجوال|العميل|الحساب|الهوية|البطاقة/.test(h);});
                    rows.slice(1).forEach(function(row){Array.from(row.cells||[]).forEach(function(cell,index){if(sensitive[index])cell.classList.add('__station_sensitive_runtime');});});
                  });
                  (root||document).querySelectorAll('input,textarea,[data-sensitive],[data-sensitive="true"],.sensitive,.sensitive-data,.sensitive-value').forEach(function(node){
                    var name=[node.id,node.name,node.getAttribute('placeholder'),node.getAttribute('aria-label'),node.getAttribute('autocomplete')].filter(Boolean).join(' ').toLowerCase();
                    if(/phone|mobile|email|account|bank|identity|national|customer.?id|رقم الهاتف|الجوال|البريد|الحساب|الهوية|العميل/.test(name))node.classList.add('__station_sensitive_runtime');
                  });
                }
                var extra=document.createElement('style');extra.id='__station_sensitive_runtime_style';
                extra.textContent='.__station_sensitive_runtime { filter: blur(7px) !important; user-select:none !important; }';(document.head||document.documentElement).appendChild(extra);
                maskSensitiveNodes(document);
                if(window.__stationSecurityMaskObserver)window.__stationSecurityMaskObserver.disconnect();
                window.__stationSecurityMaskObserver=new MutationObserver(function(records){records.forEach(function(record){record.addedNodes&&record.addedNodes.forEach(function(node){if(node.nodeType===1)maskSensitiveNodes(node.parentNode||document);});});});
                window.__stationSecurityMaskObserver.observe(document.documentElement,{childList:true,subtree:true});
              })();"""
        } else {
            """(function(){var s=document.getElementById('__station_sensitive_mask');if(s)s.remove();var extra=document.getElementById('__station_sensitive_runtime_style');if(extra)extra.remove();document.querySelectorAll('.__station_sensitive_runtime').forEach(function(node){node.classList.remove('__station_sensitive_runtime');});if(window.__stationSecurityMaskObserver){window.__stationSecurityMaskObserver.disconnect();window.__stationSecurityMaskObserver=null;}})();"""
        }
        runOnUiThread {
            if (isDestroyed.get()) return@runOnUiThread
            if (blockScreenshots) {
                window.setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                )
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            runCatching { target?.evaluateJavascript(script, null) }
        }
    }

    private fun requiresSecurityReauthentication(): Boolean =
        isTrueSetting(securitySettingValue("requireDeviceUnlock")) ||
            isTrueSetting(securitySettingValue("biometricLockEnabled"))

    /**
     * login.html and lock-screen.html own their Remember-Me / session authentication.
     * The native foreground gate must not stack another prompt over those flows.
     */
    private fun webPageOwnsAuthentication(): Boolean {
        if (!::sharedPrefs.isInitialized) return false
        if (sharedPrefs.getBoolean(KEY_REQUIRE_LOGIN_ON_NEXT_LAUNCH, false)) return true
        val pagePath = webView?.url?.substringBefore('?')?.substringBefore('#').orEmpty()
        return pagePath.endsWith("/login.html") || pagePath.endsWith("/lock-screen.html")
    }

    /**
     * Keep a pending foreground lock across Activity recreation. Clearing a successful
     * challenge also removes any stale background timestamp, without touching auth or
     * Remember-Me tokens.
     */
    private fun updateSecurityLockPending(pending: Boolean) {
        securityLockPending = pending
        if (::sharedPrefs.isInitialized) {
            val editor = sharedPrefs.edit().putBoolean(KEY_SECURITY_REAUTH_PENDING, pending)
            if (!pending) editor.remove(KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME)
            editor.apply()
        }
    }

    private fun requestSecurityReauthentication() {
        if (!appInForeground || !securityLockPending || securityPromptActive || currentAuthToken.isNullOrBlank() || isFinishing) return
        webView?.visibility = View.INVISIBLE
        securityPromptActive = true
        if (isTrueSetting(securitySettingValue("biometricLockEnabled"))) {
            showBiometricPrompt(
                onSuccess = {
                    securityPromptActive = false
                    updateSecurityLockPending(false)
                    lastSecurityInteractionElapsed = android.os.SystemClock.elapsedRealtime()
                    applySecurityRuntimeSettings()
                    webView?.visibility = View.VISIBLE
                },
                onError = { reason ->
                    // A failed fingerprint touch is non-terminal: BiometricPrompt remains
                    // visible for another attempt. Only terminal errors fall back to PIN.
                    if (reason == "failed") {
                        securityPromptActive = true
                    } else {
                        securityPromptActive = false
                        showSecurityPinFallback()
                    }
                }
            )
            return
        }

        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val credentialIntent = runCatching {
            if (keyguard?.isKeyguardSecure == true) {
                keyguard.createConfirmDeviceCredentialIntent("فتح محطة أبو أحمد", "أكد هويتك للمتابعة")
            } else null
        }.getOrNull()
        if (credentialIntent != null) {
            try {
                startActivityForResult(credentialIntent, SECURITY_DEVICE_UNLOCK_REQUEST)
            } catch (_: Exception) {
                securityPromptActive = false
                showSecurityPinFallback()
            }
        } else {
            securityPromptActive = false
            showSecurityPinFallback()
        }
    }

    private fun showSecurityPinFallback() {
        if (isFinishing || isDestroyed.get() || securityFallbackDialog?.isShowing == true) return
        if (!securitySettingsStore.isPinConfigured()) {
            securityFallbackDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("التحقق مطلوب")
                .setMessage("تعذر التحقق البيومتري أو بواسطة قفل الجهاز، ولم يتم إعداد PIN. سجّل الدخول مجدداً للمتابعة.")
                .setPositiveButton("العودة لتسجيل الدخول") { _, _ -> forceSecurityLogout("security_unlock_fallback") }
                .setCancelable(false)
                .show()
            return
        }
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "رمز PIN"
            isSingleLine = true
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("فتح محطة أبو أحمد")
            .setMessage("أدخل رمز PIN للمتابعة.")
            .setView(input)
            .setPositiveButton("فتح", null)
            .setNegativeButton("استخدام تسجيل الدخول") { _, _ -> forceSecurityLogout("security_password_fallback") }
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val verified = runCatching { securitySettingsStore.verifyPinCode(input.text?.toString().orEmpty()) }.getOrDefault(false)
                if (verified) {
                    updateSecurityLockPending(false)
                    securityPromptActive = false
                    lastSecurityInteractionElapsed = android.os.SystemClock.elapsedRealtime()
                    applySecurityRuntimeSettings()
                    webView?.visibility = View.VISIBLE
                    dialog.dismiss()
                } else {
                    input.error = "رمز PIN غير صحيح أو المحاولات مقفلة مؤقتاً"
                    input.text?.clear()
                }
            }
        }
        securityFallbackDialog = dialog
        dialog.setOnDismissListener { if (securityFallbackDialog === dialog) securityFallbackDialog = null }
        dialog.show()
    }

    private fun forceSecurityLogout(reason: String) {
        biometricCancellationExpected = true
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        securityFallbackDialog?.dismiss()
        securityFallbackDialog = null
        val token = currentAuthToken
        if (!token.isNullOrBlank() && ::dbHelper.isInitialized) {
            runCatching { dbHelper.invalidateUserSession(token, reason.take(50)) }
        }
        currentAuthToken = null
        currentUserId = 0L
        currentUserRole = ""
        currentUserName = ""
        // Keep Remember-Me material intact; only the active session token is removed.
        sharedPrefs.edit().remove(KEY_TOKEN).apply()
        updateSecurityLockPending(false)
        securityPromptActive = false
        lastSecurityInteractionElapsed = android.os.SystemClock.elapsedRealtime()
        webView?.post {
            if (!isDestroyed.get()) {
                webView?.visibility = View.VISIBLE
                webView?.loadUrl("file:///android_asset/screens/login.html?skipBiometricOnce=1")
            }
        }
    }

    private fun onSecurityDeviceUnlockResult(resultCode: Int) {
        securityPromptActive = false
        if (resultCode == RESULT_OK) {
            updateSecurityLockPending(false)
            lastSecurityInteractionElapsed = android.os.SystemClock.elapsedRealtime()
            applySecurityRuntimeSettings()
            webView?.visibility = View.VISIBLE
        } else {
            showSecurityPinFallback()
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
            securitySettingsStore = SecuritySettingsStore.open(applicationContext)
            DebugLogger.info("Prefs", "Encrypted authentication and security settings initialized")
        } catch (e: Exception) {
            // لا نسمح بالتراجع إلى SharedPreferences غير مشفرة؛ قد تحتوي هذه القيم على رمز الجلسة.
            DebugLogger.error("Prefs", "Encrypted prefs initialization failed", e)
            throw IllegalStateException("Secure preferences are required", e)
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

        val recoveryGeneration = webViewRecoveryTrigger.intValue
        androidx.compose.runtime.key(recoveryGeneration) {
            AndroidView(
                modifier = modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val webViewFactorySpan =
                        StartupPerformanceMonitor.beginSpan("webview.create_and_configure")
                    val wv = if (this@MainActivity.webView == null) {
                        WebView(context).apply {
                            if (securityLockPending) visibility = View.INVISIBLE
                            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                            isFocusable = true
                            isFocusableInTouchMode = true
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
                                // يسمح بتشغيل تنبيه نجاح مسح POS بعد التقاط الكاميرا دون اشتراط لمسة جديدة.
                                mediaPlaybackRequiresUserGesture = false
                                allowFileAccess = true
                                // واجهات التطبيق من android_asset فقط، ولا تحتاج قراءة content:// داخل WebView.
                                allowContentAccess = false
                                allowFileAccessFromFileURLs = false
                                allowUniversalAccessFromFileURLs = false
                                javaScriptCanOpenWindowsAutomatically = false
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                safeBrowsingEnabled = true
                                setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                loadsImagesAutomatically = true
                                setSupportMultipleWindows(false)
                                userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                            }

                            webViewClient = createWebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    pendingFilePathCallback?.onReceiveValue(null)
                                    pendingFilePathCallback = filePathCallback
                                    return try {
                                        val acceptType = fileChooserParams?.acceptTypes
                                            ?.firstOrNull { it.isNotBlank() } ?: "*/*"
                                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = acceptType
                                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                                        }
                                        startActivityForResult(intent, fileChooserRequestCode)
                                        true
                                    } catch (e: Exception) {
                                        pendingFilePathCallback?.onReceiveValue(null)
                                        pendingFilePathCallback = null
                                        DebugLogger.warn("WebView", "file chooser unavailable: ${e.message}")
                                        false
                                    }
                                }

                                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                                    val wantsCamera = request.resources?.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true
                                    if (!wantsCamera) {
                                        request.deny()
                                        return
                                    }
                                    if (isPermissionGranted(Manifest.permission.CAMERA)) {
                                        request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                                    } else {
                                        pendingWebPermissionRequest?.deny()
                                        pendingWebPermissionRequest = request
                                        requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
                                    }
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                    val msg = consoleMessage.message()
                                    val line = consoleMessage.lineNumber()
                                    val source = consoleMessage.sourceId()
                                    if (isDebugMode) {
                                        Log.d("WebViewConsole", "$msg (source: $source, line: $line)")
                                        DebugLogger.info("WebViewConsole", "$msg")
                                    }
                                    if (isDebugMode && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
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
                    StartupPerformanceMonitor.endSpan(webViewFactorySpan)

                    (wv.parent as? ViewGroup)?.removeView(wv)
                    addView(wv)
                    wv.isFocusable = true
                    wv.isFocusableInTouchMode = true
                    wv.requestFocus(View.FOCUS_DOWN)
                    this@MainActivity.webView = wv
                    this@MainActivity.isWebViewInitialized = true

                    DebugLogger.attachWebView(wv)

                    // setContent may be scheduled before Compose has created this view.
                    // Start the initial asset from the attached WebView rather than relying
                    // on a one-shot Activity post that can run while webView is still null.
                    wv.post {
                        if (!isDestroyed.get() && this@MainActivity.webView === wv) {
                            loadWebViewFromAssets()
                        }
                    }

                    val instanceId = ++webViewInstanceId
                    DebugLogger.info("WebView", "CREATED instance=$instanceId hash=${wv.hashCode()}")
                }
            },
                update = { }
            )
        }
    }

    private fun createStationImagePreviewDataUrl(uri: Uri): String? {
        return try {
            val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val maxDimension = 1280
            var sample = 1
            while ((bounds.outWidth / sample) > maxDimension || (bounds.outHeight / sample) > maxDimension) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val output = ByteArrayOutputStream()
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)) return null
            } finally {
                bitmap.recycle()
            }
            val encoded = android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
            "data:image/jpeg;base64,$encoded"
        } catch (e: Exception) {
            DebugLogger.logException("StationFilePreview", e)
            null
        }
    }

    private fun notifyDatabaseBackupJs(callbackExpression: String, payload: JSONObject) {
        val javascript = callbackExpression + "(" + JSONObject.quote(payload.toString()) + ");"
        webView?.post { safeEvaluateJs(javascript) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SECURITY_DEVICE_UNLOCK_REQUEST) {
            onSecurityDeviceUnlockResult(resultCode)
            return
        }
        if (requestCode == databaseBackupFolderPickerRequestCode) {
            isDatabaseBackupPickerActive = false
            val uri = data?.data
            if (resultCode == RESULT_OK && data != null && uri != null) {
                try {
                    val allowedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    val grantFlags = data.flags and allowedFlags
                    require(
                        grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                            grantFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                    ) { "مدير التخزين لم يمنح صلاحية القراءة والكتابة إلى المجلد" }
                    contentResolver.takePersistableUriPermission(uri, grantFlags)
                    getSharedPreferences(FullDatabaseBackupManager.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(FullDatabaseBackupManager.TREE_URI_KEY, uri.toString()).apply()
                    if (::dbHelper.isInitialized) dbHelper.setSetting("backupLocation", uri.toString())
                    val treeId = DocumentsContract.getTreeDocumentId(uri)
                    notifyDatabaseBackupJs(
                        "window.onDatabaseBackupFolderSelected",
                        JSONObject().put("success", true).put("uri", uri.toString())
                            .put("folder_name", treeId.substringAfterLast(':').substringAfterLast('/').ifBlank { "المجلد المحدد" })
                            .put("message", "تم حفظ صلاحية المجلد المختار")
                    )
                    DebugLogger.info("DatabaseBackupPicker", "Persisted selected backup tree URI")
                } catch (error: Exception) {
                    DebugLogger.logException("DatabaseBackupPicker", error)
                    notifyDatabaseBackupJs("window.onDatabaseBackupFolderSelected",
                        JSONObject().put("success", false).put("error", error.message ?: "تعذر حفظ المجلد المختار"))
                }
            } else {
                notifyDatabaseBackupJs("window.onDatabaseBackupFolderSelected",
                    JSONObject().put("success", false).put("cancelled", true).put("error", "تم إلغاء تحديد مكان النسخة الاحتياطية"))
            }
            return
        }

        if (requestCode == databaseBackupRestorePickerRequestCode) {
            isDatabaseBackupPickerActive = false
            val uri = data?.data
            if (resultCode == RESULT_OK && data != null && uri != null) {
                try {
                    val readFlag = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (readFlag != 0) runCatching { contentResolver.takePersistableUriPermission(uri, readFlag) }
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "ملف النسخة"
                    notifyDatabaseBackupJs("window.onDatabaseBackupRestoreFileSelected",
                        JSONObject().put("success", true).put("uri", uri.toString()).put("file_name", name))
                } catch (error: Exception) {
                    DebugLogger.logException("DatabaseBackupRestorePicker", error)
                    notifyDatabaseBackupJs("window.onDatabaseBackupRestoreFileSelected",
                        JSONObject().put("success", false).put("error", error.message ?: "تعذر قراءة الملف المحدد"))
                }
            } else {
                notifyDatabaseBackupJs("window.onDatabaseBackupRestoreFileSelected",
                    JSONObject().put("success", false).put("cancelled", true).put("error", "تم إلغاء اختيار النسخة الاحتياطية"))
            }
            return
        }

        if (requestCode == invoiceFolderPickerRequestCode) {
            val html = pendingInvoiceFolderHtml
            val phone = pendingInvoiceFolderPhone
            val action = pendingInvoiceFolderAction
            pendingInvoiceFolderHtml = null
            pendingInvoiceFolderPhone = ""
            pendingInvoiceFolderAction = ""
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && !html.isNullOrBlank() && action.isNotBlank()) {
                try {
                    val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(uri, flags)
                    val prefs = getSharedPreferences("invoice_pdf_archive", Context.MODE_PRIVATE)
                    prefs.edit().putString("tree_uri", uri.toString()).putLong("configured_at", System.currentTimeMillis()).apply()
                    DebugLogger.info("InvoicePdfArchive", "Persisted user-selected folder URI: $uri")
                    webView?.post {
                        safeEvaluateJs(
                            "window.resumeInvoicePdfAfterFolderSelection && window.resumeInvoicePdfAfterFolderSelection(" +
                                JSONObject.quote(action) + "," + JSONObject.quote(phone) + ");"
                        )
                    }
                } catch (e: Exception) {
                    DebugLogger.logException("InvoicePdfFolderPicker", e)
                    webView?.post {
                        safeEvaluateJs("window.handleAndroidResult(" + JSONObject.quote(action) + "," +
                            JSONObject.quote(JSONObject().put("success", false).put("error", e.message ?: "تعذر حفظ صلاحية مجلد الفواتير").toString()) + ");")
                    }
                }
            } else if (!action.isBlank()) {
                webView?.post {
                    safeEvaluateJs("window.handleAndroidResult(" + JSONObject.quote(action) + "," +
                        JSONObject.quote(JSONObject().put("success", false).put("cancelled", true).put("error", "أُلغي اختيار مجلد حفظ الفواتير").toString()) + ");")
                }
            }
            return
        }
        if (requestCode == mapPickerRequestCode) {
            if (resultCode == RESULT_OK && data != null) {
                val latitude = data.getDoubleExtra(MapPickerActivity.EXTRA_LATITUDE, Double.NaN)
                val longitude = data.getDoubleExtra(MapPickerActivity.EXTRA_LONGITUDE, Double.NaN)
                if (latitude.isFinite() && longitude.isFinite()) {
                    webView?.post {
                        safeEvaluateJs("window.onNativeMapLocationSelected && window.onNativeMapLocationSelected($latitude, $longitude);")
                    }
                    DebugLogger.info("Maps", "Native picker returned latitude=$latitude longitude=$longitude")
                }
            } else {
                DebugLogger.info("Maps", "Native picker cancelled")
            }
            return
        }
        if (requestCode == stationFilePickerRequestCode) {
            val kind = pendingStationFileKind
            pendingStationFileKind = null
            if (kind == null) return
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                try {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        DebugLogger.logException("StationFileUriPermission", e)
                    }

                    val name = contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else null
                    }?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                        ?: "ملف محدد"

                    val mime = contentResolver.getType(uri) ?: "image/*"
                    val dataUrl = createStationImagePreviewDataUrl(uri)
                    val js = "window.onStationFileSelected && window.onStationFileSelected(" +
                        JSONObject.quote(kind) + "," +
                        JSONObject.quote(uri.toString()) + "," +
                        JSONObject.quote(name) + "," +
                        JSONObject.quote(mime) + "," +
                        JSONObject.quote(dataUrl ?: "") + ");"
                    webView?.post { safeEvaluateJs(js) }
                    DebugLogger.info("StationFilePicker", "Selected kind=$kind name=$name uri=$uri")
                } catch (e: Exception) {
                    DebugLogger.logException("StationFilePicker", e)
                    webView?.post {
                        safeEvaluateJs(
                            "window.onStationFileSelectionError && window.onStationFileSelectionError(" +
                                JSONObject.quote(e.message ?: "تعذر قراءة الملف") + ");"
                        )
                    }
                }
            } else {
                webView?.post {
                    safeEvaluateJs(
                        "window.onStationFileSelectionCancelled && window.onStationFileSelectionCancelled(" +
                            JSONObject.quote(kind) + ");"
                    )
                }
                DebugLogger.info("StationFilePicker", "Cancelled kind=$kind")
            }
            return
        }

        if (requestCode == vehicleFilePickerRequestCode) {
            val kind = pendingVehicleFileKind
            pendingVehicleFileKind = null
            if (kind == null) return
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                try {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                        DebugLogger.info("VehicleFilePicker", "Persistable permission unavailable for $uri")
                    }
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "ملف محدد"
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val previewDataUrl = if (kind == "photo") createStationImagePreviewDataUrl(uri) else null
                    val js = "window.onVehicleFileSelected && window.onVehicleFileSelected(" +
                        JSONObject.quote(kind) + "," +
                        JSONObject.quote(uri.toString()) + "," +
                        JSONObject.quote(name) + "," +
                        JSONObject.quote(mime) + "," +
                        JSONObject.quote(previewDataUrl ?: "") + ");"
                    webView?.post { safeEvaluateJs(js) }
                    DebugLogger.info("VehicleFilePicker", "Selected kind=$kind name=$name uri=$uri")
                } catch (e: Exception) {
                    DebugLogger.logException("VehicleFilePicker", e)
                    webView?.post { safeEvaluateJs("window.onVehicleFileSelectionError && window.onVehicleFileSelectionError(" + JSONObject.quote(e.message ?: "تعذر قراءة الملف") + ");") }
                }
            } else {
                webView?.post { safeEvaluateJs("window.onVehicleFileSelectionCancelled && window.onVehicleFileSelectionCancelled(" + JSONObject.quote(kind) + ");") }
                DebugLogger.info("VehicleFilePicker", "Cancelled kind=$kind")
            }
            return
        }

        if (requestCode == fileChooserRequestCode) {
            val callback = pendingFilePathCallback
            pendingFilePathCallback = null
            if (callback != null) {
                val results = if (resultCode == RESULT_OK && data != null) {
                    val clipData = data.clipData
                    when {
                        clipData != null -> Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
                        data.data != null -> arrayOf(data.data!!)
                        else -> null
                    }
                } else null
                results?.firstOrNull()?.let { uri ->
          getSharedPreferences("webview_file_picker", Context.MODE_PRIVATE).edit()
              .putString("last_uri", uri.toString())
              .apply()
          try {
              contentResolver.takePersistableUriPermission(
                  uri,
                  Intent.FLAG_GRANT_READ_URI_PERMISSION
              )
          } catch (e: SecurityException) {
              DebugLogger.logException("UserAvatarUriPermission", e)
          }
      }
      callback.onReceiveValue(results)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Samples the local HTML screen's navigation, paint, resource, long-task and
     * interaction timing APIs. It sends numeric aggregates only, never DOM text or URLs.
     */
    private fun installWebPerformanceObserver(target: WebView?) {
        val script = """
            (function () {
                if (window.__stationPerformanceObserverInstalled) return;
                window.__stationPerformanceObserverInstalled = true;
                var page = (location.pathname || '').split('/').pop() || 'unknown';
                page = page.replace(/[^A-Za-z0-9_.-]/g, '').slice(0, 64) || 'unknown';
                var latest = {
                    page: page,
                    timeToFirstByteMs: 0,
                    domInteractiveMs: 0,
                    domContentLoadedMs: 0,
                    loadEventMs: 0,
                    firstPaintMs: 0,
                    firstContentfulPaintMs: 0,
                    largestContentfulPaintMs: 0,
                    cumulativeLayoutShift: 0,
                    resourceCount: 0,
                    resourceDurationMs: 0,
                    resourceTransferBytes: 0,
                    longTaskCount: 0,
                    longTaskTotalMs: 0,
                    longTaskMaximumMs: 0,
                    interactionCount: 0,
                    slowInteractionCount: 0,
                    interactionMaximumMs: 0
                };
                var sentSignature = '';
                function observe(type, callback, options) {
                    try {
                        if (window.PerformanceObserver &&
                            PerformanceObserver.supportedEntryTypes &&
                            PerformanceObserver.supportedEntryTypes.indexOf(type) >= 0) {
                            var observer = new PerformanceObserver(function (list) {
                                callback(list.getEntries());
                            });
                            observer.observe(options || { type: type, buffered: true });
                        }
                    } catch (_) { }
                }
                try {
                    var nav = performance.getEntriesByType('navigation')[0];
                    if (nav) {
                        latest.timeToFirstByteMs = Math.max(0, nav.responseStart || 0);
                        latest.domInteractiveMs = Math.max(0, nav.domInteractive || 0);
                        latest.domContentLoadedMs = Math.max(0, nav.domContentLoadedEventEnd || 0);
                        latest.loadEventMs = Math.max(0, nav.loadEventEnd || 0);
                    }
                    var paints = performance.getEntriesByType('paint') || [];
                    paints.forEach(function (entry) {
                        if (entry.name === 'first-paint') latest.firstPaintMs = Math.max(0, entry.startTime || 0);
                        if (entry.name === 'first-contentful-paint') {
                            latest.firstContentfulPaintMs = Math.max(0, entry.startTime || 0);
                        }
                    });
                    var resources = performance.getEntriesByType('resource') || [];
                    latest.resourceCount = resources.length;
                    resources.forEach(function (entry) {
                        latest.resourceDurationMs += Math.max(0, entry.duration || 0);
                        latest.resourceTransferBytes += Math.max(0, entry.transferSize || 0);
                    });
                } catch (_) { }
                observe('largest-contentful-paint', function (entries) {
                    entries.forEach(function (entry) {
                        latest.largestContentfulPaintMs =
                            Math.max(latest.largestContentfulPaintMs, entry.startTime || 0);
                    });
                }, { type: 'largest-contentful-paint', buffered: true });
                observe('layout-shift', function (entries) {
                    entries.forEach(function (entry) {
                        if (!entry.hadRecentInput) {
                            latest.cumulativeLayoutShift += Math.max(0, entry.value || 0);
                        }
                    });
                }, { type: 'layout-shift', buffered: true });
                observe('longtask', function (entries) {
                    entries.forEach(function (entry) {
                        var duration = Math.max(0, entry.duration || 0);
                        latest.longTaskCount++;
                        latest.longTaskTotalMs += duration;
                        latest.longTaskMaximumMs = Math.max(latest.longTaskMaximumMs, duration);
                    });
                }, { type: 'longtask', buffered: true });
                observe('event', function (entries) {
                    entries.forEach(function (entry) {
                        if (entry.interactionId && entry.duration > 0) {
                            latest.interactionCount++;
                            latest.interactionMaximumMs =
                                Math.max(latest.interactionMaximumMs, entry.duration);
                            if (entry.duration >= 50) latest.slowInteractionCount++;
                        }
                    });
                }, { type: 'event', buffered: true, durationThreshold: 16 });
                function send() {
                    try {
                        if (!window.AndroidInterface ||
                            typeof AndroidInterface.recordWebPerformanceMetrics !== 'function') return;
                        var signature = JSON.stringify(latest);
                        if (signature === sentSignature) return;
                        sentSignature = signature;
                        AndroidInterface.recordWebPerformanceMetrics(signature);
                    } catch (_) { }
                }
                setTimeout(send, 1800);
                window.addEventListener('pagehide', send, { once: true });
                var timer = window.setInterval(function () {
                    send();
                    if (document.visibilityState === 'hidden') {
                        window.clearInterval(timer);
                    }
                }, 15000);
            })();
        """.trimIndent()
        target?.evaluateJavascript(script, null)
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                DebugLogger.info("WebView", "PAGE_STARTED: $url")
                if (!url.isNullOrBlank() && url.startsWith("file:///android_asset/")) {
                    lastKnownAssetUrl = url
                    val assetName = url.substringAfterLast('/').substringBefore('?').substringBefore('#').take(64)
                    StartupPerformanceMonitor.endSpan(
                        currentPageLoadPerformanceSpan,
                        success = false,
                        attributes = mapOf("outcome" to "superseded")
                    )
                    currentPageLoadAsset = assetName
                    currentPageLoadPerformanceSpan =
                        StartupPerformanceMonitor.beginSpan("webview.page_load.$assetName")
                    StartupPerformanceMonitor.mark("webview.page_started", mapOf("asset" to assetName))
                }
                if (url?.substringBefore('?')?.substringBefore('#')?.endsWith("/login.html") == true &&
                    shouldOpenSystemSetupScreen() && startupExpectedAsset != "first-user-setup.html"
                ) {
                    view?.post {
                        if (!isDestroyed.get()) {
                            view.loadUrl("file:///android_asset/screens/first-user-setup.html")
                        }
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isDestroyed.get()) return
                serverReady = true
                isErrorPageShown = false
                DebugLogger.info("WebView", "PAGE_FINISHED: $url")
                if (startupDatabaseReady) {
                    updateStartupRouteAfterPageFinished(url)
                    applySecurityRuntimeSettings(view)
                    scheduleStartupPermissionsRequest()
                }
                normalizeOfflineFirstUi(view)
                installWebPerformanceObserver(view)
                if (!url.isNullOrBlank() && url.startsWith("file:///android_asset/")) {
                    val assetName = url.substringAfterLast('/').substringBefore('?').substringBefore('#').take(64)
                    if (currentPageLoadAsset == assetName) {
                        StartupPerformanceMonitor.endSpan(
                            currentPageLoadPerformanceSpan,
                            attributes = mapOf("asset" to assetName)
                        )
                        currentPageLoadPerformanceSpan = null
                        currentPageLoadAsset = null
                    }
                    StartupPerformanceMonitor.mark("webview.page_finished", mapOf("asset" to assetName))
                    if (!firstAssetPageFinished) {
                        firstAssetPageFinished = true
                        StartupPerformanceMonitor.mark("startup.first_asset_page_finished", mapOf("asset" to assetName))
                    }
                }
                maybeReportFullyDrawn()
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
                    val vehicleId = pendingVehicleTrackingId
                    if (vehicleId != null) {
                        pendingVehicleTrackingId = null
                        view?.postDelayed({
                            if (!isDestroyed.get()) {
                                view.loadUrl("file:///android_asset/screens/vehicle-tracking.html" + if (vehicleId > 0L) "?vehicle_id=$vehicleId" else "")
                            }
                        }, 450)
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
                if (!isTrustedAssetUrl(url)) {
                    DebugLogger.warn("WebView", "BLOCKED_UNTRUSTED_URL: $url")
                    return true
                }
                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return true
                if (handleCustomUrl(url)) {
                    DebugLogger.info("WebView", "URL_OVERRIDDEN: $url")
                    return true
                }
                if (!isTrustedAssetUrl(url)) {
                    DebugLogger.warn("WebView", "BLOCKED_UNTRUSTED_URL: $url")
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
                    serverReady = false
                    // A plain property becoming null does not rerun AndroidView.factory.
                    // Changing this key disposes the dead subtree and creates a fresh WebView/bridge.
                    webViewRecoveryTrigger.intValue += 1
                    handler.postDelayed({
                        if (!isDestroyed.get()) {
                            isErrorPageShown = false
                            recreateWebView()
                        }
                    }, 900)
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

        val targetUrl = lastKnownAssetUrl
            ?.takeIf { it.startsWith("file:///android_asset/") }
            ?: entryAssetUrl(initialEntryAsset())
        val wv = webView
        if (wv != null && wv.isAttachedToWindow) {
            try {
                wv.loadUrl(targetUrl)
                DebugLogger.info("WebView", "RELOADED after renderer loss: $targetUrl")
            } catch (e: Exception) {
                DebugLogger.logException("WebViewRecovery", e)
                showErrorPage()
            }
        } else {
            webViewRecoveryTrigger.intValue += 1
            handler.postDelayed({
                if (isDestroyed.get()) return@postDelayed
                val recovered = webView
                if (recovered != null && recovered.isAttachedToWindow) {
                    recovered.loadUrl(targetUrl)
                } else {
                    DebugLogger.warn("WebViewRecovery", "New WebView was not attached after renderer loss")
                    showErrorPage()
                }
            }, 900)
        }
    }

    private fun isTrustedAssetUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val path = uri.path.orEmpty()
            uri.scheme.equals("file", ignoreCase = true) &&
                path.startsWith("/android_asset/") &&
                !path.contains("..")
        } catch (_: Exception) {
            false
        }
    }

    private fun handleCustomUrl(url: String): Boolean {
        return when {
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
        if (securityPromptActive && biometricPrompt != null) return
        securityPromptActive = true
        biometricCancellationExpected = false
        try {
            Class.forName("androidx.biometric.BiometricPrompt")
        } catch (e: ClassNotFoundException) {
            DebugLogger.warn("Biometric", "BiometricPrompt not available")
            securityPromptActive = false
            onError("unsupported")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            DebugLogger.warn("Biometric", "Android version < P")
            securityPromptActive = false
            onError("unsupported")
            return
        }

        try {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = androidx.biometric.BiometricPrompt(
                this,
                executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: androidx.biometric.BiometricPrompt.AuthenticationResult
                    ) {
                        super.onAuthenticationSucceeded(result)
                        DebugLogger.info("Biometric", "Authentication SUCCEEDED")
                        biometricPrompt = null
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
                        biometricPrompt = null
                        if (biometricCancellationExpected) {
                            biometricCancellationExpected = false
                            securityPromptActive = false
                            return
                        }
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
            biometricPrompt = prompt
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            DebugLogger.logException("Biometric", e)
            biometricPrompt = null
            securityPromptActive = false
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

        init {
            // The persisted setting is restored after SQLite is prepared on Dispatchers.IO.
            DebugLogger.setDeveloperModeEnabled(this@MainActivity.isDebugMode)
        }

        /**
         * Loads only the nine allowlisted settings panel templates bundled in this APK.
         * The whitelist prevents JavaScript from using this bridge as an arbitrary asset reader.
         * File-to-file access remains disabled in WebView.
         */
        @JavascriptInterface
        fun loadSettingsPanel(panelName: String): String {
            val assetName = when (panelName) {
                "general" -> "general.html"
                "startup" -> "operations.html"
                "sms" -> "sms.html"
                "monitoring" -> "monitoring.html"
                "security" -> "security.html"
                "backup" -> "backup.html"
                "maintenance" -> "maintenance.html"
                "users" -> "users.html"
                "display" -> "display.html"
                else -> return ""
            }

            return try {
                val context = contextRef.get() ?: return ""
                context.assets.open("screens/settings-panels/$assetName").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                DebugLogger.logException("SettingsPanelLoader", e)
                ""
            }
        }
        private val db: DatabaseHelper
            get() = getDbHelper() ?: throw IllegalStateException("قاعدة البيانات غير متاحة")
        private val geminiHelperRef = WeakReference(geminiHelper)
        private val settingsJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        private val systemSetupLock = Any()

        private fun getDbHelper(): DatabaseHelper? =
            if (this@MainActivity::dbHelper.isInitialized) this@MainActivity.dbHelper else null
        private fun getGeminiHelper(): GeminiAIHelper? = geminiHelperRef.get()
        private fun getActivity(): MainActivity? = activityRef.get()

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
        fun executeCustodyOperation(action: String, jsonData: String): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val database = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stationId = requireCurrentStationId(database, activity.currentUserId)
                CustodyWorkshopManager.executeCustody(
                    database, stationId, activity.currentUserId, action, JSONObject(jsonData)
                ).toString()
            } catch (e: Exception) {
                DebugLogger.logException("CustodyOperation-" + action, e)
                errorResponse(e.message)
            }
        }

        @JavascriptInterface
        fun executeWorkshopOperation(action: String, jsonData: String): String {
            val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
            val database = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val stationId = requireCurrentStationId(database, activity.currentUserId)
                CustodyWorkshopManager.executeWorkshop(
                    database, stationId, activity.currentUserId, action, JSONObject(jsonData)
                ).toString()
            } catch (e: Exception) {
                DebugLogger.logException("WorkshopOperation-" + action, e)
                errorResponse(e.message)
            }
        }


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


        @JavascriptInterface
        fun getLastSelectedFileUri(): String {
  return getActivity()?.getSharedPreferences("webview_file_picker", Context.MODE_PRIVATE)
      ?.getString("last_uri", "") ?: ""
        }

        @JavascriptInterface
        fun getImageDataUrl(path: String): String {
  val raw = path.trim()
  if (raw.isBlank()) return errorResponse("مسار الصورة فارغ")
  return try {
      val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
      val bytes = when {
          raw.startsWith("content://", ignoreCase = true) -> {
              activity.contentResolver.openInputStream(Uri.parse(raw))?.use { it.readBytes() }
                  ?: throw IllegalStateException("تعذر فتح URI الصورة عبر ContentResolver")
          }
          raw.startsWith("file://", ignoreCase = true) -> java.io.File(Uri.parse(raw).path ?: "").readBytes()
          else -> java.io.File(raw).readBytes()
      }
      require(bytes.isNotEmpty()) { "ملف الصورة فارغ" }
      require(bytes.size <= 5 * 1024 * 1024) { "حجم صورة المستخدم يتجاوز 5MB" }
      val mime = if (raw.startsWith("content://", ignoreCase = true)) {
          activity.contentResolver.getType(Uri.parse(raw)) ?: "image/*"
      } else {
          android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
              raw.substringAfterLast('.', "").lowercase()
          ) ?: "image/*"
      }
      val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
      JSONObject().apply {
          put("success", true)
          put("data", "data:$mime;base64,$encoded")
      }.toString()
  } catch (e: Exception) {
      DebugLogger.logException("UserAvatar", e)
      errorResponse(e.message)
  }
        }

        @JavascriptInterface
        fun getOpenShiftForManagement(): String {
  val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
  val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
  return try {
      val currentUserId = activity.currentUserId
      if (currentUserId <= 0L) return errorResponse("لا توجد جلسة مستخدم صالحة")
      val stationId = requireCurrentStationId(db, currentUserId).toLong()
      val report = db.getOpenShiftForManagement(stationId)
          ?: return errorResponse("لا توجد وردية مفتوحة حالياً لهذه المحطة")
      dataResponse(report)
  } catch (e: Exception) {
      DebugLogger.logException("OpenShift", e)
      errorResponse(e.message)
  }
        }

        @JavascriptInterface
        fun closeOpenShiftForManagement(): String {
  val activity = getActivity() ?: return errorResponse("النشاط غير متاح")
  val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
  return try {
      val currentUserId = activity.currentUserId
      if (currentUserId <= 0L) return errorResponse("لا توجد جلسة مستخدم صالحة")
      val stationId = requireCurrentStationId(db, currentUserId).toLong()
      val id = db.closeOpenShiftForManagement(stationId, currentUserId)
      successResponse(true, "تم إغلاق الوردية $id فعلياً في SQLite")
  } catch (e: Exception) {
      DebugLogger.logException("CloseOpenShift", e)
      errorResponse(e.message)
  }
        }
        // ============================================================
        // Database Browser Bridge — dev-mode، بلا فحص صلاحيات
        // ============================================================

        @JavascriptInterface
        fun getDatabaseTables(): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponse(db.getBrowserTableList())
            } catch (e: Exception) {
                DebugLogger.logException("DBBrowser", e)
                errorResponse(e.message ?: "فشل تحميل قائمة الجداول")
            }
        }

        @JavascriptInterface
        fun getDatabaseTableSchema(tableName: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponse(db.getBrowserTableSchema(tableName))
            } catch (e: Exception) {
                DebugLogger.logException("DBBrowser", e)
                errorResponse(e.message ?: "فشل تحميل مخطط الجدول")
            }
        }

        @JavascriptInterface
        fun getDatabaseTableData(tableName: String, jsonParams: String = "{}"): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val p = JSONObject(jsonParams.ifBlank { "{}" })
                dataResponse(
                    db.getBrowserTableRows(
                        table = tableName,
                        limit = p.optInt("limit", 50),
                        offset = p.optInt("offset", 0),
                        search = p.optString("search", ""),
                        sortBy = p.optString("sort_by", ""),
                        sortDir = p.optString("sort_dir", "desc")
                    )
                )
            } catch (e: Exception) {
                DebugLogger.logException("DBBrowser", e)
                errorResponse(e.message ?: "فشل تحميل بيانات الجدول")
            }
        }

        @JavascriptInterface
        fun getDatabaseRecord(tableName: String, id: Long): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val row = db.getBrowserRow(tableName, id)
                    ?: return errorResponse("السجل غير موجود")
                dataResponse(row)
            } catch (e: Exception) {
                DebugLogger.logException("DBBrowser", e)
                errorResponse(e.message ?: "فشل تحميل السجل")
            }
        }

        @JavascriptInterface
        fun insertBrowserRecord(tableName: String, jsonData: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val obj = JSONObject(jsonData.ifBlank { "{}" })
                val id = db.insertBrowserRow(tableName, obj)
                successResponse(id, "تم الإدراج بنجاح")
            } catch (e: Exception) {
                DebugLogger.logException("DBBrowser", e)
                errorResponse(e.message ?: "فشل الإدراج")
            }
        }

        @JavascriptInterface
        fun updateBrowserRecord(tableName: String, id: Long, jsonData: String): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val obj = JSONObject(jsonData.ifBlank { "{}" })
                val rows = db.updateBrowserRow(tableName, id, obj)
                if (rows > 0) {
                    successResponse(id, "تم التحديث بنجاح")
                } else {
                    successResponse(false, "لم يتم العثور على السجل")
                }
            } catch (e: Exception) {
                DebugLogger.logException("DBBrowser", e)
                errorResponse(e.message ?: "فشل التحديث")
            }
        }

        @JavascriptInterface
        fun deleteBrowserRecord(tableName: String, id: Long): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                val result = db.deleteBrowserRow(tableName, id)
                val rows = result.optInt("rows", 0)
                val mode = result.optString("mode", "hard")
                val message = if (rows > 0)
                    (if (mode == "soft") "تم الحذف المنطقي" else "تم الحذف الفعلي")
                else "لم يتم العثور على السجل"
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rows", rows)
                    put("mode", mode)
                    put("message", message)
                }.toString()
            } catch (e: Exception) {
                DebugLogger.logException("DBBrowser", e)
                errorResponse(e.message ?: "فشل الحذف")
            }
        }

        @JavascriptInterface
        fun getDatabaseRelatedRows(
            relatedTable: String,
            fkColumn: String,
            fkValue: String,
            limit: Int = 100
        ): String {
            val db = getDbHelper() ?: return errorResponse("قاعدة البيانات غير متاحة")
            return try {
                dataResponse(db.getBrowserRelatedRows(relatedTable, fkColumn, fkValue, limit))
            } catch (e: Exception) {
                DebugLogger.logException("DBBrowser", e)
                errorResponse(e.message ?: "فشل تحميل البيانات المرتبطة")
            }
		}
}

    private fun clearSessionState() {
        // Clear only an invalid active session. Remember-Me remains available so a user
        // can recover through biometric auto-login; device reboot has its own explicit reset.
        currentAuthToken?.let { token ->
            try {
                if (::dbHelper.isInitialized) dbHelper.invalidateUserSession(token, "invalid_active_session")
            } catch (e: Exception) {
                DebugLogger.warn("Session", "Failed to invalidate active session: ${e.message}")
            }
        }
        currentAuthToken = null
        currentUserId = 0L
        currentUserRole = ""
        currentUserName = ""
        sharedPrefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_ROLE)
            .remove(KEY_USER_NAME)
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
