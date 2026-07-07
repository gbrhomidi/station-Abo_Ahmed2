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
 * - قاعدة البيانات V7 (DatabaseHelper المتكامل مع نظام Parties)
 * - خدمة الخادم المحلي SMSService المحدثة (قراءة المفاتيح من BuildConfig)
 * - واجهة ويب متطورة (web_interface.html) مع دعم AI المتعدد
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
        private const val SERVICE_START_DELAY_MS = 3000L      // تأخير بدء الخدمة
        private const val WEBVIEW_LOAD_DELAY_MS = 4000L       // تأخير تحميل WebView
        private const val WEBVIEW_INITIAL_RETRY_DELAY_MS = 2000L
        private const val WEBVIEW_MAX_RETRY_DELAY_MS = 15000L
        private const val MAX_WEBVIEW_RETRIES = 10
        private const val SERVER_PORT = 8080

        private const val BIOMETRIC_TITLE = "المصادقة البيومترية"
        private const val BIOMETRIC_SUBTITLE = "استخدم بصمة الإصبع أو الوجه للدخول"
        private const val BIOMETRIC_CANCEL = "إلغاء"
    }

    // ============================================================
    //  متغيرات الحالة
    // ============================================================

    private var webView: WebView? = null
    private var geminiApiKey: String = ""   // سيتم قراءتها من BuildConfig عبر SMSService، لكن نحتفظ بها للتوافق
    private var serverReady = false
    private var webViewRetryCount = 0
    private val isDestroyed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var isWebViewInitialized = false

    private val isDebugMode: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // ============================================================
    //  دورة حياة النشاط
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // تفعيل الحواف الكاملة (مع معالجة الأخطاء)
        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            Log.e(TAG, "enableEdgeToEdge failed: ${e.message}", e)
        }

        // تفعيل تصحيح WebView في وضع التطوير
        if (isDebugMode) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
                Log.d(TAG, "Debug mode enabled – WebView debugging active")
            } catch (e: Exception) {
                Log.w(TAG, "WebView debugging enable failed: ${e.message}")
            }
        }

        // تحميل مفتاح Gemini (يُستخدم في JavaScript Interface)
        geminiApiKey = loadEnvKey("GEMINI_API_KEY")
        if (geminiApiKey.isEmpty()) {
            Log.w(TAG, "GEMINI_API_KEY not found in .env – AI features may be limited")
        } else {
            Log.d(TAG, "Gemini API key loaded successfully")
        }

        // طلب الأذونات المطلوبة
        requestAllPermissions()

        // بدء خدمة الخادم المحلي بعد تأخير (لتجنب تعارض بدء التشغيل)
        lifecycleScope.launch {
            delay(SERVICE_START_DELAY_MS)
            if (!isDestroyed.get()) {
                startSMSService()
            }
        }

        // تحميل واجهة Compose التي تحتوي على WebView
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        // بدء تحميل WebView بعد تأخير كافٍ لضمان تشغيل الخادم
        lifecycleScope.launch {
            delay(WEBVIEW_LOAD_DELAY_MS)
            if (!isDestroyed.get()) {
                loadWebViewUrl()
            }
        }
    }

    // ============================================================
    //  إدارة مفتاح Gemini (من .env للتوافق مع الإصدارات السابقة)
    //  ملاحظة: المفاتيح تُقرأ الآن من BuildConfig في SMSService،
    //  لكن نحتفظ بهذه الدالة لتوفير المفتاح لواجهة JavaScript.
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

    /**
     * تشفير بسيط في الذاكرة (XOR باستخدام بصمة الجهاز)
     */
    private fun encryptInMemory(value: String): String {
        val key = Build.FINGERPRINT.hashCode().toByte()
        return value.map { (it.code xor key.toInt()).toChar() }.joinToString("")
    }

    /**
     * فك التشفير عند الاستخدام (تُستخدم في واجهة JavaScript)
     */
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
    //  إدارة خدمة SMSService (الخادم المحلي)
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
            Log.e(TAG, "SecurityException starting SMSService – missing permissions?", e)
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
    //  إدارة WebView (الواجهة الرئيسية)
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

                        // تحسين الأداء باستخدام الطبقة المادية
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

    /**
     * إنشاء WebViewClient مخصص مع معالجة الأخطاء والروابط المخصصة
     */
    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isDestroyed.get()) return
                serverReady = true
                webViewRetryCount = 0
                Log.d(TAG, "WebView page finished loading: $url")
            }

            /**
             * منع تحميل الروابط المخصصة داخل WebView، بل فتحها في تطبيقات خارجية
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (handleCustomUrl(url)) {
                    return true
                }
                // السماح للروابط العادية بالتحميل داخل WebView
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

                if (webViewRetryCount < MAX_WEBVIEW_RETRIES) {
                    webViewRetryCount++
                    val delay = calculateRetryDelay(webViewRetryCount)
                    Log.d(TAG, "Retrying WebView in ${delay}ms (attempt $webViewRetryCount/$MAX_WEBVIEW_RETRIES)")

                    lifecycleScope.launch {
                        delay(delay)
                        if (!isDestroyed.get()) {
                            loadWebViewUrl()
                        }
                    }
                } else {
                    Log.e(TAG, "Max WebView retries reached")
                    showErrorPage()
                }
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

                if (webViewRetryCount < MAX_WEBVIEW_RETRIES) {
                    webViewRetryCount++
                    val delay = calculateRetryDelay(webViewRetryCount)
                    lifecycleScope.launch {
                        delay(delay)
                        if (!isDestroyed.get()) {
                            loadWebViewUrl()
                        }
                    }
                } else {
                    Log.e(TAG, "Max WebView retries reached")
                    showErrorPage()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // رفض الاتصالات غير الآمنة
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
                }
                return true
            }
        }
    }

    // ============================================================
    //  تحميل WebView URL وإعادة المحاولة
    // ============================================================

    private fun loadWebViewUrl() {
        if (isDestroyed.get()) return

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
            retryLoadUrl()
        }
    }

    private fun retryLoadUrl() {
        if (webViewRetryCount < MAX_WEBVIEW_RETRIES && !isDestroyed.get()) {
            webViewRetryCount++
            val delay = calculateRetryDelay(webViewRetryCount)
            Log.d(TAG, "Retrying URL load in ${delay}ms (attempt $webViewRetryCount/$MAX_WEBVIEW_RETRIES)")

            lifecycleScope.launch {
                delay(delay)
                if (!isDestroyed.get()) {
                    loadWebViewUrl()
                }
            }
        } else {
            Log.e(TAG, "Max retries reached, showing error page")
            showErrorPage()
        }
    }

    private fun calculateRetryDelay(attempt: Int): Long {
        val delay = WEBVIEW_INITIAL_RETRY_DELAY_MS * (1 shl (attempt - 1))
        return minOf(delay, WEBVIEW_MAX_RETRY_DELAY_MS)
    }

    // ============================================================
    //  عرض صفحة الخطأ داخل WebView
    // ============================================================

    private fun showErrorPage() {
        val wv = webView ?: return
        if (isDestroyed.get()) return

        val errorHtml = """
            <html dir="rtl">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { font-family: sans-serif; text-align: center; padding: 50px 20px; background: #f5f5f5; margin: 0; }
                .error-box { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); max-width: 400px; margin: 0 auto; }
                h1 { color: #d32f2f; font-size: 24px; }
                p { color: #666; line-height: 1.6; }
                button { background: #1976d2; color: white; border: none; padding: 12px 24px;
                        border-radius: 5px; cursor: pointer; margin-top: 20px; font-size: 16px; }
                button:hover { background: #1565c0; }
            </style></head>
            <body>
                <div class="error-box">
                    <h1>⚠️ تعذر الاتصال بالخادم المحلي</h1>
                    <p>يرجى التحقق من اتصالك والمحاولة مرة أخرى</p>
                    <button onclick="location.reload()">إعادة المحاولة</button>
                </div>
            </body>
            </html>
        """.trimIndent()

        try {
            wv.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load error page: ${e.message}")
        }
    }

    // ============================================================
    //  معالجة الروابط المخصصة (WhatsApp, Facebook, Email)
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
            else -> false
        }
    }

    // ============================================================
    //  المصادقة البيومترية (Biometric)
    // ============================================================

    fun showBiometricPrompt(onSuccess: () -> Unit, onError: (String) -> Unit) {
        // التحقق من وجود المكتبة
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
            // إرجاع الحالة فقط (المفتاح مشفر، لا نكشفه)
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
    }

    /**
     * تنفيذ كود JavaScript بأمان
     */
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
    //  دورة حياة النشاط – إدارة النتائج والتنظيف
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

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        isDestroyed.set(true)
        handler.removeCallbacksAndMessages(null)

        // إيقاف الخدمة
        stopSMSService()

        // تدمير WebView
        try {
            val wv = webView
            if (wv != null) {
                destroyWebView(wv)
                webView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebView cleanup in onDestroy", e)
        }

        super.onDestroy()
    }

    /**
     * تدمير WebView بشكل آمن لمنع تسرب الذاكرة
     */
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
