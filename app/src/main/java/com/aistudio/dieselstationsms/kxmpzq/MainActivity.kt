package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.ui.theme.MyApplicationTheme
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val CAMERA_REQUEST = 101
    private val BIOMETRIC_REQUEST = 102
    private val ALL_PERMISSIONS = 103
    private var geminiApiKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // تفعيل تصحيح أخطاء الـ WebView في نسخة التطوير فقط
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        
        enableEdgeToEdge()

        try {
            geminiApiKey = loadEnvKey("GEMINI_API_KEY")
            Log.d("MainActivity", "Gemini Key loaded: ${if (geminiApiKey.isNotEmpty()) "Yes" else "No"}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading env key", e)
            Toast.makeText(this, "خطأ في تحميل المفتاح: ${e.message}", Toast.LENGTH_LONG).show()
        }

        requestAllPermissions()

        try {
            startService(Intent(this, SMSService::class.java))
            Log.d("MainActivity", "SMSService started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting SMSService", e)
            Toast.makeText(this, "خطأ في بدء الخدمة: ${e.message}", Toast.LENGTH_LONG).show()
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun loadEnvKey(key: String): String {
        return try {
            assets.open(".env").use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$key=")) trimmed.substringAfter("=").trim() else null
                    }.firstOrNull() ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading .env: ${e.message}")
            ""
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (needed.isNotEmpty()) {
                requestPermissions(needed, ALL_PERMISSIONS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun WebViewScreen(modifier: Modifier = Modifier) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false

                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            Log.e("WebView", "Error loading page: $description")
                            Handler(Looper.getMainLooper()).postDelayed({
                                loadUrl("http://127.0.0.1:8080/")
                            }, 3000)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d("WebView", "Page loaded: $url")
                        }
                    }
                    webChromeClient = WebChromeClient()

                    addJavascriptInterface(WebAppInterface(context, this@MainActivity), "AndroidInterface")

                    Handler(Looper.getMainLooper()).postDelayed({
                        loadUrl("http://127.0.0.1:8080/")
                    }, 3000)
                }
            }
        )
    }

    fun showBiometricPrompt(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executors.newSingleThreadExecutor()
            val biometricPrompt = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.biometric_prompt_title))
                .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                .setNegativeButton(getString(R.string.biometric_cancel), executor) { _, _ ->
                    runOnUiThread { onError("cancelled") }
                }
                .build()

            val cancellationSignal = CancellationSignal()
            cancellationSignal.setOnCancelListener {
                runOnUiThread { onError("cancelled") }
            }

            biometricPrompt.authenticate(
                cancellationSignal, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        super.onAuthenticationSucceeded(result)
                        runOnUiThread { onSuccess() }
                    }
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        runOnUiThread { onError("failed") }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        super.onAuthenticationError(errorCode, errString)
                        runOnUiThread { onError(errString?.toString() ?: "error") }
                    }
                }
            )
        } else {
            onError("unsupported")
        }
    }

    fun startQrScanner(callback: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                webView.evaluateJavascript("window.startQrScanner && window.startQrScanner()", null)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    inner class WebAppInterface(private val context: Context, private val activity: MainActivity) {

        @JavascriptInterface
        fun showToast(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun requestBiometricAuth(): String {
            val result = JSONObject()
            activity.showBiometricPrompt(
                onSuccess = {
                    result.put("success", true)
                    result.put("message", "authenticated")
                    activity.runOnUiThread {
                        webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(${result.toString()})", null)
                    }
                },
                onError = { error ->
                    result.put("success", false)
                    result.put("error", error)
                    activity.runOnUiThread {
                        webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(${result.toString()})", null)
                    }
                }
            )
            return "requested"
        }

        @JavascriptInterface
        fun getGeminiApiKey(): String {
            return geminiApiKey
        }

        @JavascriptInterface
        fun printInvoice(htmlContent: String) {
            try {
                val file = java.io.File(context.filesDir, "invoices/invoice_${System.currentTimeMillis()}.html")
                file.parentFile?.mkdirs()
                file.writeText(htmlContent, Charsets.UTF_8)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/html")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, "مشاركة"))
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            val info = JSONObject()
            info.put("model", Build.MODEL)
            info.put("manufacturer", Build.MANUFACTURER)
            info.put("androidVersion", Build.VERSION.RELEASE)
            info.put("sdk", Build.VERSION.SDK_INT)
            info.put("packageName", context.packageName)
            info.put("geminiConfigured", geminiApiKey.isNotEmpty())
            return info.toString()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            ALL_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "تم منح الأذونات", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
