package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aistudio.dieselstationsms.kxmpzq.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SERVICE_START_DELAY_MS = 3000L
        private const val WEBVIEW_LOAD_DELAY_MS = 4000L
        private const val SERVER_PORT = 8080
    }

    private var webView: WebView? = null
    private val isDestroyed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var serverReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (e: Exception) { Log.e(TAG, "enableEdgeToEdge failed", e) }

        // طلب الأذونات
        requestAllPermissions()

        // بدء الخدمة
        lifecycleScope.launch {
            delay(SERVICE_START_DELAY_MS)
            if (!isDestroyed.get()) {
                startSMSService()
            }
        }

        // عرض واجهة Compose مع WebView
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        WebViewScreen()
                        // زر إعادة تحميل WebView في حالة الفشل
                        Button(
                            onClick = { reloadWebView() },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            Text("🔄 إعادة تحميل")
                        }
                    }
                }
            }
        }

        // تحميل WebView بعد تأخير
        lifecycleScope.launch {
            delay(WEBVIEW_LOAD_DELAY_MS)
            if (!isDestroyed.get()) {
                loadWebViewUrl()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun WebViewScreen() {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).apply {
                    val wv = WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (isDestroyed.get()) return
                                serverReady = true
                                Log.d(TAG, "WebView loaded: $url")
                                Toast.makeText(context, "تم تحميل الواجهة", Toast.LENGTH_SHORT).show()
                            }
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                super.onReceivedError(view, errorCode, description, failingUrl)
                                Log.e(TAG, "WebView error: $errorCode - $description")
                                Toast.makeText(context, "خطأ في تحميل الواجهة: $description", Toast.LENGTH_LONG).show()
                            }
                        }
                        webChromeClient = WebChromeClient()
                        try {
                            addJavascriptInterface(
                                WebAppInterface(context, this@MainActivity),
                                "AndroidInterface"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add JS interface", e)
                        }
                    }
                    addView(wv)
                    this@MainActivity.webView = wv
                }
            },
            update = { /* no-op */ }
        )
    }

    private fun loadWebViewUrl() {
        val wv = webView ?: return
        try {
            if (wv.isAttachedToWindow) {
                Log.d(TAG, "Loading http://127.0.0.1:$SERVER_PORT/")
                wv.loadUrl("http://127.0.0.1:$SERVER_PORT/")
            } else {
                Log.w(TAG, "WebView not attached, retrying...")
                handler.postDelayed({ loadWebViewUrl() }, 1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading URL", e)
        }
    }

    private fun reloadWebView() {
        webView?.reload()
        Toast.makeText(this, "جاري إعادة تحميل الواجهة...", Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    //  طلب الأذونات
    // ============================================================
    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()
        if (!isPermissionGranted(Manifest.permission.SEND_SMS)) permissions.add(Manifest.permission.SEND_SMS)
        if (!isPermissionGranted(Manifest.permission.RECEIVE_SMS)) permissions.add(Manifest.permission.RECEIVE_SMS)
        if (!isPermissionGranted(Manifest.permission.READ_SMS)) permissions.add(Manifest.permission.READ_SMS)
        if (!isPermissionGranted(Manifest.permission.CAMERA)) permissions.add(Manifest.permission.CAMERA)
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

    // ============================================================
    //  بدء الخدمة
    // ============================================================
    private fun startSMSService() {
        if (isDestroyed.get()) return
        try {
            val intent = Intent(this, SMSService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "SMSService started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SMSService", e)
        }
    }

    private fun stopSMSService() {
        try {
            stopService(Intent(this, SMSService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SMSService", e)
        }
    }

    // ============================================================
    //  JavaScript Interface
    // ============================================================
    inner class WebAppInterface(
        private val context: Context,
        private val activity: MainActivity
    ) {
        @JavascriptInterface
        fun showToast(message: String) {
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
        fun getGeminiApiKey(): String = "configured"

        @JavascriptInterface
        fun requestBiometricAuth(): String {
            Toast.makeText(context, "المصادقة البيومترية غير مفعلة حالياً", Toast.LENGTH_SHORT).show()
            return "not_supported"
        }
    }

    // ============================================================
    //  دورة الحياة
    // ============================================================
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val denied = permissions.zip(grantResults.toList())
            .filter { it.second != PackageManager.PERMISSION_GRANTED }
            .map { it.first }
        if (denied.isNotEmpty()) {
            val critical = listOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
            if (denied.any { it in critical }) {
                Toast.makeText(this, "بعض الأذونات الأساسية مرفوضة. قد لا تعمل بعض الميزات.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        isDestroyed.set(true)
        handler.removeCallbacksAndMessages(null)
        stopSMSService()
        webView?.destroy()
        super.onDestroy()
    }
}
