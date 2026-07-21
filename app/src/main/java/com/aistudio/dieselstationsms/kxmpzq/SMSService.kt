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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════
 * SMSService – خدمة الخلفية لإدارة الرسائل النصية والمهام
 * الإصدار النهائي 5.0.0 – Offline First Architecture
 * ═══════════════════════════════════════════════════════════════
 *
 * تم تحديث هذه الخدمة لإلغاء الاعتماد على الخادم المحلي (NanoHTTPD) نهائياً.
 * الآن تعمل كخدمة خلفية نقية تدير:
 * 1. إرسال الرسائل النصية (SMS) مع دعم القائمة البيضاء والدفعات.
 * 2. النسخ الاحتياطي التلقائي عبر WorkManager.
 * 3. العمليات المجدولة في الخلفية (تنظيف السجلات، تحسين قاعدة البيانات).
 * 4. واجهات للذكاء الاصطناعي (AI) مع آلية Fallback بين المزودين.
 * 5. إرسال تذكيرات الديون المستحقة للعملاء.
 *
 * جميع عمليات قاعدة البيانات أصبحت تتم عبر AndroidInterface في MainActivity
 * عبر جسر JavaScript ←→ Kotlin الموحد.
 *
 * ═══════════════════════════════════════════════════════════════
 * @version 5.0.0 - Offline First Architecture
 * @since 2026-07-21
 * ═══════════════════════════════════════════════════════════════
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

        // ==================== إشعارات الأحداث ====================
        const val ACTION_SMS_SENT = "com.aistudio.dieselstationsms.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.aistudio.dieselstationsms.SMS_DELIVERED"
        const val ACTION_BACKUP_COMPLETED = "com.aistudio.dieselstationsms.BACKUP_COMPLETED"
        const val ACTION_SERVICE_STATUS_CHANGED = "com.aistudio.dieselstationsms.SERVICE_STATUS_CHANGED"
    }

    // ==================== Scope للعمليات غير المتزامنة ====================
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDestroyed = AtomicBoolean(false)

    // ==================== مفاتيح APIs للذكاء الاصطناعي ====================
    private val geminiApiKey: String by lazy { BuildConfig.GEMINI_API_KEY }
    private val deepseekApiKey: String by lazy { BuildConfig.DEEPSEEK_API_KEY }
    private val grokApiKey: String by lazy { BuildConfig.GROK_API_KEY }
    private val kimiApiKey: String by lazy { BuildConfig.KIMI_API_KEY }
    private val chatgptApiKey: String by lazy { BuildConfig.CHATGPT_API_KEY }

    // ==================== دورة حياة الخدمة ====================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SMSService onCreate - Offline First Mode")
        createNotificationChannel()
        isDestroyed.set(false)

        serviceScope.launch {
            try {
                startForegroundService()
                scheduleAutoBackup()
                Log.d(TAG, "Service initialization completed successfully (No Server Mode)")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal Error in service initialization: ${e.message}", e)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SMSService onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        isDestroyed.set(true)
        try {
            serviceScope.cancel()
            WorkManager.getInstance(this).cancelUniqueWork(BACKUP_WORK_NAME)
            Log.d(TAG, "Service destroyed and resources cleaned (No Server Mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction: ${e.message}", e)
        }
        super.onDestroy()
    }

    // ==================== إدارة الإشعارات ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Station SMS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة مراقبة الرسائل النصية والنسخ الاحتياطي"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * بناء الإشعار الأساسي للخدمة العاملة في المقدمة (Foreground Service)
     */
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
            .setContentText("النظام يعمل في وضع Offline")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * تحديث نص الإشعار بشكل ديناميكي
     */
    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * بدء الخدمة في المقدمة (Foreground Service)
     * ملاحظة: تم إزالة جميع الإشارات إلى الخادم المحلي والمنفذ 8080
     */
    private fun startForegroundService() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started (Offline Mode - No Server)")
    }

    // ==================== النسخ الاحتياطي التلقائي ====================

    /**
     * جدولة عملية النسخ الاحتياطي التلقائي عبر WorkManager
     * تعمل كل 24 ساعة مع مرونة ساعة واحدة
     */
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
            updateNotification("النسخ الاحتياطي التلقائي مُفعّل")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule auto backup: ${e.message}", e)
            updateNotification("فشل في جدولة النسخ الاحتياطي")
        }
    }

    // ==================== الذكاء الاصطناعي (AI) ====================

    /**
     * استدعاء API الذكاء الاصطناعي مع آلية Fallback بين المزودين
     *
     * @param prompt نص الطلب المُرسل للنموذج
     * @param db مثيل DatabaseHelper للوصول إلى السجلات
     * @return نص الرد أو رسالة خطأ
     */
    fun callAIWithFallback(prompt: String, db: DatabaseHelper): String {
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
                    Log.d(TAG, "AI response from $provider successful")
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Provider $provider failed: ${e.message}")
            }
        }
        return "جميع محاولات الاتصال بالذكاء الاصطناعي فشلت. يرجى التحقق من المفاتيح."
    }

    // ---------- Gemini API ----------
    private fun callGeminiAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = java.net.URL(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
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
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            parseAIResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini error: ${e.message}", e)
            null
        }
    }

    // ---------- DeepSeek API ----------
    private fun callDeepSeekAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = java.net.URL("https://api.deepseek.com/v1/chat/completions")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("model", "deepseek-chat")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 1024)
                put("temperature", 0.7)
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            val json = JSONObject(response)
            json.getJSONArray("choices")
                ?.getJSONObject(0)
                ?.getJSONObject("message")
                ?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek error: ${e.message}", e)
            null
        }
    }

    // ---------- Grok API ----------
    private fun callGrokAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = java.net.URL("https://api.x.ai/v1/chat/completions")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("model", "grok-beta")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 1024)
                put("temperature", 0.7)
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            val json = JSONObject(response)
            json.getJSONArray("choices")
                ?.getJSONObject(0)
                ?.getJSONObject("message")
                ?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "Grok error: ${e.message}", e)
            null
        }
    }

    // ---------- Kimi API ----------
    private fun callKimiAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = java.net.URL("https://api.moonshot.cn/v1/chat/completions")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("model", "moonshot-v1-8k")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 1024)
                put("temperature", 0.7)
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            val json = JSONObject(response)
            json.getJSONArray("choices")
                ?.getJSONObject(0)
                ?.getJSONObject("message")
                ?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "Kimi error: ${e.message}", e)
            null
        }
    }

    // ---------- ChatGPT API ----------
    private fun callChatGPTAPI(prompt: String, apiKey: String): String? {
        return try {
            val url = java.net.URL("https://api.openai.com/v1/chat/completions")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestBody = JSONObject().apply {
                put("model", "gpt-3.5-turbo")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 1024)
                put("temperature", 0.7)
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            val json = JSONObject(response)
            json.getJSONArray("choices")
                ?.getJSONObject(0)
                ?.getJSONObject("message")
                ?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "ChatGPT error: ${e.message}", e)
            null
        }
    }

    /**
     * تحليل رد API Gemini (تنسيق مختلف عن بقية المزودين)
     */
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

    // ==================== إدارة الرسائل النصية (SMS) ====================

    /**
     * التحقق مما إذا كانت خدمة SMS مُفعّلة في الإعدادات
     */
    private fun isSmsEnabled(db: DatabaseHelper): Boolean {
        return db.getSetting("sms_enabled") != "0"
    }

    /**
     * التحقق مما إذا كان الرقم مسموحاً له باستلام الرسائل (القائمة البيضاء)
     */
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

    /**
     * إرسال رسالة نصية (SMS) مع التحقق من القائمة البيضاء والتنسيق
     *
     * @param db مثيل DatabaseHelper
     * @param phone رقم الهاتف المستقبل
     * @param message نص الرسالة
     * @param type نوع الرسالة (للتسجيل في السجلات)
     * @return true إذا تم الإرسال بنجاح
     */
    fun sendSMS(db: DatabaseHelper, phone: String, message: String, type: String): Boolean {
        if (!isSmsAllowed(phone, db)) {
            db.logSms(phone, message, type, "blocked: not in whitelist")
            Log.w(TAG, "SMS blocked: $phone not in whitelist")
            return false
        }

        if (!phone.matches(Regex(PHONE_REGEX))) {
            db.logSms(phone, message, type, "failed: invalid number")
            Log.w(TAG, "SMS failed: invalid phone number format: $phone")
            return false
        }

        if (message.length > MAX_SMS_LENGTH) {
            db.logSms(phone, message, type, "failed: too long (${message.length} chars)")
            Log.w(TAG, "SMS failed: message too long (${message.length} > $MAX_SMS_LENGTH)")
            return false
        }

        return try {
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(phone, null, message, null, null)
            db.logSms(phone, message, type, "sent")
            Log.d(TAG, "SMS sent successfully to $phone")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}", e)
            db.logSms(phone, message, type, "failed: ${e.message}")
            false
        }
    }

    /**
     * إرسال رسائل نصية متعددة مع تأخير بين كل رسالة (لتجنب الحظر)
     */
    fun sendBulkSMS(db: DatabaseHelper, recipients: List<Pair<String, String>>, type: String) {
        serviceScope.launch {
            var sentCount = 0
            var failedCount = 0

            for ((phone, message) in recipients) {
                if (isDestroyed.get()) {
                    Log.w(TAG, "Bulk SMS interrupted: service destroyed")
                    break
                }

                val success = sendSMS(db, phone, message, type)
                if (success) sentCount++ else failedCount++

                // تأخير بين الرسائل لتجنب حظر المشغل
                delay(SMS_DELAY_MS)
            }

            Log.d(TAG, "Bulk SMS completed: $sentCount sent, $failedCount failed")
            updateNotification("تم إرسال $sentCount رسالة، فشل $failedCount")
        }
    }

    /**
     * إرسال رسائل تذكير بالديون المستحقة
     * يُستخدم من قبل AndroidInterface عند طلب المستخدم
     */
    fun sendOverdueReminders(db: DatabaseHelper) {
        serviceScope.launch {
            try {
                // استخدام الدالة الموجودة في DatabaseHelper: getOverduePayments()
                val overdueCustomers = db.getOverduePayments()
                val recipients = mutableListOf<Pair<String, String>>()

                for (i in 0 until minOf(overdueCustomers.length(), MAX_OVERDUE_SMS)) {
                    val customer = overdueCustomers.getJSONObject(i)
                    val phone = customer.optString("customer_phone", "")
                    val name = customer.optString("customer_name", "عميل")
                    val dueAmount = customer.optDouble("remaining_amount", 0.0)

                    if (phone.isNotBlank() && isSmsAllowed(phone, db)) {
                        val message = """
                            مرحباً $name،
                            تذكير: لديك دين مستحق بمبلغ ${dueAmount.toInt()} ريال.
                            يرجى التسديد في أقرب وقت.
                            شكراً - محطة أبو أحمد
                        """.trimIndent()
                        recipients.add(phone to message)
                    }
                }

                if (recipients.isNotEmpty()) {
                    sendBulkSMS(db, recipients, "overdue_reminder")
                } else {
                    Log.d(TAG, "No overdue reminders to send")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending overdue reminders: ${e.message}", e)
            }
        }
    }

    // ==================== العمليات المجدولة (Scheduled Tasks) ====================

    /**
     * تنفيذ عملية في الخلفية مع إشعار بالنتيجة
     * يمكن استدعاؤها من AndroidInterface
     */
    fun executeBackgroundTask(taskName: String, db: DatabaseHelper, callback: (Boolean, String) -> Unit) {
        serviceScope.launch {
            try {
                val result = when (taskName) {
                    "cleanup_old_logs" -> {
                        // استخدام الدالة الموجودة: cleanupOldData مع فترة الاحتفاظ
                        val retentionDays = db.getRetentionDays()
                        db.cleanupOldData(retentionDays)
                        Pair(true, "تم تنظيف السجلات القديمة (احتفاظ بـ $retentionDays يوم)")
                    }
                    "optimize_database" -> {
                        // استخدام الدالة الموجودة: vacuumDatabase()
                        db.vacuumDatabase()
                        Pair(true, "تم تحسين قاعدة البيانات بنجاح")
                    }
                    "sync_pending" -> {
                        // للاستخدام المستقبلي مع المزامنة
                        Pair(true, "لا توجد بيانات معلقة للمزامنة")
                    }
                    else -> Pair(false, "مهمة غير معروفة: $taskName")
                }
                callback(result.first, result.second)
            } catch (e: Exception) {
                Log.e(TAG, "Background task failed: ${e.message}", e)
                callback(false, "فشل تنفيذ المهمة: ${e.message}")
            }
        }
    }

    // ==================== مساعدين (Helpers) ====================

    /**
     * الحصول على حالة الخدمة الحالية
     */
    fun getServiceStatus(): JSONObject {
        return JSONObject().apply {
            put("is_running", !isDestroyed.get())
            put("mode", "offline")
            put("server_enabled", false)
            put("backup_scheduled", true)
            put("timestamp", DATETIME_FORMAT.format(Date()))
        }
    }
}
