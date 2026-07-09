package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.annotation.SuppressLint
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MainActivity - النشاط الرئيسي لتطبيق محطة أبو أحمد لمشتقات الديزل
 *
 * الإصدار 4.0 – متوافق مع:
 * - قاعدة البيانات V8 (DatabaseHelper المتكامل مع نظام Parties والدوال الديناميكية)
 * - خدمة الخادم المحلي SMSService المحدثة (قراءة المفاتيح من BuildConfig)
 * - واجهة ويب متطورة (web_interface.html) مع دعم AI المتعدد
 * - نظام مصادقة بيومترية محسّن
 *
 * الميزات:
 * - عرض واجهة ويب متكاملة عبر WebView
 * - دعم المصادقة البيومترية (بصمة / وجه)
 * - بدء خدمة الخادم المحلي (SMSService) تلقائياً
 * - إدارة الأذونات المطلوبة للتشغيل
 * - معالجة الروابط المخصصة (واتساب، فيسبوك، بريد)
 * - دورة حياة محسنة وإدارة ذاكرة
 * - واجهة JavaScript للتفاعل مع التطبيق
 * - دعم مسح QR وباركود عبر مكتبة html5-qrcode (تُحمَّل من assets)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SERVICE_START_DELAY_MS = 3000L
        private const val WEBVIEW_LOAD_DELAY_MS = 4000L
        private const val WEBVIEW_INITIAL_RETRY_DELAY_MS = 2000L
        private const val WEBVIEW_MAX_RETRY_DELAY_MS = 15000L
        private const val MAX_WEBVIEW_RETRIES = 3
        private const val SERVER_PORT = 8080

        private const val BIOMETRIC_TITLE = "المصادقة البيومترية"
        private const val BIOMETRIC_SUBTITLE = "استخدم بصمة الإصبع أو الوجه للدخول"
        private const val BIOMETRIC_CANCEL = "إلغاء"
    }

    // ============================================================
    //  متغيرات الحالة
    // ============================================================

    private var webView: WebView? = null
    private var geminiApiKey: String = ""
    private var serverReady = false
    private var webViewRetryCount = 0
    private val isDestroyed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var isWebViewInitialized = false
    private var isErrorPageShown = false

    private val isDebugMode: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private lateinit var dbHelper: DatabaseHelper

    // ============================================================
    //  دورة حياة النشاط
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dbHelper = DatabaseHelper(this)

        if (isDebugMode) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
                Log.d(TAG, "Debug mode enabled – WebView debugging active")
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
            Log.w(TAG, "GEMINI_API_KEY not found in .env – AI features may be limited")
        } else {
            Log.d(TAG, "Gemini API key loaded successfully")
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

        lifecycleScope.launch {
            delay(WEBVIEW_LOAD_DELAY_MS)
            if (!isDestroyed.get()) {
                loadWebViewUrl()
            }
        }
    }

    // ============================================================
    //  إدارة مفتاح Gemini
    // ============================================================

    private fun loadEnvKey(key: String): String {
        return try {
            assets.open(".env").use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$key=")) {
                            val value = trimmed.substringAfter("=").trim()
                            if (value.isNotEmpty() && value != "YOUR_GEMINI_API_KEY_HERE") {
                                encryptInMemory(value)
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

    private fun encryptInMemory(value: String): String {
        val key = Build.FINGERPRINT.hashCode().toByte()
        return value.map { (it.code xor key.toInt()).toChar() }.joinToString("")
    }

    private fun decryptInMemory(encrypted: String): String {
        val key = Build.FINGERPRINT.hashCode().toByte()
        return encrypted.map { (it.code xor key.toInt()).toChar() }.joinToString("")
    }

    // ============================================================
    //  إدارة الأذونات
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
            Log.d(TAG, "All required permissions already granted")
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    // ============================================================
    //  إدارة خدمة SMSService
    // ============================================================

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

    private fun stopSMSService() {
        try {
            val intent = Intent(this, SMSService::class.java)
            stopService(intent)
            Log.d(TAG, "SMSService stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SMSService", e)
        }
    }

    // ============================================================
    //  إدارة WebView
    // ============================================================

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
                            databaseEnabled = false
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccess = false
                            allowContentAccess = false
                            javaScriptCanOpenWindowsAutomatically = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
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
            update = { /* لا حاجة للتحديث هنا */ }
        )
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isDestroyed.get()) return
                serverReady = true
                webViewRetryCount = 0
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

                val errorCode = error?.errorCode ?: -1
                val description = error?.description?.toString() ?: "Unknown error"
                val failingUrl = request?.url?.toString() ?: "unknown"

                Log.w(TAG, "WebView error $errorCode: $description on $failingUrl")
                serverReady = false

                // عرض صفحة الخطأ مباشرة دون إعادة محاولة تلقائية لمنع التحديث المستمر
                showErrorPage()
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
                serverReady = false

                // عرض صفحة الخطأ مباشرة دون إعادة محاولة تلقائية
                showErrorPage()
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
                    // محاولة إعادة إنشاء WebView بعد فترة قصيرة
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
        // إعادة تحميل الواجهة
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        // محاولة تحميل URL بعد إعادة الإنشاء
        handler.postDelayed({
            if (!isDestroyed.get()) {
                loadWebViewUrl()
            }
        }, 1500)
    }

    // ============================================================
    //  تحميل WebView URL (مُعدل لمنع التحديث المستمر)
    // ============================================================

    private fun loadWebViewUrl() {
        if (isDestroyed.get()) return

        // إذا كانت صفحة الخطأ معروضة، لا نحاول التحميل مرة أخرى
        if (isErrorPageShown) {
            Log.d(TAG, "Error page is shown, skipping load")
            return
        }

        val wv = webView ?: run {
            Log.w(TAG, "WebView is null, cannot load URL")
            return
        }

        try {
            if (wv.isAttachedToWindow) {
                Log.d(TAG, "Loading URL: http://127.0.0.1:$SERVER_PORT/")
                wv.loadUrl("http://127.0.0.1:$SERVER_PORT/")
            } else {
                Log.w(TAG, "WebView not attached to window yet, retrying...")
                retryLoadUrl()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading URL: ${e.message}", e)
            // في حالة الخطأ، نعرض صفحة الخطأ مباشرة
            showErrorPage()
        }
    }

    private fun retryLoadUrl() {
        if (isErrorPageShown) {
            Log.d(TAG, "Error page is shown, not retrying")
            return
        }

        if (webViewRetryCount < MAX_WEBVIEW_RETRIES && !isDestroyed.get()) {
            webViewRetryCount++
            val delay = calculateRetryDelay(webViewRetryCount)
            Log.d(TAG, "Retrying URL load in ${delay}ms (attempt $webViewRetryCount/$MAX_WEBVIEW_RETRIES)")

            lifecycleScope.launch {
                delay(delay)
                if (!isDestroyed.get() && !isErrorPageShown) {
                    loadWebViewUrl()
                }
            }
        } else {
            Log.e(TAG, "Max retries reached, showing error page")
            showErrorPage()
        }
    }

    private fun calculateRetryDelay(attempt: Int): Long {
        val delay = WEBVIEW_INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1))
        return minOf(delay, WEBVIEW_MAX_RETRY_DELAY_MS)
    }

    // ============================================================
    //  عرض صفحة الخطأ (داخل WebView)
    // ============================================================

    private fun showErrorPage() {
        if (isDestroyed.get()) return
        if (isErrorPageShown) {
            Log.d(TAG, "Error page already shown")
            return
        }

        isErrorPageShown = true
        val wv = webView ?: run {
            Log.w(TAG, "WebView is null, cannot show error page")
            return
        }

        val errorHtml = """
            <html dir="rtl">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>خطأ في الاتصال</title>
                <style>
                    body {
                        font-family: 'Segoe UI', Tahoma, sans-serif;
                        text-align: center;
                        padding: 50px 20px;
                        background: #f5f5f5;
                        margin: 0;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                    }
                    .error-box {
                        background: white;
                        padding: 30px;
                        border-radius: 16px;
                        box-shadow: 0 4px 20px rgba(0,0,0,0.1);
                        max-width: 400px;
                        margin: 0 auto;
                    }
                    h1 {
                        color: #d32f2f;
                        font-size: 24px;
                        margin-bottom: 12px;
                    }
                    p {
                        color: #666;
                        line-height: 1.6;
                        margin: 8px 0;
                    }
                    .icon {
                        font-size: 48px;
                        margin-bottom: 16px;
                        display: block;
                    }
                    .btn-retry {
                        background: #1976d2;
                        color: white;
                        border: none;
                        padding: 12px 24px;
                        border-radius: 8px;
                        cursor: pointer;
                        margin-top: 20px;
                        font-size: 16px;
                        transition: background 0.3s;
                    }
                    .btn-retry:hover {
                        background: #1565c0;
                    }
                    .sub-text {
                        font-size: 12px;
                        color: #999;
                        margin-top: 12px;
                    }
                </style>
            </head>
            <body>
                <div class="error-box">
                    <span class="icon">⚠️</span>
                    <h1>تعذر الاتصال بالخادم المحلي</h1>
                    <p>يرجى التحقق من اتصالك والمحاولة مرة أخرى</p>
                    <p class="sub-text">تأكد من تشغيل خدمة الخادم</p>
                    <button class="btn-retry" onclick="window.location.reload()">🔄 إعادة المحاولة</button>
                </div>
            </body>
            </html>
        """.trimIndent()

        try {
            wv.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
            Log.d(TAG, "Error page loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load error page: ${e.message}")
        }
    }

    // ============================================================
    //  معالجة الروابط المخصصة
    // ============================================================

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

    // ============================================================
    //  المصادقة البيومترية
    // ============================================================

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

    // ============================================================
    //  واجهة JavaScript (Bridge)
    // ============================================================

    inner class WebAppInterface(
        private val context: Context,
        private val activity: MainActivity
    ) {

        @JavascriptInterface
        fun login(username: String, password: String): String {
            Log.d(TAG, "login() called with username: $username")
            try {
                val authResult = dbHelper.authenticateUser(username, password)
                if (authResult != null) {
                    val token = java.util.UUID.randomUUID().toString()
                    val response = JSONObject().apply {
                        put("success", true)
                        put("user", authResult)
                        put("token", token)
                    }
                    dbHelper.logActivity(username, "login", "تسجيل دخول ناجح عبر WebInterface")
                    return response.toString()
                } else {
                    val response = JSONObject().apply {
                        put("success", false)
                        put("error", "بيانات خاطئة")
                    }
                    return response.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                val response = JSONObject().apply {
                    put("success", false)
                    put("error", "خطأ داخلي: ${e.message}")
                }
                return response.toString()
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

        @JavascriptInterface
        fun getGeminiApiKey(): String {
            return if (geminiApiKey.isNotEmpty()) "configured" else "not_configured"
        }

        @JavascriptInterface
        fun showToast(message: String) {
            if (isDestroyed.get()) return
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                    put("tables_count", 42)
                    put("is_encrypted", false)
                }
                json.toString()
            } catch (e: Exception) {
                "{\"error\":\"${e.message}\"}"
            }
        }

        @JavascriptInterface
        fun getCustomerCount(): Int {
            return try {
                dbHelper.getParties().length()
            } catch (e: Exception) {
                0
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

    // ============================================================
    //  دورة حياة النشاط
    // ============================================================

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
                    "بعض الأذونات الأساسية مرفوضة. قد لا تعمل بعض الميزات.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // إذا كانت صفحة الخطأ معروضة ولا يوجد اتصال، نحاول إعادة التحميل مرة واحدة
        if (!isDestroyed.get() && isErrorPageShown) {
            // ننتظر قليلاً ثم نحاول التحميل مرة واحدة
            handler.postDelayed({
                if (!isDestroyed.get() && isErrorPageShown) {
                    isErrorPageShown = false
                    webViewRetryCount = 0
                    loadWebViewUrl()
                }
            }, 2000)
        }

        if (!isDestroyed.get() && webView != null) {
            if (!webView!!.isAttachedToWindow) {
                Log.w(TAG, "WebView not attached, reloading...")
                lifecycleScope.launch {
                    delay(500)
                    if (!isDestroyed.get()) {
                        loadWebViewUrl()
                    }
                }
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
}
