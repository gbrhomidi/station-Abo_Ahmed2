package com.aistudio.dieselstationsms.kxmpzq

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * خدمة خادم ويب مبسطة – بدون قاعدة بيانات أو AI
 * تعمل على المنفذ 8080 وترد بـ "Hello from SMSService"
 */
class SMSService : Service() {

    companion object {
        private const val TAG = "SMSService"
        private const val SERVER_PORT = 8080
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_service_channel"
    }

    private var server: SimpleServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        setupNotificationChannel()
        startForegroundService()
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        if (server == null || !server!!.isAlive) {
            startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ========== الإشعارات ==========
    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خدمة المحطة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة الخادم المحلي لمحطة أبو أحمد"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText("الخادم المحلي يعمل على المنفذ $SERVER_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started")
    }

    // ========== خادم ويب بسيط ==========
    private fun startServer() {
        try {
            server?.stop()
            server = SimpleServer(SERVER_PORT)
            server?.start()
            Log.d(TAG, "Server started on port $SERVER_PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
        }
    }

    private inner class SimpleServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            // استجابة بسيطة لجميع الطلبات
            val responseText = """
                <html>
                <head><title>محطة أبو أحمد</title></head>
                <body style="font-family: sans-serif; text-align: center; padding: 50px;">
                    <h1>⛽ محطة أبو أحمد</h1>
                    <p>الخادم المحلي يعمل بنجاح ✅</p>
                    <p>تم تشغيل الخدمة المبسطة بدون قاعدة بيانات</p>
                </body>
                </html>
            """.trimIndent()

            val res = newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                responseText
            )

            // إضافة رؤوس CORS للسماح بـ WebView
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            res.addHeader("Access-Control-Allow-Headers", "Content-Type")
            return res
        }
    }
}
