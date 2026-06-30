package com.aistudio.dieselstationsms.kxmpzq

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SMSService - محسّن ومصحح بالكامل
 *
 * التحسينات الإضافية:
 * - إضافة علامة isDestroyed لمنع بدء الخادم بعد التدمير
 * - جعل send_overdue_sms متزامنة مع حد أقصى 20 رسالة لتجنب حجب الطلب
 * - إضافة معامل limit إلى export_data مع تحديد افتراضي 1000 سجل لكل جدول
 * - تحسين معالجة الأخطاء في جميع الـ handlers
 */
class SMSService : Service() {

    companion object {
        private const val TAG = "SMSService"
        private const val SERVER_PORT = 8080
        private const val MAX_PORT_RETRIES = 5
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_service_channel"
        private const val BACKUP_WORK_NAME = "auto_backup_work"
        private const val SMS_DELAY_MS = 1000L
        private const val MAX_SMS_LENGTH = 1600
        private const val PHONE_REGEX = "^[+]?[0-9]{10,14}$"
        private const val MAX_OVERDUE_SMS = 20   // حد أقصى للرسائل لتجنب حجب الطلب
        private const val DEFAULT_EXPORT_LIMIT = 1000
    }

    private var server: ApiServer? = null
    private var currentPort = SERVER_PORT
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDestroyed = AtomicBoolean(false)

    // Rate limiting
    private val smsTimestamps = mutableListOf<Long>()
    private val maxSmsPerMinute = 30

    private val geminiApiKey: String
        get() {
            val key = BuildConfig.GEMINI_API_KEY ?: ""
            return if (key == "YOUR_GEMINI_API_KEY_HERE") "" else key
        }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        isDestroyed.set(false)

        serviceScope.launch {
            try {
                setupNotificationChannel()
                startForegroundService()
                startServer()
                scheduleAutoBackup()
                Log.d(TAG, "Service initialization completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal Error in service initialization: ${e.message}", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, startId=$startId")
        if (!isDestroyed.get() && (server == null || !server!!.isAlive)) {
            serviceScope.launch { startServer() }
        }
        return START_STICKY
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خدمة المحطة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "قناة إشعارات خدمة الخادم المحلي لمحطة أبو أحمد"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText("الخادم المحلي يعمل على المنفذ $currentPort...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started with notification")
    }

    private fun startServer() {
        if (isDestroyed.get()) {
            Log.w(TAG, "Service is destroyed, not starting server")
            return
        }
        var retries = 0
        while (retries < MAX_PORT_RETRIES && !isDestroyed.get()) {
            try {
                server?.stop()
                server = ApiServer(currentPort)
                server?.start()
                Log.d(TAG, "Server started at port $currentPort")
                updateNotification("الخادم المحلي يعمل على المنفذ $currentPort")
                return
            } catch (e: IOException) {
                Log.w(TAG, "Port $currentPort busy, trying next...")
                currentPort++
                retries++
            }
        }
        Log.e(TAG, "Failed to start server after $MAX_PORT_RETRIES attempts")
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

    override fun onDestroy() {
        isDestroyed.set(true)
        try {
            server?.stop()
            server = null
            serviceScope.cancel()
            WorkManager.getInstance(this).cancelUniqueWork(BACKUP_WORK_NAME)
            Log.d(TAG, "Service destroyed and resources cleaned")
        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction: ${e.message}", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ==================== GEMINI AI ====================

    private fun callGeminiAPI(prompt: String): String {
        if (geminiApiKey.isEmpty()) {
            return "مفتاح Gemini API غير مُكوّن. يرجى إضافة GEMINI_API_KEY في ملف .env"
        }

        val sanitizedPrompt = sanitizePrompt(prompt)

        return try {
            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$geminiApiKey"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "AbuAhmed-Station/2.1")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", sanitizedPrompt)
                            })
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

            parseGeminiResponse(response)
        } catch (e: Exception) {
            Log.e("Gemini", "Error: ${e.message}", e)
            "عذراً، حدث خطأ في الاتصال بـ Gemini: ${e.message}"
        }
    }

    private fun sanitizePrompt(prompt: String): String {
        return prompt.replace(Regex("[<>\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]"), "")
    }

    private fun parseGeminiResponse(response: String): String {
        return try {
            val jsonResponse = JSONObject(response)
            if (jsonResponse.has("candidates")) {
                val candidates = jsonResponse.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    if (parts.length() > 0) {
                        parts.getJSONObject(0).getString("text")
                    } else "لم يتم الحصول على رد"
                } else "لم يتم الحصول على رد"
            } else {
                jsonResponse.optString("error", "خطأ في الاتصال بـ Gemini")
            }
        } catch (e: Exception) {
            Log.e("Gemini", "Error parsing response: ${e.message}")
            "خطأ في معالجة الرد"
        }
    }

    private fun generateAIInsight(stats: JSONObject, sales: JSONArray): String {
        if (geminiApiKey.isEmpty()) {
            return "ميزة الذكاء الاصطناعي غير مفعلة. أضف مفتاح Gemini API في الإعدادات."
        }
        val stock = stats.optDouble("total_remaining", 0.0)
        val due = stats.optDouble("total_due", 0.0)
        val todaySales = stats.optDouble("total_sales", 0.0)
        val customers = stats.optInt("total_customers", 0)

        val prompt = """
            أنت مساعد ذكي لمحطة أبو أحمد لمشتقات الديزل في اليمن.
            قدم تحليلاً مختصراً ومهنياً باللغة العربية للبيانات التالية:

            - المخزون المتبقي: ${stock.toInt()} لتر
            - الديون المستحقة: ${due.toInt()} ريال
            - مبيعات اليوم: ${todaySales.toInt()} ريال
            - عدد العملاء النشطين: $customers
            - عدد أيام المبيعات المتاحة: ${sales.length()}

            قدم:
            1. تقييم سريع للوضع
            2. توصية واحدة عملية
            3. تحذير إن وجد

            الرد يجب أن يكون قصيراً (3-4 أسطر فقط).
        """.trimIndent()

        return callGeminiAPI(prompt)
    }

    private fun generateSalesForecast(sales: JSONArray): String {
        if (geminiApiKey.isEmpty()) return "ميزة التوقع غير مفعلة."
        if (sales.length() < 3) return "لا توجد بيانات كافية للتوقع"

        val prompt = """
            أنت محلل بيانات لمحطة وقود.
            بناءً على بيانات المبيعات اليومية التالية، قدم توقعاً للمبيعات القادمة:

            ${(0 until minOf(sales.length(), 30)).joinToString("\n") { i ->
                val s = sales.getJSONObject(i)
                "- ${s.optString("date")}: ${s.optDouble("total_qty", 0.0).toInt()} لتر"
            }}

            قدم توقعاً مختصراً للأسبوع القادم.
        """.trimIndent()

        return callGeminiAPI(prompt)
    }

    private fun analyzeCustomerBehavior(customerData: JSONObject): String {
        if (geminiApiKey.isEmpty()) return "ميزة التحليل غير مفعلة."

        val prompt = """
            أنت مستشار مالي لمحطة وقود.
            قم بتحليل سلوك العميل التالي وقدم نصيحة:

            - الاسم: ${customerData.optString("full_name", "غير معروف")}
            - الرصيد المستحق: ${customerData.optDouble("current_balance", 0.0).toInt()} ريال
            - نقاط الولاء: ${customerData.optInt("loyalty_points", 0)}
            - مستوى VIP: ${customerData.optInt("vip_level", 0)}

            قدم نصيحة واحدة مختصرة.
        """.trimIndent()

        return callGeminiAPI(prompt)
    }

    // ==================== API SERVER ====================

    private inner class ApiServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            val method = session.method ?: Method.GET
            val headers = mutableMapOf<String, String>()
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
            headers["Access-Control-Allow-Headers"] = "Content-Type"
            headers["Content-Type"] = "application/json; charset=utf-8"
            headers["X-Content-Type-Options"] = "nosniff"
            headers["X-Frame-Options"] = "DENY"

            if (Method.OPTIONS == method) {
                val res = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                headers.forEach { (k, v) -> res.addHeader(k, v) }
                return res
            }

            val db = DatabaseHelper(this@SMSService)
            val responseJson = JSONObject()

            try {
                if (uri.startsWith("/api")) {
                    val params = session.parameters ?: mutableMapOf()
                    val action = params["action"]?.firstOrNull() ?: ""

                    when (action) {
                        "login" -> handleLogin(db, params, responseJson)
                        "biometric_login" -> handleBiometricLogin(db, params, responseJson)
                        "get_customers" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getCustomers())
                        }
                        "get_refills" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getRefills())
                        }
                        "execute_sale" -> handleExecuteSale(db, params, responseJson)
                        "make_payment" -> handleMakePayment(db, params, responseJson)
                        "get_dashboard" -> handleGetDashboard(db, responseJson)
                        "get_ai_insight" -> handleGetAiInsight(db, responseJson)
                        "get_sales_forecast" -> handleGetSalesForecast(db, responseJson)
                        "analyze_customer" -> handleAnalyzeCustomer(db, params, responseJson)
                        "ai_chat" -> handleAiChat(db, params, responseJson)
                        "send_sms" -> handleSendSms(db, params, responseJson)
                        "export_data" -> handleExportData(db, params, responseJson)
                        "search_transactions" -> {
                            val q = params["query"]?.firstOrNull() ?: ""
                            responseJson.put("success", true)
                            responseJson.put("data", db.searchTransactions(q))
                        }
                        "get_customer_report" -> {
                            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
                            responseJson.put("success", true)
                            responseJson.put("data", db.getCustomerReport(customerId))
                        }
                        "send_overdue_sms" -> handleSendOverdueSms(db, responseJson)
                        "get_daily_sales" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getDailySales())
                        }
                        "get_monthly_sales" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getMonthlySales())
                        }
                        "get_sms_logs" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getSmsLogs())
                        }
                        "get_activity_logs" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getActivityLogs())
                        }
                        "set_setting" -> handleSetSetting(db, params, responseJson)
                        "get_setting" -> {
                            val key = params["key"]?.firstOrNull() ?: ""
                            responseJson.put("success", true)
                            responseJson.put("value", db.getSetting(key))
                        }
                        "get_eod_report" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getEodReport())
                        }
                        "get_inventory_alerts" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getInventoryAlerts())
                        }
                        "mark_alert_read" -> {
                            val alertId = params["alert_id"]?.firstOrNull()?.toInt() ?: 0
                            val success = db.markAlertRead(alertId)
                            responseJson.put("success", success)
                        }
                        "get_loyalty_history" -> {
                            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
                            responseJson.put("success", true)
                            responseJson.put("data", db.getLoyaltyHistory(customerId))
                        }
                        "redeem_loyalty" -> handleRedeemLoyalty(db, params, responseJson)
                        "log_activity" -> {
                            val actionType = params["action_type"]?.firstOrNull() ?: ""
                            val details = params["details"]?.firstOrNull() ?: ""
                            val operator = params["operator"]?.firstOrNull() ?: "System"
                            db.logActivity(operator, actionType, details)
                            responseJson.put("success", true)
                        }
                        "save_ai_chat" -> {
                            val sessionId = params["session_id"]?.firstOrNull() ?: "default"
                            val role = params["role"]?.firstOrNull() ?: "user"
                            val message = params["message"]?.firstOrNull() ?: ""
                            db.saveAiMessage(sessionId, role, message)
                            responseJson.put("success", true)
                        }
                        "get_ai_chat" -> {
                            val sessionId = params["session_id"]?.firstOrNull() ?: "default"
                            responseJson.put("success", true)
                            responseJson.put("data", db.getAiChatHistory(sessionId))
                        }
                        else -> {
                            responseJson.put("success", false)
                            responseJson.put("error", "إجراء غير معروف: $action")
                        }
                    }
                } else {
                    return serveStaticFile(uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                responseJson.put("success", false)
                responseJson.put("error", "Internal error: ${e.message}")
            }

            val res = newFixedLengthResponse(
                Response.Status.OK, "application/json", responseJson.toString()
            )
            headers.forEach { (k, v) -> res.addHeader(k, v) }
            return res
        }

        // ==================== Handlers ====================

        private fun handleLogin(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            val user = params["username"]?.firstOrNull() ?: ""
            val pass = params["password"]?.firstOrNull() ?: ""
            val auth = db.authenticateUser(user, pass)
            if (auth != null) {
                responseJson.put("success", true)
                responseJson.put("user", auth)
                responseJson.put("token", java.util.UUID.randomUUID().toString())
            } else {
                responseJson.put("success", false)
                responseJson.put("error", "بيانات خاطئة")
            }
        }

        private fun handleBiometricLogin(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            val user = params["username"]?.firstOrNull() ?: ""
            val userData = db.getUserByUsername(user)
            if (userData != null && userData.getInt("biometric_enabled") == 1 && userData.getInt("active") == 1) {
                responseJson.put("success", true)
                responseJson.put("user", userData)
                responseJson.put("token", java.util.UUID.randomUUID().toString())
            } else {
                responseJson.put("success", false)
                responseJson.put("error", "المصادقة البيومترية غير مفعلة")
            }
        }

        private fun handleExecuteSale(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
            val refillId = params["refill_id"]?.firstOrNull()?.toInt() ?: 0
            val qty = params["quantity_liters"]?.firstOrNull()?.toDouble() ?: 0.0
            val price = params["unit_price"]?.firstOrNull()?.toDouble() ?: 0.0
            val paid = params["paid_amount"]?.firstOrNull()?.toDouble() ?: 0.0
            val paymentMethod = params["payment_method"]?.firstOrNull() ?: "cash"
            val dueDate = params["due_date"]?.firstOrNull() ?: ""
            val paymentType = params["payment_type"]?.firstOrNull() ?: "نقداً"
            val operator = params["operator"]?.firstOrNull() ?: "System"

            if (customerId <= 0 || refillId <= 0 || qty <= 0 || price <= 0) {
                responseJson.put("success", false)
                responseJson.put("error", "بيانات غير صالحة")
                return
            }

            val total = qty * price
            val due = total - paid

            val refill = db.getRefill(refillId)
            if (refill == null || refill.getDouble("remaining_quantity") < qty) {
                responseJson.put("success", false)
                responseJson.put("error", "الكمية غير متوفرة")
                return
            }

            db.updateRefillQty(refillId, qty, operator)
            val tid = db.insertTransaction(
                customerId, refillId, qty, price, paid, due,
                paymentMethod, dueDate, paymentType, operator
            )
            responseJson.put("success", true)
            responseJson.put("transaction_id", tid)
            responseJson.put("invoice_number", db.getTransactionById(tid)?.getString("invoice_number"))
            responseJson.put("message", "تم البيع بنجاح")

            if (due > 0.0) {
                val customer = db.getCustomer(customerId)
                val phone = customer?.optString("phone")
                if (!phone.isNullOrEmpty()) {
                    val msg = "تذكير: لديك مبلغ مستحق $due ريال قبل $dueDate"
                    sendSMS(db, phone, msg, "new_sale_due")
                }
            }
        }

        private fun handleMakePayment(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
            val amount = params["amount"]?.firstOrNull()?.toDouble() ?: 0.0
            val operator = params["operator"]?.firstOrNull() ?: "System"

            if (customerId <= 0 || amount <= 0) {
                responseJson.put("success", false)
                responseJson.put("error", "بيانات غير صالحة")
                return
            }

            db.processPayment(customerId, amount, operator)
            responseJson.put("success", true)
            responseJson.put("message", "تم التسديد")
        }

        private fun handleGetDashboard(db: DatabaseHelper, responseJson: JSONObject) {
            val stats = db.getDashboardStats()
            responseJson.put("success", true)
            responseJson.put("data", stats)

            if (db.getSetting("ai_enabled") == "1" && geminiApiKey.isNotEmpty()) {
                val sales = db.getDailySales()
                val aiInsight = generateAIInsight(stats, sales)
                responseJson.put("ai_insight", aiInsight)
            }
        }

        private fun handleGetAiInsight(db: DatabaseHelper, responseJson: JSONObject) {
            if (geminiApiKey.isEmpty()) {
                responseJson.put("success", false)
                responseJson.put("error", "مفتاح Gemini API غير مُكوّن")
                return
            }
            val stats = db.getDashboardStats()
            val sales = db.getDailySales()
            responseJson.put("success", true)
            responseJson.put("insight", generateAIInsight(stats, sales))
        }

        private fun handleGetSalesForecast(db: DatabaseHelper, responseJson: JSONObject) {
            if (geminiApiKey.isEmpty()) {
                responseJson.put("success", false)
                responseJson.put("error", "مفتاح Gemini API غير مُكوّن")
                return
            }
            val sales = db.getDailySales()
            responseJson.put("success", true)
            responseJson.put("forecast", generateSalesForecast(sales))
        }

        private fun handleAnalyzeCustomer(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            if (geminiApiKey.isEmpty()) {
                responseJson.put("success", false)
                responseJson.put("error", "مفتاح Gemini API غير مُكوّن")
                return
            }
            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
            val customer = db.getCustomer(customerId)
            if (customer != null) {
                responseJson.put("success", true)
                responseJson.put("analysis", analyzeCustomerBehavior(customer))
            } else {
                responseJson.put("success", false)
                responseJson.put("error", "العميل غير موجود")
            }
        }

        private fun handleAiChat(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            val message = params["message"]?.firstOrNull() ?: ""
            val sessionId = params["session_id"]?.firstOrNull() ?: "default"

            if (message.isBlank()) {
                responseJson.put("success", false)
                responseJson.put("error", "الرسالة فارغة")
                return
            }

            val history = db.getAiChatHistory(sessionId)
            val context = if (history.length() > 0) {
                "سياق المحادثة السابقة: ${history.toString().take(500)}\n\n"
            } else ""

            val prompt = """
                $context
                أنت مساعد ذكي لمحطة أبو أحمد لمشتقات الديزل في اليمن.
                أجب على السؤال التالي باختصار ومهنية:

                السؤال: $message

                ملاحظات:
                - سعر اللتر الحالي: 500 ريال
                - العملة: الريال اليمني (ريال)
                - الرد يجب أن يكون بالعربية
                - اجعل الرد مختصراً ومفيداً
            """.trimIndent()

            val reply = callGeminiAPI(prompt)
            db.saveAiMessage(sessionId, "assistant", reply)

            responseJson.put("success", true)
            responseJson.put("reply", reply)
        }

        private fun handleSendSms(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            val phone = params["phone"]?.firstOrNull() ?: ""
            val msg = params["message"]?.firstOrNull() ?: ""

            if (!isValidPhone(phone)) {
                responseJson.put("success", false)
                responseJson.put("error", "رقم هاتف غير صالح")
                return
            }

            if (msg.isBlank()) {
                responseJson.put("success", false)
                responseJson.put("error", "الرسالة فارغة")
                return
            }

            val sent = sendSMS(db, phone, msg, "manual")
            responseJson.put("success", sent)
            responseJson.put("message", if (sent) "تم الإرسال" else "فشل الإرسال")
        }

        /**
         * تصدير البيانات مع حد أقصى للصفوف لتجنب OOM
         */
        private fun handleExportData(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            // قراءة معامل limit (اختياري)
            val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: DEFAULT_EXPORT_LIMIT
            val data = JSONObject()

            // تصدير العملاء مع حد
            val customers = db.getCustomers()
            val limitedCustomers = if (customers.length() > limit) {
                val arr = JSONArray()
                for (i in 0 until limit) arr.put(customers.get(i))
                arr
            } else customers
            data.put("customers", limitedCustomers)

            // تصدير التعبئات مع حد
            val refills = db.getRefills()
            val limitedRefills = if (refills.length() > limit) {
                val arr = JSONArray()
                for (i in 0 until limit) arr.put(refills.get(i))
                arr
            } else refills
            data.put("refills", limitedRefills)

            // تصدير المعاملات (الأحدث أولاً) مع حد
            val transactions = db.getTransactions(limit = limit, offset = 0)
            data.put("transactions", transactions)

            // تصدير سجلات SMS (الأحدث) مع حد
            val smsLogs = db.getSmsLogs()
            val limitedSms = if (smsLogs.length() > limit) {
                val arr = JSONArray()
                for (i in 0 until limit) arr.put(smsLogs.get(i))
                arr
            } else smsLogs
            data.put("sms_logs", limitedSms)

            // تصدير سجلات النشاط (الأحدث) مع حد
            val activityLogs = db.getActivityLogs()
            val limitedActivity = if (activityLogs.length() > limit) {
                val arr = JSONArray()
                for (i in 0 until limit) arr.put(activityLogs.get(i))
                arr
            } else activityLogs
            data.put("activity_logs", limitedActivity)

            // تصدير التنبيهات
            data.put("inventory_alerts", db.getInventoryAlerts())

            // تصدير المدفوعات - نستخدم استعلام مباشر مع حد
            val payments = JSONArray()
            val dbRead = db.readableDatabase
            val cursor = dbRead.rawQuery(
                "SELECT * FROM payments ORDER BY id DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val o = JSONObject()
                    o.put("payment_id", it.getInt(it.getColumnIndexOrThrow("id")))
                    o.put("customer_id", it.getInt(it.getColumnIndexOrThrow("customer_id")))
                    o.put("amount", it.getDouble(it.getColumnIndexOrThrow("amount")))
                    o.put("method", it.getString(it.getColumnIndexOrThrow("method")))
                    o.put("date", it.getString(it.getColumnIndexOrThrow("date")))
                    payments.put(o)
                }
            }
            data.put("payments", payments)

            responseJson.put("success", true)
            responseJson.put("data", data)
            responseJson.put("limit_applied", limit)
        }

        /**
         * إرسال رسائل للمعاملات المتأخرة - متزامن مع حد أقصى
         */
        private fun handleSendOverdueSms(db: DatabaseHelper, responseJson: JSONObject) {
            val overdue = db.getOverdueTransactions()
            var sentCount = 0
            val failedList = JSONArray()
            val total = overdue.length()

            // تحديد عدد الرسائل التي سيتم إرسالها (حد أقصى)
            val toSend = minOf(total, MAX_OVERDUE_SMS)

            for (i in 0 until toSend) {
                val t = overdue.getJSONObject(i)
                val phone = t.optString("customer_phone", "")
                val name = t.optString("customer_name", "عميلنا العزيز")
                val due = t.optDouble("due", 0.0)
                val dueDate = t.optString("due_date", "")

                if (isValidPhone(phone)) {
                    val msg = "عزيزي $name، نحيطكم علماً بوجود مبلغ مستحق قدره $due ريال تجاوز تاريخ الاستحقاق ($dueDate). يرجى المبادرة بالتسديد. محطة أبو أحمد."
                    if (sendSMS(db, phone, msg, "overdue_reminder")) {
                        sentCount++
                    } else {
                        failedList.put(name)
                    }
                    // تأخير قصير لتجنب حظر النظام
                    Thread.sleep(SMS_DELAY_MS)
                } else {
                    failedList.put(name)
                }
            }

            responseJson.put("success", true)
            responseJson.put("message", "تم إرسال $sentCount رسالة من $toSend (إجمالي $total). فشل: ${failedList.length()}")
            responseJson.put("failed_customers", failedList)
            if (total > MAX_OVERDUE_SMS) {
                responseJson.put("warning", "يوجد $total معاملة متأخرة، تم إرسال $MAX_OVERDUE_SMS فقط. يرجى تكرار العملية لبقية العملاء.")
            }
        }

        private fun handleRedeemLoyalty(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
            val points = params["points"]?.firstOrNull()?.toInt() ?: 0
            val desc = params["description"]?.firstOrNull() ?: ""

            if (customerId <= 0 || points <= 0) {
                responseJson.put("success", false)
                responseJson.put("error", "بيانات غير صالحة")
                return
            }

            val success = db.redeemLoyaltyPoints(customerId, points, desc)
            responseJson.put("success", success)
            responseJson.put("message", if (success) "تم استبدال النقاط" else "النقاط غير كافية أو العميل غير موجود")
        }

        private fun handleSetSetting(db: DatabaseHelper, params: Map<String, List<String>>, responseJson: JSONObject) {
            val key = params["key"]?.firstOrNull() ?: ""
            val value = params["value"]?.firstOrNull() ?: ""
            if (key.isNotEmpty()) {
                db.setSetting(key, value)
                responseJson.put("success", true)
                responseJson.put("message", "تم الحفظ")
            } else {
                responseJson.put("success", false)
                responseJson.put("error", "المفتاح مفقود")
            }
        }

        // ==================== Static Files ====================

        private fun serveStaticFile(uri: String): Response {
            return try {
                when {
                    uri == "/" || uri == "/index.html" -> {
                        val stream = assets.open("web_interface.html")
                        newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", stream)
                    }
                    uri == "/html5-qrcode.min.js" -> {
                        val stream = assets.open("html5-qrcode.min.js")
                        newChunkedResponse(Response.Status.OK, "application/javascript", stream)
                    }
                    else -> {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
                    }
                }
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }
    }

    // ==================== SMS Helpers ====================

    private fun isValidPhone(phone: String): Boolean {
        return phone.isNotBlank() && phone.matches(Regex(PHONE_REGEX))
    }

    private fun sendSMS(db: DatabaseHelper, phone: String, msg: String, type: String): Boolean {
        if (!isValidPhone(phone)) {
            Log.w(TAG, "Invalid phone number: $phone")
            db.logSms(phone, msg, type, "failed: invalid number")
            return false
        }
        if (msg.length > MAX_SMS_LENGTH) {
            Log.w(TAG, "SMS too long (${msg.length}), truncating")
            // يمكن تقسيم الرسائل ولكن تبسيطاً نختصرها
            db.logSms(phone, msg, type, "failed: too long")
            return false
        }
        // Rate limiting بسيط
        synchronized(smsTimestamps) {
            val now = System.currentTimeMillis()
            smsTimestamps.removeAll { now - it > 60000 }
            if (smsTimestamps.size >= maxSmsPerMinute) {
                Log.w(TAG, "Rate limit exceeded for SMS")
                db.logSms(phone, msg, type, "failed: rate limit")
                return false
            }
            smsTimestamps.add(now)
        }

        return try {
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(phone, null, msg, null, null)
            db.logSms(phone, msg, type, "sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}", e)
            db.logSms(phone, msg, type, "failed: ${e.message}")
            false
        }
    }
}
