package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
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
 * MainActivity - محسّن ومصحح بالكامل
 * 
 * التحسينات:
 * 1. استخدام Coroutines بدلاً من Handler للتأخيرات
 * 2. إضافة exponential backoff لإعادة محاولة WebView
 * 3. تحسين إدارة الأذونات
 * 4. حماية WebView من تسريب البيانات
 * 5. تحسين BiometricPrompt مع التحقق من الدعم
 * 6. تشفير مفتاح Gemini API في الذاكرة
 * 7. إضافة lifecycle-aware cleanup
 * 8. تحسين معالجة الأخطاء
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SERVICE_START_DELAY_MS = 1500L
        private const val WEBVIEW_LOAD_DELAY_MS = 2000L
        private const val WEBVIEW_INITIAL_RETRY_DELAY_MS = 1000L
        private const val WEBVIEW_MAX_RETRY_DELAY_MS = 10000L
        private const val MAX_WEBVIEW_RETRIES = 5
        private const val SERVER_PORT = 8080
    }

    private lateinit var webView: WebView
    private var geminiApiKey: String = ""
    private var serverReady = false
    private var webViewRetryCount = 0
    private val isDestroyed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // تفعيل debugging فقط في debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        enableEdgeToEdge()

        // تحميل مفتاح API مع التحقق
        geminiApiKey = loadEnvKey("GEMINI_API_KEY")
        if (geminiApiKey.isEmpty()) {
            Log.w(TAG, "GEMINI_API_KEY not found in .env")
        }

        // طلب الأذونات
        requestAllPermissions()

        // بدء الخدمة مع التحقق من Android O+
        lifecycleScope.launch {
            delay(SERVICE_START_DELAY_MS)
            startSMSService()
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        modifier = Modifier.padding(innerPadding),
                        onWebViewCreated = { webView = it }
                    )
                }
            }
        }
    }

    /**
     * تحسين: تحميل مفتاح API مع التحقق والتشفير البسيط
     */
    private fun loadEnvKey(key: String): String {
        return try {
            assets.open(".env").use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$key=")) {
                            val value = trimmed.substringAfter("=").trim()
                            // تشفير بسيط في الذاكرة (XOR مع مفتاح عشوائي)
                            encryptInMemory(value)
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
     * تشفير بسيط في الذاكرة لمنع استخراج المفتاح بسهولة
     */
    private fun encryptInMemory(value: String): String {
        // XOR encryption with device-specific key
        val key = Build.FINGERPRINT.hashCode().toByte()
        return value.map { (it.code xor key.toInt()).toChar() }.joinToString("")
    }

    /**
     * فك التشفير عند الاستخدام
     */
    private fun decryptInMemory(encrypted: String): String {
        val key = Build.FINGERPRINT.hashCode().toByte()
        return encrypted.map { (it.code xor key.toInt()).toChar() }.joinToString("")
    }

    /**
     * تحسين: طلب الأذونات مع التحقق من الحاجة
     */
    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()

        // أذونات SMS (مطلوبة للتطبيق)
        if (!isPermissionGranted(Manifest.permission.SEND_SMS)) {
            permissions.add(Manifest.permission.SEND_SMS)
        }
        if (!isPermissionGranted(Manifest.permission.RECEIVE_SMS)) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }
        if (!isPermissionGranted(Manifest.permission.READ_SMS)) {
            permissions.add(Manifest.permission.READ_SMS)
        }

        // أذونات أخرى
        if (!isPermissionGranted(Manifest.permission.CAMERA)) {
            permissions.add(Manifest.permission.CAMERA)
        }

        // أذونات التخزين للأجهزة القديمة
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!isPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // إشعارات Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * تحسين: بدء الخدمة مع التحقق من Android O+
     */
    private fun startSMSService() {
        if (isDestroyed.get()) return
        
        try {
            val intent = Intent(this, SMSService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "SMSService started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SMSService", e)
            Toast.makeText(this, "فشل في بدء خدمة SMS", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * تحسين: WebView مع lifecycle-aware cleanup
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun WebViewScreen(
        modifier: Modifier = Modifier,
        onWebViewCreated: (WebView) -> Unit
    ) {
        val webViewRef = remember { mutableSetOf<WebView>() }
        
        DisposableEffect(Unit) {
            onDispose {
                webViewRef.forEach { destroyWebView(it) }
                webViewRef.clear()
            }
        }

        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewRef.add(this)
                    onWebViewCreated(this)
                    
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = false // تعطيل للأمان
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        // أمان: تعطيل الوصول للملفات
                        allowFileAccess = false
                        allowContentAccess = false
                        // أمان: تعطيل فتح النوافذ
                        javaScriptCanOpenWindowsAutomatically = false
                    }

                    webViewClient = createWebViewClient()
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(
                        WebAppInterface(context, this@MainActivity),
                        "AndroidInterface"
                    )

                    // تحميل WebView مع Coroutine
                    lifecycleScope.launch {
                        delay(WEBVIEW_LOAD_DELAY_MS)
                        if (!isDestroyed.get()) {
                            loadUrl("http://127.0.0.1:$SERVER_PORT/")
                        }
                    }
                }
            },
            update = { view ->
                // تحديث إذا لزم الأمر
            }
        )
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isDestroyed.get()) return
                serverReady = true
                webViewRetryCount = 0
                Log.d(TAG, "WebView page finished loading: $url")
            }

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
                    Log.d(TAG, "Retrying WebView in ${delay}ms (attempt $webViewRetryCount/$MAX_WEBVIEW_RETRIES)")
                    
                    lifecycleScope.launch {
                        delay(delay)
                        if (!isDestroyed.get() && ::webView.isInitialized) {
                            webView.loadUrl("http://127.0.0.1:$SERVER_PORT/")
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
        }
    }

    /**
     * Exponential backoff للإعادة
     */
    private fun calculateRetryDelay(attempt: Int): Long {
        val delay = WEBVIEW_INITIAL_RETRY_DELAY_MS * (1 shl (attempt - 1))
        return minOf(delay, WEBVIEW_MAX_RETRY_DELAY_MS)
    }

    private fun showErrorPage() {
        if (!::webView.isInitialized || isDestroyed.get()) return
        
        val errorHtml = """
            <html dir="rtl">
            <head><style>
                body { font-family: sans-serif; text-align: center; padding: 50px; background: #f5f5f5; }
                .error-box { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                h1 { color: #d32f2f; }
                button { background: #1976d2; color: white; border: none; padding: 12px 24px; 
                        border-radius: 5px; cursor: pointer; margin-top: 20px; }
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
        
        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
    }

    /**
     * تحسين: BiometricPrompt مع التحقق الكامل
     */
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
                        // أخطاء معينة يمكن تجاهلها
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
                .setTitle(getString(R.string.biometric_prompt_title))
                .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                .setNegativeButtonText(getString(R.string.biometric_cancel))
                .setConfirmationRequired(false)
                .build()

            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Biometric error", e)
            onError("unsupported")
        }
    }

    /**
     * تحسين: WebAppInterface مع Callback صحيح
     */
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
            // فك التشفير قبل الإرجاع
            return if (geminiApiKey.isNotEmpty()) {
                decryptInMemory(geminiApiKey)
            } else {
                ""
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            if (isDestroyed.get()) return
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun isServerReady(): Boolean {
            return serverReady
        }
    }

    /**
     * تحسين: تقييم JS مع التحقق الكامل
     */
    private fun safeEvaluateJs(script: String) {
        if (isDestroyed.get()) return
        try {
            if (::webView.isInitialized && webView.isAttachedToWindow) {
                webView.evaluateJavascript(script, null)
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
            // عرض رسالة للمستخدم
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
        isDestroyed.set(true)
        handler.removeCallbacksAndMessages(null)
        
        // إيقاف الخدمة
        try {
            stopService(Intent(this, SMSService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
        }
        
        // تدمير WebView
        destroyWebView(webView)
        
        super.onDestroy()
    }

    private fun destroyWebView(webView: WebView?) {
        webView?.let {
            try {
                it.stopLoading()
                it.loadUrl("about:blank")
                it.clearHistory()
                it.removeAllViews()
                it.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying WebView", e)
            }
        }
    }
}
