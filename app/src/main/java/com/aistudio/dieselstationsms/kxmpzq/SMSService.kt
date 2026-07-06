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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val MAX_OVERDUE_SMS = 20
        private const val DEFAULT_EXPORT_LIMIT = 1000
    }

    private var server: ApiServer? = null
    private var currentPort = SERVER_PORT
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDestroyed = AtomicBoolean(false)

    // قراءة المفتاح من BuildConfig (من secrets)
    private val geminiApiKey: String by lazy {
        BuildConfig.GEMINI_API_KEY
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
        Log.d(TAG, "onStartCommand called")
        if (!isDestroyed.get() && (server == null || !server!!.isAlive)) {
            serviceScope.launch { startServer() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed.set(true)
        try {
            server?.stop()
            server = null
            serviceScope.cancel()
            WorkManager.getInstance(this).cancelUniqueWork(BACKUP_WORK_NAME)
            Log.d(TAG, "Service destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction", e)
        }
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
                description = "قناة إشعارات خدمة الخادم المحلي"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText("الخادم المحلي يعمل على المنفذ $currentPort")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started")
    }

    // ========== خادم الويب ==========
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
                return
            } catch (e: IOException) {
                Log.w(TAG, "Port $currentPort busy, trying next...")
                currentPort++
                retries++
            }
        }
        Log.e(TAG, "Failed to start server after $MAX_PORT_RETRIES attempts")
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
            Log.d(TAG, "Auto backup scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule auto backup", e)
        }
    }

    // ========== خادم API ==========
    private inner class ApiServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            val method = session.method ?: Method.GET

            // إضافة رؤوس CORS
            val headers = mutableMapOf<String, String>()
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
            headers["Access-Control-Allow-Headers"] = "Content-Type"
            headers["Content-Type"] = "application/json; charset=utf-8"

            if (Method.OPTIONS == method) {
                val res = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                headers.forEach { (k, v) -> res.addHeader(k, v) }
                return res
            }

            // خدمة الملفات الثابتة (web_interface.html)
            if (!uri.startsWith("/api")) {
                return serveStaticFile(uri)
            }

            // معالجة طلبات API
            val db = DatabaseHelper(this@SMSService)
            val responseJson = JSONObject()
            val params = session.parameters ?: mutableMapOf()
            val action = params["action"]?.firstOrNull() ?: ""

            try {
                when (action) {
                    "login" -> {
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
                    "biometric_login" -> {
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
                    "get_customers" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getCustomers())
                    }
                    "get_refills" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getRefills())
                    }
                    "execute_sale" -> {
                        val customerId = params["customer_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val refillId = params["refill_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val qty = params["quantity_liters"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val price = params["unit_price"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val paid = params["paid_amount"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val paymentType = params["payment_type"]?.firstOrNull() ?: "نقداً"
                        val dueDate = params["due_date"]?.firstOrNull() ?: ""
                        val operator = params["operator"]?.firstOrNull() ?: "System"

                        if (customerId <= 0 || refillId <= 0 || qty <= 0 || price <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val total = qty * price
                            val due = total - paid
                            val refill = db.getRefill(refillId)
                            if (refill == null || refill.getDouble("remaining_quantity") < qty) {
                                responseJson.put("success", false)
                                responseJson.put("error", "الكمية غير متوفرة")
                            } else {
                                db.updateRefillQty(refillId, qty, operator)
                                val tid = db.insertTransaction(
                                    customerId, refillId, qty, price, paid, due,
                                    "cash", dueDate, paymentType, operator
                                )
                                responseJson.put("success", true)
                                responseJson.put("transaction_id", tid)
                                responseJson.put("invoice_number", db.getTransactionById(tid)?.getString("invoice_number"))
                                responseJson.put("message", "تم البيع بنجاح")
                            }
                        }
                    }
                    "make_payment" -> {
                        val customerId = params["customer_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val amount = params["amount"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val operator = params["operator"]?.firstOrNull() ?: "System"
                        if (customerId <= 0 || amount <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            db.processPayment(customerId, amount, operator)
                            responseJson.put("success", true)
                            responseJson.put("message", "تم التسديد")
                        }
                    }
                    "get_dashboard" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", JSONArray().apply { put(db.getDashboardStats()) })
                    }
                    "get_daily_sales" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getDailySales())
                    }
                    "get_monthly_sales" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getMonthlySales())
                    }
                    "get_eod_report" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getEodReport())
                    }
                    "send_overdue_sms" -> {
                        val overdue = db.getOverdueTransactions()
                        var sent = 0
                        for (i in 0 until minOf(overdue.length(), MAX_OVERDUE_SMS)) {
                            val t = overdue.getJSONObject(i)
                            val phone = t.optString("customer_phone", "")
                            if (phone.isNotEmpty()) {
                                val msg = "عزيزي العميل، لديك مبلغ مستحق ${t.getDouble("due")} ريال. يرجى التسديد."
                                if (sendSMS(db, phone, msg, "overdue_reminder")) sent++
                                Thread.sleep(SMS_DELAY_MS)
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("message", "تم إرسال $sent رسالة تذكير")
                    }
                    "export_data" -> {
                        val data = db.exportAllData()
                        responseJson.put("success", true)
                        responseJson.put("data", data)
                    }
                    "get_sms_logs" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getSmsLogs())
                    }
                    "get_activity_logs" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getActivityLogs())
                    }
                    "get_customer_report" -> {
                        val customerId = params["customer_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        if (customerId > 0) {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getCustomerReport(customerId))
                        } else {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف العميل غير صالح")
                        }
                    }
                    "search_transactions" -> {
                        val query = params["query"]?.firstOrNull() ?: ""
                        responseJson.put("success", true)
                        responseJson.put("data", db.searchTransactions(query))
                    }
                    "get_inventory_alerts" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getInventoryAlerts())
                    }
                    "mark_alert_read" -> {
                        val alertId = params["alert_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val success = db.markAlertRead(alertId)
                        responseJson.put("success", success)
                    }
                    "get_loyalty_history" -> {
                        val customerId = params["customer_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        if (customerId > 0) {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getLoyaltyHistory(customerId))
                        } else {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف العميل غير صالح")
                        }
                    }
                    "redeem_loyalty" -> {
                        val customerId = params["customer_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val points = params["points"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val desc = params["description"]?.firstOrNull() ?: ""
                        if (customerId > 0 && points > 0) {
                            val success = db.redeemLoyaltyPoints(customerId, points, desc)
                            responseJson.put("success", success)
                            responseJson.put("message", if (success) "تم استبدال النقاط" else "النقاط غير كافية")
                        } else {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        }
                    }
                    "log_activity" -> {
                        val operator = params["operator"]?.firstOrNull() ?: "System"
                        val actionType = params["action_type"]?.firstOrNull() ?: ""
                        val details = params["details"]?.firstOrNull() ?: ""
                        db.logActivity(operator, actionType, details)
                        responseJson.put("success", true)
                    }
                    "ai_chat" -> {
                        val message = params["message"]?.firstOrNull() ?: ""
                        val sessionId = params["session_id"]?.firstOrNull() ?: "default"
                        if (geminiApiKey.isEmpty()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "مفتاح Gemini API غير مُكوّن")
                        } else {
                            val reply = callGeminiAPI(message, db, sessionId)
                            responseJson.put("success", true)
                            responseJson.put("reply", reply)
                        }
                    }
                    "get_ai_insight" -> {
                        if (geminiApiKey.isEmpty()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "مفتاح Gemini API غير مُكوّن")
                        } else {
                            val stats = db.getDashboardStats()
                            val sales = db.getDailySales()
                            val insight = generateAIInsight(stats, sales)
                            responseJson.put("success", true)
                            responseJson.put("insight", insight)
                        }
                    }
                    "get_sales_forecast" -> {
                        if (geminiApiKey.isEmpty()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "مفتاح Gemini API غير مُكوّن")
                        } else {
                            val sales = db.getDailySales()
                            val forecast = generateSalesForecast(sales)
                            responseJson.put("success", true)
                            responseJson.put("forecast", forecast)
                        }
                    }
                    "analyze_customer" -> {
                        val customerId = params["customer_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        if (geminiApiKey.isEmpty()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "مفتاح Gemini API غير مُكوّن")
                        } else if (customerId <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف العميل غير صالح")
                        } else {
                            val customer = db.getCustomer(customerId)
                            if (customer != null) {
                                val analysis = analyzeCustomerBehavior(customer)
                                responseJson.put("success", true)
                                responseJson.put("analysis", analysis)
                            } else {
                                responseJson.put("success", false)
                                responseJson.put("error", "العميل غير موجود")
                            }
                        }
                    }
                    "set_setting" -> {
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
                    "get_setting" -> {
                        val key = params["key"]?.firstOrNull() ?: ""
                        if (key.isNotEmpty()) {
                            responseJson.put("success", true)
                            responseJson.put("value", db.getSetting(key))
                        } else {
                            responseJson.put("success", false)
                            responseJson.put("error", "المفتاح مفقود")
                        }
                    }
                    else -> {
                        responseJson.put("success", false)
                        responseJson.put("error", "إجراء غير معروف: $action")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "API Error: ${e.message}", e)
                responseJson.put("success", false)
                responseJson.put("error", "Internal error: ${e.message}")
            } finally {
                db.close()
            }

            val res = newFixedLengthResponse(
                Response.Status.OK, "application/json", responseJson.toString()
            )
            headers.forEach { (k, v) -> res.addHeader(k, v) }
            return res
        }

        // ========== خدمة الملفات الثابتة ==========
        private fun serveStaticFile(uri: String): Response {
            return try {
                when {
                    uri == "/" || uri == "/index.html" -> {
                        val stream = assets.open("web_interface.html")
                        newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", stream)
                    }
                    uri == "/html5-qrcode.min.js" -> {
                        try {
                            val stream = assets.open("html5-qrcode.min.js")
                            newChunkedResponse(Response.Status.OK, "application/javascript", stream)
                        } catch (e: Exception) {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
                        }
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

    // ========== دوال الذكاء الاصطناعي ==========
    private fun callGeminiAPI(prompt: String, db: DatabaseHelper, sessionId: String): String {
        if (geminiApiKey.isEmpty()) return "مفتاح Gemini API غير مُكوّن"

        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$geminiApiKey")
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
                            put(JSONObject().apply {
                                put("text", prompt)
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
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

            parseGeminiResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini error: ${e.message}", e)
            "عذراً، حدث خطأ في الاتصال بـ Gemini"
        }
    }

    private fun parseGeminiResponse(response: String): String {
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
            json.optString("error", "لم يتم الحصول على رد")
        } catch (e: Exception) {
            "خطأ في معالجة الرد"
        }
    }

    private fun generateAIInsight(stats: JSONObject, sales: JSONArray): String {
        val prompt = """
            أنت مساعد ذكي لمحطة وقود. قدم تحليلاً مختصراً للبيانات التالية:
            - المخزون المتبقي: ${stats.optDouble("total_remaining", 0.0).toInt()} لتر
            - الديون المستحقة: ${stats.optDouble("total_due", 0.0).toInt()} ريال
            - مبيعات اليوم: ${stats.optDouble("total_sales", 0.0).toInt()} ريال
            - عدد العملاء: ${stats.optInt("total_customers", 0)}
            قدم توصية واحدة عملية.
        """.trimIndent()
        return callGeminiAPI(prompt, DatabaseHelper(this), "insight")
    }

    private fun generateSalesForecast(sales: JSONArray): String {
        if (sales.length() < 3) return "لا توجد بيانات كافية للتوقع"
        val prompt = """
            بناءً على بيانات المبيعات اليومية التالية، قدم توقعاً للأسبوع القادم:
            ${(0 until minOf(sales.length(), 30)).joinToString("\n") { i ->
                val s = sales.getJSONObject(i)
                "- ${s.optString("date")}: ${s.optDouble("total_qty", 0.0).toInt()} لتر"
            }}
        """.trimIndent()
        return callGeminiAPI(prompt, DatabaseHelper(this), "forecast")
    }

    private fun analyzeCustomerBehavior(customer: JSONObject): String {
        val prompt = """
            قم بتحليل سلوك العميل التالي وقدم نصيحة:
            - الاسم: ${customer.optString("full_name", "غير معروف")}
            - الرصيد المستحق: ${customer.optDouble("current_balance", 0.0).toInt()} ريال
            - نقاط الولاء: ${customer.optInt("loyalty_points", 0)}
            - مستوى VIP: ${customer.optInt("vip_level", 0)}
        """.trimIndent()
        return callGeminiAPI(prompt, DatabaseHelper(this), "analyze")
    }

    // ========== دوال SMS ==========
    private fun sendSMS(db: DatabaseHelper, phone: String, message: String, type: String): Boolean {
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed", e)
            db.logSms(phone, message, type, "failed: ${e.message}")
            false
        }
    }
}
