package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.aistudio.dieselstationsms.kxmpzq.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SERVICE_START_DELAY_MS = 2000L
        private const val CHANNEL_ID = "station_sms_channel"
        private const val CHANNEL_NAME = "Station SMS Service"

        private const val BIOMETRIC_TITLE = "المصادقة البيومترية"
        private const val BIOMETRIC_SUBTITLE = "استخدم بصمة الإصبع أو الوجه للدخول"
        private const val BIOMETRIC_CANCEL = "إلغاء"
    }

    private var webView: WebView? = null
    private var geminiApiKey: String = ""
    private var serverReady = false
    private val isDestroyed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var isWebViewInitialized = false
    private var isErrorPageShown = false

    private val isDebugMode: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var geminiHelper: GeminiAIHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dbHelper = DatabaseHelper(this)
        geminiHelper = GeminiAIHelper(this)

        geminiApiKey = loadEnvKey("GEMINI_API_KEY")
        if (geminiApiKey.isNotEmpty()) {
            geminiHelper.initialize(geminiApiKey)
        }

        createNotificationChannel()

        if (isDebugMode) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
                Log.d(TAG, "Debug mode enabled")
            } catch (e: Exception) {
                Log.w(TAG, "WebView debugging enable failed: ${e.message}")
            }
        }

        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            Log.e(TAG, "enableEdgeToEdge failed: ${e.message}", e)
        }

        geminiApiKey = loadEnvKey("GEMINI_API_KEY")
        if (geminiApiKey.isEmpty()) {
            Log.w(TAG, "GEMINI_API_KEY not found in .env")
        } else {
            Log.d(TAG, "Gemini API key loaded successfully")
            geminiHelper.initialize(geminiApiKey)
        }

        requestAllPermissions()

        lifecycleScope.launch {
            delay(SERVICE_START_DELAY_MS)
            if (!isDestroyed.get()) {
                startSMSService()
            }
        }

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
        }, 1500)
    }

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

    private fun loadWebViewFromAssets() {
        if (isDestroyed.get()) return
        val wv = webView ?: run {
            Log.w(TAG, "WebView is null, cannot load from assets")
            return
        }

        try {
            if (wv.isAttachedToWindow) {
                Log.d(TAG, "Loading web_interface.html from assets")
                wv.loadUrl("file:///android_asset/web_interface.html")
            } else {
                Log.w(TAG, "WebView not attached, retrying...")
                handler.postDelayed({
                    if (!isDestroyed.get()) {
                        loadWebViewFromAssets()
                    }
                }, 500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from assets: ${e.message}", e)
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
            Log.d(TAG, "Error page loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load error page: ${e.message}")
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
            Log.w(TAG, "Could not load .env key $key: ${e.message}")
            ""
        }
    }

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
            Log.d(TAG, "All required permissions already granted")
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startSMSService() {
        if (isDestroyed.get()) {
            Log.w(TAG, "Activity is destroyed, not starting service")
            return
        }
        try {
            val intent = Intent(this, SMSService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "SMSService started successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting SMSService", e)
            Toast.makeText(this, "فشل في بدء خدمة SMS: أذونات مفقودة", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SMSService", e)
            Toast.makeText(this, "فشل في بدء خدمة SMS", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun WebViewScreen(modifier: Modifier = Modifier) {
        val webViewRef = remember { mutableSetOf<WebView>() }

        DisposableEffect(Unit) {
            onDispose {
                Log.d(TAG, "Disposing WebView references")
                webViewRef.forEach { destroyWebView(it) }
                webViewRef.clear()
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

                    val wv = WebView(context).apply {
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
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            loadsImagesAutomatically = true
                            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                        }

                        webViewClient = createWebViewClient()
                        webChromeClient = WebChromeClient()

                        try {
                            addJavascriptInterface(
                                WebAppInterface(context, this@MainActivity),
                                "AndroidInterface"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add JS interface: ${e.message}")
                        }
                    }

                    addView(wv)
                    webViewRef.add(wv)
                    this@MainActivity.webView = wv
                    this@MainActivity.isWebViewInitialized = true

                    Log.d(TAG, "WebView created and added to FrameLayout")
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
                Log.d(TAG, "WebView page finished loading: $url")
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
                Log.w(TAG, "WebView error: ${error?.description}")
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
                Log.w(TAG, "WebView error $errorCode: $description on $failingUrl")
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                Log.e(TAG, "SSL Error: ${error?.toString()}")
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                Log.e(TAG, "WebView RenderProcess gone. didCrash: ${detail?.didCrash()}")
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
        Log.d(TAG, "Recreating WebView...")
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
                isErrorPageShown = false
                loadWebViewFromAssets()
            }
        }, 2000)
    }

    private fun handleCustomUrl(url: String): Boolean {
        return when {
            url.startsWith("whatsapp://") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "WhatsApp not installed", e)
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
                    Log.w(TAG, "Facebook not installed", e)
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
                    Log.w(TAG, "No email app found", e)
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
                    Log.w(TAG, "No dialer found", e)
                    false
                }
            }
            url.startsWith("http") && !url.contains("127.0.0.1") && !url.contains("localhost") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "No browser found", e)
                    false
                }
            }
            else -> false
        }
    }

    fun showBiometricPrompt(onSuccess: () -> Unit, onError: (String) -> Unit) {
        try {
            Class.forName("androidx.biometric.BiometricPrompt")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Biometric library not available")
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
            Log.e(TAG, "Biometric error", e)
            onError("unsupported")
        }
    }

    // ================================================================
    // WEB APP INTERFACE - COMPLETE CRUD BRIDGE
    // ================================================================
    inner class WebAppInterface(
        private val context: Context,
        private val activity: MainActivity
    ) {

        // ========== AUTHENTICATION ==========
        @JavascriptInterface
        fun login(username: String, password: String): String {
            Log.d(TAG, "login() called with username: $username")
            return try {
                val authResult = dbHelper.authenticateUser(username, password)
                if (authResult != null) {
                    val token = java.util.UUID.randomUUID().toString()
                    val response = JSONObject().apply {
                        put("success", true)
                        put("user", authResult)
                        put("token", token)
                    }
                    dbHelper.logActivity(username, "login", "تسجيل دخول ناجح عبر WebInterface")
                    response.toString()
                } else {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "بيانات خاطئة")
                    }.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", "خطأ داخلي: ${e.message}")
                }.toString()
            }
        }

        @JavascriptInterface
        fun requestBiometricAuth(): String {
            activity.runOnUiThread {
                activity.showBiometricPrompt(
                    onSuccess = {
                        val result = JSONObject().apply {
                            put("success", true)
                            put("message", "authenticated")
                        }
                        safeEvaluateJs("window.onBiometricResult && window.onBiometricResult(${result})")
                    },
                    onError = { error ->
                        val result = JSONObject().apply {
                            put("success", false)
                            put("error", error)
                        }
                        safeEvaluateJs("window.onBiometricResult && window.onBiometricResult(${result})")
                    }
                )
            }
            return "requested"
        }

        // ========== GEMINI AI ==========
        @JavascriptInterface
        fun getGeminiApiKey(): String {
            return if (geminiApiKey.isNotEmpty()) "configured" else "not_configured"
        }

        @JavascriptInterface
        fun sendToAI(message: String): String {
            return try {
                if (geminiApiKey.isEmpty()) {
                    return JSONObject().apply {
                        put("success", false)
                        put("error", "مفتاح Gemini API غير مُهيأ")
                    }.toString()
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val response = geminiHelper.sendMessage(message)
                        withContext(Dispatchers.Main) {
                            val result = JSONObject().apply {
                                put("success", true)
                                put("response", response)
                            }
                            safeEvaluateJs("window.onAIResponse && window.onAIResponse(${result})")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            val result = JSONObject().apply {
                                put("success", false)
                                put("error", e.message)
                            }
                            safeEvaluateJs("window.onAIResponse && window.onAIResponse(${result})")
                        }
                    }
                }

                JSONObject().apply {
                    put("success", true)
                    put("status", "processing")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "AI send error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getAIResponse(message: String): String {
            return try {
                if (geminiApiKey.isEmpty()) {
                    return JSONObject().apply {
                        put("success", false)
                        put("error", "مفتاح Gemini API غير مُهيأ")
                    }.toString()
                }
                val response = geminiHelper.sendMessageSync(message)
                JSONObject().apply {
                    put("success", true)
                    put("response", response)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "AI response error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== PARTIES ==========
        @JavascriptInterface
        fun addParty(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.insertParty(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تمت الإضافة بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addParty error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun updateParty(id: Long, jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val rows = dbHelper.updateParty(id, data)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "updateParty error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun deleteParty(id: Long): String {
            return try {
                val rows = dbHelper.deleteParty(id)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteParty error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun archiveParty(id: Long): String {
            return try {
                val rows = dbHelper.archiveParty(id)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم الأرشفة بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "archiveParty error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getParties(type: String?): String {
            return try {
                val parties = dbHelper.getParties(type ?: "")
                parties.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getParties error", e)
                "[]"
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
            return try {
                val results = dbHelper.searchParties(query)
                results.toString()
            } catch (e: Exception) {
                Log.e(TAG, "searchParties error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getPartyById(id: Long): String {
            return try {
                val party = dbHelper.getPartyById(id)
                party?.toString() ?: JSONObject().apply {
                    put("success", false)
                    put("error", "العميل غير موجود")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getPartyById error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== ORDERS ==========
        @JavascriptInterface
        fun addOrder(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addOrder(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة الطلب بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addOrder error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getOrders(status: String?): String {
            return try {
                val orders = dbHelper.getOrders(status)
                orders.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getOrders error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getPendingOrders(): String = getOrders("pending")

        // ========== DELIVERIES ==========
        @JavascriptInterface
        fun addDelivery(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addDelivery(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة التسليم بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addDelivery error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getDeliveries(): String {
            return try {
                val deliveries = dbHelper.getDeliveries()
                deliveries.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getDeliveries error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getTodayDeliveries(): String {
            return try {
                val deliveries = dbHelper.getTodayDeliveries()
                deliveries.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getTodayDeliveries error", e)
                "[]"
            }
        }

        // ========== SALES ==========
        @JavascriptInterface
        fun addSale(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addFuelSale(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة البيع بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addSale error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getSales(): String {
            return try {
                val sales = dbHelper.getSales()
                sales.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getSales error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getTodaySales(): String {
            return try {
                val sales = dbHelper.getTodaySales()
                sales.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getTodaySales error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun deleteSale(saleId: Long): String {
            return try {
                val dbWritable = dbHelper.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("sales_transactions", cv, "id=?", arrayOf(saleId.toString()))
                JSONObject().apply {
                    put("success", rows > 0)
                    if (rows > 0) dbHelper.logActivity("system", "delete_sale", "حذف مبيعة $saleId")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteSale error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== CASH MOVEMENTS ==========
        @JavascriptInterface
        fun addCashMovement(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addCashMovement(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة الحركة المالية بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addCashMovement error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getCashMovements(): String {
            return try {
                val movements = dbHelper.getCashMovements()
                movements.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getCashMovements error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getTodayCash(): String {
            return try {
                val cash = dbHelper.getTodayCash()
                cash.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getTodayCash error", e)
                "[]"
            }
        }

        // ========== METER READINGS ==========
        @JavascriptInterface
        fun addMeterReading(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addMeterReading(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة قراءة العداد بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addMeterReading error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getMeterReadings(): String {
            return try {
                val readings = dbHelper.getMeterReadings()
                readings.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getMeterReadings error", e)
                "[]"
            }
        }

        // ========== TANK READINGS ==========
        @JavascriptInterface
        fun addTankReading(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addTankReading(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة قراءة الخزان بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addTankReading error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getTankReadings(): String {
            return try {
                val readings = dbHelper.getTankReadings()
                readings.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getTankReadings error", e)
                "[]"
            }
        }

        // ========== STOCK MOVEMENTS ==========
        @JavascriptInterface
        fun addStockMovement(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addStockMovement(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة حركة المخزون بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addStockMovement error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getStockMovements(): String {
            return try {
                val movements = dbHelper.getStockMovements()
                movements.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getStockMovements error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getLowStockItems(): String {
            return try {
                val items = dbHelper.getLowStockItems()
                items.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getLowStockItems error", e)
                "[]"
            }
        }

        // ========== ASSETS ==========
        @JavascriptInterface
        fun addAsset(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addAsset(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة الأصل بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addAsset error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getAssets(): String {
            return try {
                val assets = dbHelper.getAssets()
                assets.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getAssets error", e)
                "[]"
            }
        }

        // ========== USERS ==========
        @JavascriptInterface
        fun addUser(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addUser(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة المستخدم بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addUser error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getUsers(): String {
            return try {
                val users = dbHelper.getUsers()
                users.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getUsers error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getUsersByRole(role: String): String {
            return try {
                val users = dbHelper.getUsersByRole(role)
                users.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getUsersByRole error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun updateUser(id: Long, jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val rows = dbHelper.updateUser(id, data)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "updateUser error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun deleteUser(id: Long): String {
            return try {
                val rows = dbHelper.deleteUser(id)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteUser error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== EMPLOYEES ==========
        @JavascriptInterface
        fun addEmployee(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addEmployee(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة الموظف بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addEmployee error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getEmployees(): String {
            return try {
                val employees = dbHelper.getEmployees()
                employees.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getEmployees error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun updateEmployee(id: Long, jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val rows = dbHelper.updateEmployee(id, data)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "updateEmployee error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun deleteEmployee(id: Long): String {
            return try {
                val rows = dbHelper.deleteEmployee(id.toInt())
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteEmployee error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== SHIFTS ==========
        @JavascriptInterface
        fun startShift(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.startShift(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم بدء الوردية بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "startShift error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun endShift(id: Long, jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val rows = dbHelper.endShift(id, data)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم إنهاء الوردية بنجاح" else "لم يتم العثور على الوردية")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "endShift error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getCurrentShift(): String {
            return try {
                val shift = dbHelper.getCurrentShift()
                shift?.toString() ?: JSONObject().apply {
                    put("success", false)
                    put("error", "لا توجد وردية نشطة")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getCurrentShift error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getShifts(): String {
            return try {
                val shifts = dbHelper.getShifts(1)
                shifts.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getShifts error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun deleteShift(shiftId: Long): String {
            return try {
                val dbWritable = dbHelper.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("shifts", cv, "id=?", arrayOf(shiftId.toString()))
                JSONObject().apply {
                    put("success", rows > 0)
                    if (rows > 0) dbHelper.logActivity("system", "delete_shift", "حذف وردية $shiftId")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteShift error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun addShiftSale(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addShiftSale(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة بيع الوردية بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addShiftSale error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun addShiftDelivery(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addShiftDelivery(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة تسليم الوردية بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addShiftDelivery error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun addShiftExpense(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addShiftExpense(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة مصروف الوردية بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addShiftExpense error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getShiftReport(shiftId: Long): String {
            return try {
                val report = dbHelper.getShiftReport(shiftId)
                report.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getShiftReport error", e)
                "[]"
            }
        }

        // ========== NOTIFICATIONS ==========
        @JavascriptInterface
        fun addNotification(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addNotification(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة الإشعار بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addNotification error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getNotifications(): String {
            return try {
                val notifications = dbHelper.getNotifications()
                notifications.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getNotifications error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getUnreadNotificationsCount(): Int {
            return try {
                dbHelper.getUnreadNotificationsCount()
            } catch (e: Exception) {
                Log.e(TAG, "getUnreadNotificationsCount error", e)
                0
            }
        }

        @JavascriptInterface
        fun markNotificationRead(id: Long): String {
            return try {
                val rows = dbHelper.markNotificationRead(id)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "markNotificationRead error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== SMS MESSAGES ==========
        @JavascriptInterface
        fun addSmsMessage(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addSmsMessage(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة الرسالة بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addSmsMessage error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getSmsMessages(): String {
            return try {
                val messages = dbHelper.getSmsMessages()
                messages.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getSmsMessages error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getSmsMessagesByPhone(phone: String): String {
            return try {
                val messages = dbHelper.getSmsMessagesByPhone(phone)
                messages.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getSmsMessagesByPhone error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getSmsMessagesByStatus(status: String): String {
            return try {
                val messages = dbHelper.getSmsMessagesByStatus(status)
                messages.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getSmsMessagesByStatus error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun updateSmsStatus(id: Long, status: String): String {
            return try {
                val rows = dbHelper.updateSmsStatus(id, status)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "updateSmsStatus error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getSmsStats(): String {
            return try {
                val stats = dbHelper.getSmsStats()
                stats.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getSmsStats error", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun getSmsTemplates(): String {
            return try {
                val templates = dbHelper.getSmsTemplates()
                templates.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getSmsTemplates error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun addSmsTemplate(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addSmsTemplate(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة القالب بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addSmsTemplate error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun updateSmsTemplate(id: Long, jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val rows = dbHelper.updateSmsTemplate(id, data)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "updateSmsTemplate error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun deleteSmsTemplate(id: Long): String {
            return try {
                val rows = dbHelper.deleteSmsTemplate(id)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteSmsTemplate error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== SMS WHITELIST ==========
        @JavascriptInterface
        fun getWhitelist(): String {
            return try {
                val whitelist = dbHelper.getSmsWhitelist()
                whitelist.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getWhitelist error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun addWhitelist(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val phone = data.optString("phone", "")
                val name = data.optString("name", "")
                if (phone.isBlank()) {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "رقم الهاتف مطلوب")
                    }.toString()
                } else {
                    dbHelper.addToSmsWhitelist(phone, name)
                    JSONObject().apply { put("success", true) }.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "addWhitelist error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun removeWhitelist(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val phone = data.optString("phone", "")
                if (phone.isBlank()) {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "رقم الهاتف مطلوب")
                    }.toString()
                } else {
                    dbHelper.removeFromSmsWhitelist(phone)
                    JSONObject().apply { put("success", true) }.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "removeWhitelist error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== SETTINGS ==========
        @JavascriptInterface
        fun addSetting(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.addSetting(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة الإعداد بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addSetting error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun deleteSetting(key: String): String {
            return try {
                val rows = dbHelper.deleteSetting(key)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteSetting error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getSetting(key: String): String {
            return try {
                val value = dbHelper.getSetting(key)
                JSONObject().apply {
                    put("success", true)
                    put("value", value)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getSetting error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun setSetting(key: String, value: String): String {
            return try {
                dbHelper.setSetting(key, value)
                JSONObject().apply { put("success", true) }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "setSetting error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getAllSettingsMap(): String {
            return try {
                val settings = dbHelper.getAllSettingsMap()
                settings.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getAllSettingsMap error", e)
                "{}"
            }
        }

        // ========== DASHBOARD & REPORTS ==========
        @JavascriptInterface
        fun getDashboardStats(): String {
            return try {
                val stats = dbHelper.getDashboardStats(1)
                stats.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getDashboardStats error", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun getOverduePayments(): String {
            return try {
                val payments = dbHelper.getOverduePayments()
                payments.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getOverduePayments error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getActiveAlerts(): String {
            return try {
                val alerts = dbHelper.getActiveAlerts()
                alerts.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getActiveAlerts error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getRecentActivity(limit: Int): String {
            return try {
                val activity = dbHelper.getRecentActivity(limit)
                activity.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getRecentActivity error", e)
                "[]"
            }
        }

        // ========== PRODUCTS & FUEL ==========
        @JavascriptInterface
        fun getProducts(): String {
            return try {
                val products = dbHelper.getProducts()
                products.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getProducts error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun addProduct(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val id = dbHelper.insertProduct(data)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إضافة المنتج بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "addProduct error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun updateProduct(id: Long, jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val rows = dbHelper.updateProduct(id, data)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "updateProduct error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun deleteProduct(id: Long): String {
            return try {
                val rows = dbHelper.deleteProduct(id)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم الحذف بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteProduct error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getFuelTypes(): String {
            return try {
                val types = dbHelper.getFuelTypes()
                types.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getFuelTypes error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getCategories(): String {
            return try {
                val categories = dbHelper.getProductCategories()
                categories.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getCategories error", e)
                "[]"
            }
        }

        // ========== VEHICLES ==========
        @JavascriptInterface
        fun getVehicles(): String {
            return try {
                val vehicles = dbHelper.getVehicles()
                vehicles.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getVehicles error", e)
                "[]"
            }
        }

        // ========== TANKS & PUMPS ==========
        @JavascriptInterface
        fun getTanks(): String {
            return try {
                val tanks = dbHelper.getTanks()
                tanks.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getTanks error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getPumps(): String {
            return try {
                val pumps = dbHelper.getPumps()
                pumps.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getPumps error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getTankStats(): String {
            return try {
                val stats = dbHelper.getTankStats()
                stats.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getTankStats error", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun updateTankQuantity(tankId: Int, quantity: Double): String {
            return try {
                dbHelper.updateTankQuantity(tankId, quantity, "System")
                JSONObject().apply { put("success", true) }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "updateTankQuantity error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== MAINTENANCE REQUESTS ==========
        @JavascriptInterface
        fun getMaintenanceRequests(): String {
            return try {
                val requests = dbHelper.getMaintenanceRequests(1)
                requests.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getMaintenanceRequests error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun addMaintenanceRequest(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val assetType = data.optString("asset_type", "tank")
                val assetId = data.optInt("asset_id", 0)
                val requestType = data.optString("request_type", "")
                val priority = data.optString("priority", "medium")
                val title = data.optString("title", "")
                val description = data.optString("description", "")
                if (assetId <= 0 || requestType.isBlank() || title.isBlank() || description.isBlank()) {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "بيانات غير صالحة")
                    }.toString()
                } else {
                    val id = dbHelper.addMaintenanceRequest(assetType, assetId, requestType, priority, title, description, 1, 1)
                    JSONObject().apply {
                        put("success", true)
                        put("id", id)
                        put("message", "تم إضافة طلب الصيانة بنجاح")
                    }.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "addMaintenanceRequest error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun updateMaintenanceStatus(requestId: Long, status: String): String {
            return try {
                val rows = dbHelper.updateMaintenanceRequestStatus(requestId, status)
                JSONObject().apply {
                    put("success", rows > 0)
                    put("rowsAffected", rows)
                    put("message", if (rows > 0) "تم التحديث بنجاح" else "لم يتم العثور على السجل")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "updateMaintenanceStatus error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun deleteMaintenance(requestId: Long): String {
            return try {
                val dbWritable = dbHelper.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("maintenance_requests", cv, "id=?", arrayOf(requestId.toString()))
                JSONObject().apply {
                    put("success", rows > 0)
                    if (rows > 0) dbHelper.logActivity("system", "delete_maintenance", "حذف طلب صيانة $requestId")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteMaintenance error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== PAYMENTS ==========
        @JavascriptInterface
        fun getPayments(): String {
            return try {
                val payments = dbHelper.getPaymentsWithCustomer()
                payments.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getPayments error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun makePayment(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val customerId = data.optInt("customer_party_id", 0)
                val amount = data.optDouble("amount", 0.0)
                val method = data.optString("payment_method", "cash")
                val operator = data.optString("operator", "System")
                if (customerId <= 0 || amount <= 0) {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "بيانات غير صالحة")
                    }.toString()
                } else {
                    val success = dbHelper.processPayment(customerId, amount, method, operator)
                    JSONObject().apply {
                        put("success", success)
                        put("message", if (success) "تم التسديد بنجاح" else "فشل التسديد")
                    }.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "makePayment error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun addDeposit(jsonData: String): String {
            return try {
                val data = JSONObject(jsonData)
                val customerId = data.optInt("customer_party_id", 0)
                val amount = data.optDouble("amount", 0.0)
                val notes = data.optString("notes", "")
                val operator = data.optString("operator", "System")
                if (customerId <= 0 || amount <= 0) {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "بيانات غير صالحة")
                    }.toString()
                } else {
                    val success = dbHelper.addCashDeposit(customerId, amount, notes, operator)
                    JSONObject().apply {
                        put("success", success)
                        put("message", if (success) "تم الإيداع بنجاح" else "فشل الإيداع")
                    }.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "addDeposit error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun deletePayment(paymentId: Long): String {
            return try {
                val dbWritable = dbHelper.writableDatabase
                val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                val rows = dbWritable.update("payments", cv, "id=?", arrayOf(paymentId.toString()))
                JSONObject().apply {
                    put("success", rows > 0)
                    if (rows > 0) dbHelper.logActivity("system", "delete_payment", "حذف دفعة $paymentId")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deletePayment error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== CASHIERS ==========
        @JavascriptInterface
        fun getCashiers(): String {
            return try {
                val cashiers = dbHelper.getUsersByRole("CASHIER")
                cashiers.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getCashiers error", e)
                "[]"
            }
        }

        // ========== REPORTS (Extra) ==========
        @JavascriptInterface
        fun getMonthlySales(): String {
            return try {
                val sales = dbHelper.getMonthlySales(1)
                sales.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getMonthlySales error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getDailySales(date: String?): String {
            return try {
                val sales = dbHelper.getDailySales(1, date)
                sales.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getDailySales error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getEodReport(): String {
            return try {
                val report = dbHelper.getEodReport(1)
                report.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getEodReport error", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun getProfitReport(fromDate: String?, toDate: String?): String {
            return try {
                val report = dbHelper.getEodReport(1, fromDate, toDate)
                val profit = report.optDouble("total_sales", 0.0) - report.optDouble("total_payments", 0.0)
                report.put("profit", profit)
                report.put("revenue", report.optDouble("total_sales", 0.0))
                report.put("cost", report.optDouble("total_payments", 0.0))
                report.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getProfitReport error", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun getInventoryReport(): String {
            return try {
                val products = dbHelper.getProducts(1)
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
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getInventoryReport error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getOverdueReport(): String {
            return try {
                val overdue = dbHelper.getOverduePayments()
                overdue.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getOverdueReport error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getFuelSales(): String {
            return try {
                val sales = dbHelper.getSalesByFuelType()
                sales.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getFuelSales error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getAIInsight(): String {
            return try {
                val stats = dbHelper.getDashboardStats(1)
                val prompt = """
                    أنت مساعد ذكي لمحطة وقود. قدم تحليلاً مختصراً للبيانات التالية:
                    - المخزون المتبقي: ${stats.optDouble("total_remaining", 0.0).toInt()} لتر
                    - الديون المستحقة: ${stats.optDouble("total_due", 0.0).toInt()} ريال
                    - مبيعات اليوم: ${stats.optDouble("total_sales", 0.0).toInt()} ريال
                    - عدد العملاء: ${stats.optInt("total_customers", 0)}
                    قدم توصية واحدة عملية مختصرة (سطرين فقط).
                """.trimIndent()
                val insight = geminiHelper.sendMessageSync(prompt)
                JSONObject().apply {
                    put("success", true)
                    put("insight", insight)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getAIInsight error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== BACKUP & EXPORT ==========
        @JavascriptInterface
        fun backupDatabase(): String {
            return try {
                val path = dbHelper.backupDatabase()
                JSONObject().apply {
                    put("success", true)
                    put("path", path)
                    put("message", "تم إنشاء النسخة الاحتياطية بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "backupDatabase error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun restoreDatabase(path: String): String {
            return try {
                val success = dbHelper.restoreDatabase(path)
                JSONObject().apply {
                    put("success", success)
                    put("message", if (success) "تم الاستعادة بنجاح" else "فشل الاستعادة")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "restoreDatabase error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun exportToCSV(tableName: String): String {
            return try {
                val path = dbHelper.exportToCSV(tableName)
                JSONObject().apply {
                    put("success", true)
                    put("path", path)
                    put("message", "تم التصدير بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "exportToCSV error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun importFromCSV(tableName: String, path: String): String {
            return try {
                val count = dbHelper.importFromCSV(tableName, path)
                JSONObject().apply {
                    put("success", true)
                    put("count", count)
                    put("message", "تم استيراد $count سجل بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "importFromCSV error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getDatabaseSize(): Long {
            return try {
                dbHelper.getDatabaseSize()
            } catch (e: Exception) {
                Log.e(TAG, "getDatabaseSize error", e)
                0L
            }
        }

        @JavascriptInterface
        fun getTableCounts(): String {
            return try {
                val counts = dbHelper.getTableCounts()
                counts.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getTableCounts error", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun vacuumDatabase(): String {
            return try {
                dbHelper.vacuumDatabase()
                JSONObject().apply {
                    put("success", true)
                    put("message", "تم تحسين قاعدة البيانات بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "vacuumDatabase error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== UTILITY ==========
        @JavascriptInterface
        fun showToast(message: String) {
            if (isDestroyed.get()) return
            val safeMessage = message ?: " "
            Toast.makeText(context, safeMessage, Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun isServerReady(): Boolean = serverReady

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }

        @JavascriptInterface
        fun getDatabaseInfo(): String {
            return try {
                val json = JSONObject().apply {
                    put("version", DatabaseHelper.VERSION)
                    put("tables_count", dbHelper.getTableCounts().length())
                    put("is_encrypted", false)
                    put("size_bytes", dbHelper.getDatabaseSize())
                }
                json.toString()
            } catch (e: Exception) {
                JSONObject().apply {
                    put("error", e.message ?: "Unknown error")
                }.toString()
            }
        }

        @JavascriptInterface
        fun getCustomerCount(): Int {
            return try {
                dbHelper.getParties("customer").length()
            } catch (e: Exception) {
                0
            }
        }

        @JavascriptInterface
        fun getSalesByFuelType(): String {
            return try {
                val sales = dbHelper.getSalesByFuelType()
                sales.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getSalesByFuelType error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getLatestMeterReadings(): String {
            return try {
                val readings = dbHelper.getLatestMeterReadings()
                readings.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getLatestMeterReadings error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getAssetMaintenanceHistory(assetId: Long): String {
            return try {
                val history = dbHelper.getAssetMaintenanceHistory(assetId)
                history.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getAssetMaintenanceHistory error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getUserNotifications(userId: Long): String {
            return try {
                val notifications = dbHelper.getUserNotifications(userId)
                notifications.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getUserNotifications error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getUserPermissions(userId: Long): String {
            return try {
                val permissions = dbHelper.getUserPermissions(userId)
                permissions.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getUserPermissions error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun checkLowStock(): String {
            return try {
                val items = dbHelper.checkLowStock()
                items.toString()
            } catch (e: Exception) {
                Log.e(TAG, "checkLowStock error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun createStockAlert(productId: Long, threshold: Double): String {
            return try {
                val id = dbHelper.createStockAlert(productId, threshold)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "تم إنشاء التنبيه بنجاح")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "createStockAlert error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getDieselPrice(): Double {
            return try {
                dbHelper.getDieselPrice()
            } catch (e: Exception) {
                Log.e(TAG, "getDieselPrice error", e)
                0.0
            }
        }

        @JavascriptInterface
        fun getGasolinePrice(): String {
            return try {
                dbHelper.getGasolinePrice().toString()
            } catch (e: Exception) {
                Log.e(TAG, "getGasolinePrice error", e)
                "0.0"
            }
        }

        @JavascriptInterface
        fun getManagerPhone(): String {
            return try {
                dbHelper.getManagerPhone() ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "getManagerPhone error", e)
                ""
            }
        }

        @JavascriptInterface
        fun getDriverPhones(): String {
            return try {
                val phones = dbHelper.getDriverPhones()
                phones.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getDriverPhones error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getTrustedSmscList(): String {
            return try {
                val list = dbHelper.getTrustedSmscList()
                list.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getTrustedSmscList error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun getCustomerBalanceByPhone(phone: String): Double {
            return try {
                dbHelper.getCustomerBalanceByPhone(phone)
            } catch (e: Exception) {
                Log.e(TAG, "getCustomerBalanceByPhone error", e)
                0.0
            }
        }

        @JavascriptInterface
        fun getLastOrderByPhone(phone: String): String {
            return try {
                val order = dbHelper.getLastOrderByPhone(phone)
                order?.toString() ?: JSONObject().apply {
                    put("success", false)
                    put("error", "لا توجد طلبات")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getLastOrderByPhone error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getOrderHistoryByPhone(phone: String): String {
            return try {
                val history = dbHelper.getOrderHistoryByPhone(phone)
                history.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getOrderHistoryByPhone error", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun recordDieselDelivery(jsonData: String): String {
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

                val success = dbHelper.recordDieselDelivery(
                    customerId,
                    customerName,
                    quantityLiters,
                    quantityDabbas,
                    location,
                    deliveryTime,
                    unitPrice,
                    totalAmount,
                    orderId
                )
                JSONObject().apply {
                    put("success", success)
                    put("message", if (success) "تم تسجيل التسليم بنجاح" else "فشل تسجيل التسليم")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "recordDieselDelivery error", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        // ========== EXPORT ALL DATA ==========
        @JavascriptInterface
        fun exportAllData(): String {
            return try {
                val data = dbHelper.exportAllData()
                data.toString()
            } catch (e: Exception) {
                Log.e(TAG, "exportAllData error", e)
                "{}"
            }
        }
    }

    private fun safeEvaluateJs(script: String) {
        if (isDestroyed.get()) return
        try {
            val wv = webView
            if (wv != null && wv.isAttachedToWindow) {
                wv.evaluateJavascript(script, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to evaluate JS: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != PERMISSION_REQUEST_CODE) return
        if (grantResults.isEmpty()) {
            Log.w(TAG, "Permission result is empty")
            return
        }

        val denied = permissions.zip(grantResults.toList())
            .filter { it.second != PackageManager.PERMISSION_GRANTED }
            .map { it.first }

        if (denied.isNotEmpty()) {
            Log.w(TAG, "Denied permissions: $denied")
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
            lifecycleScope.launch {
                delay(500)
                startSMSService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isDestroyed.get() && webView != null) {
            if (!webView!!.isAttachedToWindow) {
                Log.w(TAG, "WebView not attached, reloading...")
                handler.postDelayed({
                    if (!isDestroyed.get()) {
                        loadWebViewFromAssets()
                    }
                }, 500)
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        isDestroyed.set(true)
        handler.removeCallbacksAndMessages(null)

        stopSMSService()

        try {
            val wv = webView
            if (wv != null) {
                destroyWebView(wv)
                webView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebView cleanup in onDestroy", e)
        }

        dbHelper.close()
        super.onDestroy()
    }

    private fun destroyWebView(webView: WebView?) {
        if (webView == null) return

        try {
            (webView.parent as? ViewGroup)?.removeView(webView)

            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
            webView.removeJavascriptInterface("AndroidInterface")
            webView.removeAllViews()
            webView.destroy()

            Log.d(TAG, "WebView destroyed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying WebView", e)
        }
    }

    private fun stopSMSService() {
        try {
            val intent = Intent(this, SMSService::class.java)
            stopService(intent)
            Log.d(TAG, "SMSService stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SMSService", e)
        }
    }
}
