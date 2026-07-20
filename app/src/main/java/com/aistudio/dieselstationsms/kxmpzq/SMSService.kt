package com.aistudio.dieselstationsms.kxmpzq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════
 * SMSService – خدمة الخلفية لإدارة الرسائل النصية والمهام
 * ═══════════════════════════════════════════════════════════════
 *
 * المعمارية الجديدة:
 * - تم إلغاء خادم NanoHTTPD المحلي (المنفذ 8080) نهائياً.
 * - جميع الاتصالات من الواجهات الأمامية تتم عبر AndroidInterface.
 * - هذه الخدمة مسؤولة فقط عن:
 *   1. استقبال الرسائل النصية (SMS) ومعالجتها.
 *   2. إرسال الرسائل النصية عبر SmsManager.
 *   3. جدولة المهام الدورية (النسخ الاحتياطي، التنبيهات).
 *   4. الاتصال بـ APIs الخارجية (Gemini, DeepSeek, Grok, Kimi, ChatGPT).
 *
 * لا تحتوي هذه الخدمة على أي جزء متعلق بالخادم المحلي.
 */
class SMSService : Service() {

    companion object {
        private const val TAG = "SMSService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_service_channel"
        private const val BACKUP_WORK_NAME = "auto_backup_work"
        private const val SMS_DELAY_MS = 1000L
        private const val MAX_SMS_LENGTH = 1600
        private const val PHONE_REGEX = "^[+]?[0-9]{10,14}$"
        private const val MAX_OVERDUE_SMS = 20

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val DATETIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    // ===== Coroutine Scope للعمليات غير المتزامنة =====
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDestroyed = AtomicBoolean(false)

    // ===== مفاتيح API من BuildConfig =====
    private val geminiApiKey: String by lazy { BuildConfig.GEMINI_API_KEY }
    private val deepseekApiKey: String by lazy { BuildConfig.DEEPSEEK_API_KEY }
    private val grokApiKey: String by lazy { BuildConfig.GROK_API_KEY }
    private val kimiApiKey: String by lazy { BuildConfig.KIMI_API_KEY }
    private val chatgptApiKey: String by lazy { BuildConfig.CHATGPT_API_KEY }

    // ===== دورة حياة الخدمة =====
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SMSService onCreate")
        createNotificationChannel()
        isDestroyed.set(false)

        serviceScope.launch {
            try {
                startForegroundService()
                // تم إلغاء بدء الخادم المحلي نهائياً
                // startServer() – لم يعد مستخدماً
                scheduleAutoBackup()
                Log.d(TAG, "Service initialization completed successfully (HTTP Server DISABLED)")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal Error in service initialization: ${e.message}", e)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SMSService onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification())
        // لا توجد حاجة لإعادة تشغيل الخادم
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed.set(true)
        try {
            serviceScope.cancel()
            WorkManager.getInstance(this).cancelUniqueWork(BACKUP_WORK_NAME)
            Log.d(TAG, "Service destroyed and resources cleaned")
        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction: ${e.message}", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ================================================================
    //  الإشعارات (Notification)
    // ================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Station SMS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة مراقبة الرسائل النصية"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText("الخدمة نشطة (خادم HTTP معطل - يعمل عبر الجسر المباشر)")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText("خدمة الرسائل النصية نشطة (بدون خادم محلي)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started")
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)
    }

    // ================================================================
    //  الجدولة التلقائية (Auto Backup)
    // ================================================================

    private fun scheduleAutoBackup() {
        try {
            val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(
                24, TimeUnit.HOURS,
                1, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                backupRequest
            )
            Log.d(TAG, "Auto backup scheduled via WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule auto backup: ${e.message}", e)
        }
    }

    // ================================================================
    //  دوال المساعدة لقراءة الإعدادات من DatabaseHelper (ديناميكية)
    // ================================================================

    private fun getDieselPrice(db: DatabaseHelper): Double {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT default_sale_price FROM fuel_types WHERE fuel_code = 'DIESEL' AND is_deleted = 0 LIMIT 1",
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    private fun getGasolinePrice(db: DatabaseHelper, fuelCode: String = "PETROL_95"): Double {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT default_sale_price FROM fuel_types WHERE fuel_code = ? AND is_deleted = 0 LIMIT 1",
            arrayOf(fuelCode)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    private fun getManagerPhone(db: DatabaseHelper): String? {
        val cursor = db.readableDatabase.rawQuery("""
            SELECT u.phone FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN', 'STATION_MANAGER')
              AND u.status = 'active' AND u.is_deleted = 0
            ORDER BY r.level ASC LIMIT 1
        """, null)
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun getDriverPhones(db: DatabaseHelper): List<String> {
        val phones = mutableListOf<String>()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT phone, phone2 FROM drivers WHERE status = 'active' AND is_deleted = 0",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                it.getString(0)?.let { p -> if (p.isNotBlank()) phones.add(p) }
                it.getString(1)?.let { p -> if (p.isNotBlank()) phones.add(p) }
            }
        }
        return phones.distinct()
    }

    private fun getTrustedSmscList(db: DatabaseHelper): List<String> {
        val phones = mutableListOf<String>()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT phone FROM sms_whitelist WHERE enabled = 1 ORDER BY name",
            null
        )
        cursor.use {
            while (it.moveToNext()) phones.add(it.getString(0))
        }
        return phones
    }

    private fun getCustomerBalanceByPhone(db: DatabaseHelper, phone: String): Double {
        val cursor = db.readableDatabase.rawQuery("""
            SELECT p.current_balance FROM parties p
            WHERE p.phone = ? AND p.is_deleted = 0
            LIMIT 1
        """, arrayOf(phone))
        return cursor.use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    private fun getLastOrderByPhone(db: DatabaseHelper, phone: String): JSONObject? {
        val cursor = db.readableDatabase.rawQuery("""
            SELECT s.* FROM sales_transactions s
            JOIN parties p ON s.customer_party_id = p.id
            WHERE p.phone = ? AND s.is_deleted = 0
            ORDER BY s.id DESC LIMIT 1
        """, arrayOf(phone))
        return cursor.use {
            if (it.moveToFirst()) {
                val json = JSONObject()
                json.put("sale_code", it.getString(it.getColumnIndexOrThrow("sale_code")))
                json.put("liters", it.getDouble(it.getColumnIndexOrThrow("liters")))
                json.put("delivery_location", it.getString(it.getColumnIndexOrThrow("notes")) ?: "")
                json.put("status", it.getString(it.getColumnIndexOrThrow("status")))
                json.put("created_at", it.getString(it.getColumnIndexOrThrow("created_at")))
                json
            } else null
        }
    }

    private fun getOrderHistoryByPhone(db: DatabaseHelper, phone: String, limit: Int): JSONArray {
        val arr = JSONArray()
        val cursor = db.readableDatabase.rawQuery("""
            SELECT s.* FROM sales_transactions s
            JOIN parties p ON s.customer_party_id = p.id
            WHERE p.phone = ? AND s.is_deleted = 0
            ORDER BY s.id DESC LIMIT ?
        """, arrayOf(phone, limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                val json = JSONObject()
                json.put("sale_type", it.getString(it.getColumnIndexOrThrow("sale_type")))
                json.put("liters", it.getDouble(it.getColumnIndexOrThrow("liters")))
                json.put("net_amount", it.getDouble(it.getColumnIndexOrThrow("net_amount")))
                json.put("created_at", it.getString(it.getColumnIndexOrThrow("created_at")))
                arr.put(json)
            }
        }
        return arr
    }

    private fun getPartyIdByPhone(db: DatabaseHelper, phone: String): Int? {
        val cleanPhone = normalizePhone(phone)
        val cursor = db.readableDatabase.rawQuery(
            "SELECT id FROM parties WHERE phone = ? AND is_deleted = 0 LIMIT 1",
            arrayOf(cleanPhone)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else null
        }
    }

    private fun normalizePhone(phone: String): String {
        return phone.replace("[^0-9]".toRegex(), "").takeLast(9)
    }

    private fun isTrustedSmsc(db: DatabaseHelper, smsc: String): Boolean {
        if (smsc.isEmpty()) return true
        val trusted = getTrustedSmscList(db)
        if (trusted.isEmpty()) return true
        return trusted.any { smsc.contains(it) || it.contains(smsc) }
    }

    private fun getRetentionDays(db: DatabaseHelper): Int {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT setting_value FROM system_settings WHERE setting_key = 'retention_days' LIMIT 1",
            null
        )
        val days = cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 90
        }
        return days.coerceIn(7, 365)
    }

    private fun getSystemSetting(db: DatabaseHelper, key: String, defaultValue: String = "0"): String {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT setting_value FROM system_settings WHERE setting_key = ? LIMIT 1",
            arrayOf(key)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else defaultValue
        }
    }

    // ================================================================
    //  دوال معالجة الرسائل النصية (SMS)
    // ================================================================

    private fun isSmsEnabled(db: DatabaseHelper): Boolean {
        return db.getSetting("sms_enabled") != "0"
    }

    private fun isSmsAllowed(phone: String, db: DatabaseHelper): Boolean {
        if (!isSmsEnabled(db)) return false
        val whitelist = db.getSmsWhitelist()
        for (i in 0 until whitelist.length()) {
            val entry = whitelist.getJSONObject(i)
            if (entry.getString("phone") == phone && entry.getInt("enabled") == 1) {
                return true
            }
        }
        return false
    }

    private fun sendSMS(db: DatabaseHelper, phone: String, message: String, type: String): Boolean {
        if (!isSmsAllowed(phone, db)) {
            db.logSms(phone, message, type, "blocked: not in whitelist")
            Log.w(TAG, "SMS blocked: $phone not in whitelist")
            return false
        }

        if (!phone.matches(Regex(PHONE_REGEX))) {
            db.logSms(phone, message, type, "failed: invalid number")
            return false
        }

        if (message.length > MAX_SMS_LENGTH) {
            db.logSms(phone, message, type, "failed: too long")
            return false
        }

        return try {
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(phone, null, message, null, null)
            db.logSms(phone, message, type, "sent")
            Log.d(TAG, "SMS sent to $phone")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}", e)
            db.logSms(phone, message, type, "failed: ${e.message}")
            false
        }
    }

    private fun safeSendReply(context: Context, db: DatabaseHelper, phone: String, message: String) {
        try {
            sendReply(context, db, phone, message)
        } catch (e: Exception) {
            Log.e(TAG, "Safe send failed: ${e.javaClass.simpleName}")
        }
    }

    private fun sendReply(context: Context, db: DatabaseHelper, phone: String, message: String) {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.SEND_SMS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            db.logSms(phone, message, "auto_reply", "failed: permission denied")
            return
        }

        try {
            val smsManager = getSmsManager(context)
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null)
            }
            db.logSms(phone, message, "auto_reply", "sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply: ${e.javaClass.simpleName}")
            db.logSms(phone, message, "auto_reply", "failed: ${e.javaClass.simpleName}")
        }
    }

    private fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
    }

    // ================================================================
    //  AI Providers (Gemini, DeepSeek, Grok, Kimi, ChatGPT)
    // ================================================================

    private fun callAIWithFallback(prompt: String, db: DatabaseHelper): String {
        val providers = listOf(
            "gemini" to geminiApiKey,
            "deepseek" to deepseekApiKey,
            "grok" to grokApiKey,
            "kimi" to kimiApiKey,
            "chatgpt" to chatgptApiKey
        )

        for ((provider, apiKey) in providers) {
            if (apiKey.isEmpty()) continue
            try {
                val result = when (provider) {
                    "gemini" -> callGeminiAPI(prompt, apiKey)
                    "deepseek" -> callDeepSeekAPI(prompt, apiKey)
                    "grok" -> callGrokAPI(prompt, apiKey)
                    "kimi" -> callKimiAPI(prompt, apiKey)
                    "chatgpt" -> callChatGPTAPI(prompt, apiKey)
                    else -> null
                }
                if (result != null && !result.contains("خطأ") && !result.contains("API key") && result.length > 10) {
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Provider $provider failed: ${e.message}")
            }
        }
        return "جميع محاولات الاتصال بالذكاء الاصطناعي فشلت. يرجى التحقق من المفاتيح."
    }

    private fun callGeminiAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 1024)
                })
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            parseAIResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini error: ${e.message}", e)
            null
        }
    }

    private fun callDeepSeekAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = URL("https://api.deepseek.com/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("model", "deepseek-chat")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
                put("max_tokens", 1024)
                put("temperature", 0.7)
            }

            conn.outputStream.use { os -> os.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            val json = JSONObject(response)
            json.getJSONArray("choices")?.getJSONObject(0)?.getJSONObject("message")?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek error: ${e.message}", e)
            null
        }
    }

    private fun callGrokAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = URL("https://api.x.ai/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("model", "grok-beta")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
                put("max_tokens", 1024)
                put("temperature", 0.7)
            }

            conn.outputStream.use { os -> os.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            val json = JSONObject(response)
            json.getJSONArray("choices")?.getJSONObject(0)?.getJSONObject("message")?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "Grok error: ${e.message}", e)
            null
        }
    }

    private fun callKimiAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = URL("https://api.moonshot.cn/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("model", "moonshot-v1-8k")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
                put("max_tokens", 1024)
                put("temperature", 0.7)
            }

            conn.outputStream.use { os -> os.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            val json = JSONObject(response)
            json.getJSONArray("choices")?.getJSONObject(0)?.getJSONObject("message")?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "Kimi error: ${e.message}", e)
            null
        }
    }

    private fun callChatGPTAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = URL("https://api.openai.com/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("model", "gpt-3.5-turbo")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
                put("max_tokens", 1024)
                put("temperature", 0.7)
            }

            conn.outputStream.use { os -> os.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            val json = JSONObject(response)
            json.getJSONArray("choices")?.getJSONObject(0)?.getJSONObject("message")?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "ChatGPT error: ${e.message}", e)
            null
        }
    }

    private fun parseAIResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            if (json.has("candidates")) {
                val candidates = json.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    if (parts.length() > 0) {
                        return parts.getJSONObject(0).getString("text")
                    }
                }
            }
            if (json.has("error")) {
                return json.getJSONObject("error").optString("message", "خطأ في API")
            }
            json.optString("error", "لم يتم الحصول على رد")
        } catch (e: Exception) {
            "خطأ في معالجة الرد"
        }
    }

    // ================================================================
    //  دوال إضافية مساعدة
    // ================================================================

    private fun recordDieselDelivery(
        db: DatabaseHelper,
        customerId: String,
        customerName: String,
        quantityLiters: Double,
        quantityDabbas: Double,
        location: String,
        deliveryTime: String,
        unitPrice: Double,
        totalAmount: Double,
        orderId: String
    ): Boolean {
        try {
            val partyId = getPartyIdByPhone(db, customerId) ?: return false

            require(quantityLiters in 1.0..10000.0) { "Invalid quantity" }
            require(unitPrice in 1.0..1000000.0) { "Invalid price" }
            require(totalAmount in 0.0..1000000.0 * 10000.0) { "Invalid total" }
            require(location.length in 3..200) { "Invalid location" }

            val subtotal = quantityLiters * unitPrice

            val result = db.insertSaleTransaction(
                stationId = 1,
                shiftId = 1,
                customerPartyId = partyId,
                fuelTypeId = 1,
                pumpId = null,
                nozzleId = null,
                liters = quantityLiters,
                pricePerLiter = unitPrice,
                subtotal = subtotal,
                discountAmount = 0.0,
                taxAmount = 0.0,
                grossAmount = totalAmount,
                netAmount = totalAmount,
                paymentMethod = "credit",
                isCredit = true,
                dueDate = DATE_FORMAT.format(Date()),
                cashierId = 1,
                notes = "طلب توصيل ديزل - ${location.take(100)} في ${deliveryTime.take(50)}"
            )

            if (result <= 0) return false

            val currentBalance = getCustomerBalanceByPhone(db, customerId)
            val newBalance = currentBalance + totalAmount
            val values = android.content.ContentValues().apply {
                put("current_balance", newBalance)
                put("total_due", totalAmount)
            }
            db.writableDatabase.update("parties", values, "id = ?", arrayOf(partyId.toString()))

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error recording delivery: ${e.javaClass.simpleName}")
            return false
        }
    }

    private fun notifyManager(context: Context, db: DatabaseHelper, managerPhone: String, message: String) {
        try {
            sendReply(context, db, managerPhone, message)
            val pushEnabled = getSystemSetting(db, "push_notifications_enabled", "0") == "1"
            if (pushEnabled) {
                // تنبيه دفع (Push) – يمكن تفعيله لاحقاً
                Log.d(TAG, "Push notification would be sent to $managerPhone")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify manager: ${e.javaClass.simpleName}")
        }
    }

    private fun logSecurityEvent(context: Context, event: String, phone: String, details: String) {
        // تسجيل الأحداث الأمنية – يمكن توسيعها لاحقاً
        Log.i(TAG, "SECURITY: $event | $phone | $details")
    }

    private fun cleanupOldData(context: Context, db: DatabaseHelper, retentionDays: Int) {
        try {
            val cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60 * 60 * 1000)
            val cutoffDate = DATE_FORMAT.format(Date(cutoff))

            db.execSQL("DELETE FROM user_activity_log WHERE created_at < ?", arrayOf(cutoffDate))
            db.execSQL("DELETE FROM sms_logs WHERE created_at < ?", arrayOf(cutoffDate))
            db.execSQL("DELETE FROM customer_ledger WHERE transaction_date < ?", arrayOf(cutoffDate))

            db.execSQL("UPDATE sales_transactions SET archived = 1 WHERE created_at < ? AND status = 'delivered'", arrayOf(cutoffDate))

            Log.d(TAG, "Cleanup completed, retention days: $retentionDays")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}")
        }
    }

    // ================================================================
    //  دوال تم إلغاؤها (كانت خاصة بالخادم المحلي)
    // ================================================================

    // تم إزالة فئة ApiServer (NanoHTTPD) بالكامل.
    // تم إزالة متغير server.
    // تم إزالة دالة startServer() التي كانت تشغل الخادم.
}

/**
 * Worker للنسخ الاحتياطي التلقائي (يُستخدم مع WorkManager).
 */
class BackupWorker(
    context: Context,
    params: androidx.work.WorkerParameters
) : androidx.work.CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = DatabaseHelper(applicationContext)
            val path = db.backupDatabase()
            Log.d("BackupWorker", "Auto backup completed: $path")
            db.close()
            Result.success()
        } catch (e: Exception) {
            Log.e("BackupWorker", "Backup failed: ${e.message}", e)
            Result.retry()
        }
    }
}
