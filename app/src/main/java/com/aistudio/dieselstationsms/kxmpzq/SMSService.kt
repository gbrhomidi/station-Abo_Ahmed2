package com.aistudio.dieselstationsms.kxmpzq

import android.app.NotificationChannel
import android.app.NotificationManager
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
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SMSService - خدمة الخادم المحلي لتطبيق محطة أبو أحمد
 *
 * الإصدار 4.1 – مُحسَّن مع معالجة أفضل للاتصال والأخطاء
 *
 * الميزات:
 * - تشغيل خادم HTTP على المنفذ 8080
 * - تقديم واجهة الويب (web_interface.html) من مجلد assets
 * - توفير واجهة API للتفاعل مع قاعدة البيانات
 * - دعم الذكاء الاصطناعي (Gemini, DeepSeek, Grok, Kimi, ChatGPT)
 * - إرسال واستقبال الرسائل النصية
 * - إدارة القائمة البيضاء للرسائل
 * - جدولة النسخ الاحتياطي التلقائي
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
        private const val MAX_OVERDUE_SMS = 20
        private const val DEFAULT_EXPORT_LIMIT = 1000

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val DATETIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private var server: ApiServer? = null
    private var currentPort = SERVER_PORT
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDestroyed = AtomicBoolean(false)

    // مفاتيح API من BuildConfig
    private val geminiApiKey: String by lazy { BuildConfig.GEMINI_API_KEY }
    private val deepseekApiKey: String by lazy { BuildConfig.DEEPSEEK_API_KEY }
    private val grokApiKey: String by lazy { BuildConfig.GROK_API_KEY }
    private val kimiApiKey: String by lazy { BuildConfig.KIMI_API_KEY }
    private val chatgptApiKey: String by lazy { BuildConfig.CHATGPT_API_KEY }

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

    // ============================================================
    // الإشعارات
    // ============================================================
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

    // ============================================================
    // بدء الخادم (مع إعادة المحاولة)
    // ============================================================
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

    // ============================================================
    // النسخ الاحتياطي التلقائي
    // ============================================================
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

    // ============================================================
    // خادم API (NanoHTTPD)
    // ============================================================
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

            if (!uri.startsWith("/api")) {
                return serveStaticFile(uri)
            }

            val db = DatabaseHelper(this@SMSService)
            val responseJson = JSONObject()
            val params = session.parameters ?: mutableMapOf()
            val action = params["action"]?.firstOrNull() ?: ""

            try {
                when (action) {
                    // ===== المصادقة =====
                    "login" -> {
                        val username = params["username"]?.firstOrNull() ?: ""
                        val password = params["password"]?.firstOrNull() ?: ""
                        val auth = db.authenticateUser(username, password)
                        if (auth != null) {
                            responseJson.put("success", true)
                            responseJson.put("user", auth)
                            responseJson.put("token", UUID.randomUUID().toString())
                        } else {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات خاطئة")
                        }
                    }

                    // ===== العملاء =====
                    "get_customers" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getParties())
                    }
                    "add_customer" -> {
                        val name = params["commercial_name"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val credit = params["credit_limit"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val type = params["party_type_id"]?.firstOrNull()?.toIntOrNull() ?: 1
                        if (name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "الاسم مطلوب")
                        } else {
                            val id = db.addParty(type, name, null, phone, credit)
                            responseJson.put("success", true)
                            responseJson.put("party_id", id)
                        }
                    }
                    "update_customer" -> {
                        val id = params["party_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val name = params["commercial_name"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val credit = params["credit_limit"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        if (id <= 0 || name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            db.updateParty(id, name, null, phone, credit, "active")
                            responseJson.put("success", true)
                        }
                    }
                    "delete_customer" -> {
                        val id = params["party_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            db.deleteParty(id)
                            responseJson.put("success", true)
                        }
                    }
                    "get_customer_report" -> {
                        val id = params["party_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف العميل غير صالح")
                        } else {
                            responseJson.put("success", true)
                            responseJson.put("data", db.getCustomerReport(id))
                        }
                    }

                    // ===== الموردين =====
                    "get_suppliers" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getParties(6))
                    }

                    // ===== المبيعات =====
                    "get_sales" -> {
                        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 200
                        val offset = params["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                        responseJson.put("success", true)
                        responseJson.put("data", db.getSalesTransactions(1, limit, offset))
                    }
                    "execute_sale" -> {
                        val customerId = params["customer_party_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val fuelTypeId = params["fuel_type_id"]?.firstOrNull()?.toIntOrNull() ?: 1
                        val pumpId = params["pump_id"]?.firstOrNull()?.toIntOrNull() ?: 1
                        val liters = params["liters"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val pricePerLiter = params["price_per_liter"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val paid = params["paid_amount"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val paymentMethod = params["payment_method"]?.firstOrNull() ?: "cash"
                        val isCredit = params["is_credit"]?.firstOrNull()?.toIntOrNull() == 1
                        val dueDate = params["due_date"]?.firstOrNull() ?: ""
                        val notes = params["notes"]?.firstOrNull() ?: ""
                        val cashierId = 1

                        if (customerId <= 0 || liters <= 0 || pricePerLiter <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val subtotal = liters * pricePerLiter
                            val totalPaid = if (isCredit) 0.0 else paid
                            val due = subtotal - totalPaid

                            val saleId = db.insertSaleTransaction(
                                stationId = 1,
                                shiftId = 1,
                                customerPartyId = customerId,
                                fuelTypeId = fuelTypeId,
                                pumpId = pumpId,
                                nozzleId = null,
                                liters = liters,
                                pricePerLiter = pricePerLiter,
                                subtotal = subtotal,
                                discountAmount = 0.0,
                                taxAmount = 0.0,
                                grossAmount = subtotal,
                                netAmount = subtotal,
                                paymentMethod = paymentMethod,
                                isCredit = isCredit,
                                dueDate = if (isCredit) dueDate else null,
                                cashierId = cashierId,
                                notes = notes
                            )

                            if (saleId == -1L) {
                                responseJson.put("success", false)
                                responseJson.put("error", "فشل إدراج البيع")
                            } else {
                                db.logActivity("cashier_$cashierId", "sale", "بيع جديد: $liters لتر - $subtotal ريال")
                                val customer = db.getParty(customerId)
                                val phone = customer?.optString("phone", "")
                                if (!phone.isNullOrEmpty() && isSmsEnabled(db)) {
                                    val msg = if (isCredit) {
                                        "قيدنا عليكم: ${due.toInt()} ريال\nالتفاصيل: $liters لتر ديزل"
                                    } else {
                                        "تم شراء $liters لتر ديزل بقيمة ${subtotal.toInt()} ريال\nشكراً لزيارتكم محطة أبو أحمد"
                                    }
                                    sendSMS(db, phone, msg, "sale_notification")
                                }
                                responseJson.put("success", true)
                                responseJson.put("sale_id", saleId)
                                responseJson.put("message", "تم البيع بنجاح")
                                responseJson.put("invoice_number", "INV-${System.currentTimeMillis()}")
                            }
                        }
                    }

                    // ===== المدفوعات =====
                    "make_payment" -> {
                        val customerId = params["customer_party_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val amount = params["amount"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val method = params["payment_method"]?.firstOrNull() ?: "cash"
                        val notes = params["notes"]?.firstOrNull() ?: ""
                        val operator = params["operator"]?.firstOrNull() ?: "System"

                        if (customerId <= 0 || amount <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val success = db.processPayment(customerId, amount, method, operator)
                            if (success) {
                                db.logActivity(operator, "payment", "تسديد مبلغ $amount للعميل $customerId")
                                responseJson.put("success", true)
                                responseJson.put("message", "تم التسديد بنجاح")
                            } else {
                                responseJson.put("success", false)
                                responseJson.put("error", "فشل التسديد")
                            }
                        }
                    }

                    // ===== الإيداعات =====
                    "add_deposit" -> {
                        val customerId = params["customer_party_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val amount = params["amount"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val notes = params["notes"]?.firstOrNull() ?: ""
                        val operator = params["operator"]?.firstOrNull() ?: "System"

                        if (customerId <= 0 || amount <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val success = db.addCashDeposit(customerId, amount, notes, operator)
                            if (success) {
                                val customer = db.getParty(customerId)
                                val phone = customer?.optString("phone", "")
                                if (!phone.isNullOrEmpty() && isSmsEnabled(db)) {
                                    val msg = "قيدنا لكم: ${amount.toInt()} ريال\nالتفاصيل: ${notes.ifEmpty { "إيداع نقدي" }}"
                                    sendSMS(db, phone, msg, "deposit_notification")
                                }
                                responseJson.put("success", true)
                                responseJson.put("message", "تم الإيداع بنجاح")
                            } else {
                                responseJson.put("success", false)
                                responseJson.put("error", "فشل الإيداع")
                            }
                        }
                    }

                    // ===== المركبات والسائقين =====
                    "get_vehicles" -> {
                        val arr = JSONArray()
                        val cursor = db.readableDatabase.rawQuery(
                            "SELECT * FROM vehicles WHERE is_deleted = 0 ORDER BY plate_number",
                            null
                        )
                        cursor.use {
                            while (it.moveToNext()) {
                                arr.put(JSONObject().apply {
                                    put("vehicle_id", it.getInt(it.getColumnIndexOrThrow("id")))
                                    put("vehicle_code", it.getString(it.getColumnIndexOrThrow("vehicle_code")))
                                    put("plate_number", it.getString(it.getColumnIndexOrThrow("plate_number")))
                                    put("brand", it.getString(it.getColumnIndexOrThrow("brand")))
                                    put("model", it.getString(it.getColumnIndexOrThrow("model")))
                                    put("tank_capacity", it.getDouble(it.getColumnIndexOrThrow("tank_capacity")))
                                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                                    put("party_id", it.getInt(it.getColumnIndexOrThrow("party_id")))
                                })
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", arr)
                    }
                    "get_drivers" -> {
                        val arr = JSONArray()
                        val cursor = db.readableDatabase.rawQuery(
                            "SELECT * FROM drivers WHERE is_deleted = 0 ORDER BY full_name",
                            null
                        )
                        cursor.use {
                            while (it.moveToNext()) {
                                arr.put(JSONObject().apply {
                                    put("driver_id", it.getInt(it.getColumnIndexOrThrow("id")))
                                    put("driver_code", it.getString(it.getColumnIndexOrThrow("driver_code")))
                                    put("full_name", it.getString(it.getColumnIndexOrThrow("full_name")))
                                    put("phone", it.getString(it.getColumnIndexOrThrow("phone")))
                                    put("license_number", it.getString(it.getColumnIndexOrThrow("license_number")))
                                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                                    put("vehicle_id", it.getInt(it.getColumnIndexOrThrow("vehicle_id")))
                                })
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", arr)
                    }

                    // ===== المنتجات =====
                    "get_products" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getProducts(1))
                    }
                    "get_product_categories" -> {
                        val arr = JSONArray()
                        val cursor = db.readableDatabase.rawQuery(
                            "SELECT id, category_name FROM product_categories WHERE is_active = 1 AND is_deleted = 0 ORDER BY category_name",
                            null
                        )
                        cursor.use {
                            while (it.moveToNext()) {
                                arr.put(JSONObject().apply {
                                    put("category_id", it.getInt(0))
                                    put("category_name", it.getString(1))
                                })
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", arr)
                    }
                    "get_inventory_movements" -> {
                        val arr = JSONArray()
                        val cursor = db.readableDatabase.rawQuery(
                            "SELECT * FROM inventory_movements WHERE station_id = 1 AND is_deleted = 0 ORDER BY id DESC LIMIT 50",
                            null
                        )
                        cursor.use {
                            while (it.moveToNext()) {
                                arr.put(JSONObject().apply {
                                    put("movement_code", it.getString(it.getColumnIndexOrThrow("movement_code")))
                                    put("movement_type", it.getString(it.getColumnIndexOrThrow("movement_type")))
                                    put("quantity_change", it.getDouble(it.getColumnIndexOrThrow("quantity_change")))
                                })
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", arr)
                    }

                    // ===== الحسابات =====
                    "get_accounts" -> {
                        val arr = JSONArray()
                        val cursor = db.readableDatabase.rawQuery(
                            "SELECT account_code, account_name FROM accounts WHERE is_active = 1 AND is_deleted = 0 ORDER BY account_code",
                            null
                        )
                        cursor.use {
                            while (it.moveToNext()) {
                                arr.put(JSONObject().apply {
                                    put("account_code", it.getString(0))
                                    put("account_name", it.getString(1))
                                })
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", arr)
                    }
                    "get_journal_entries" -> {
                        val arr = JSONArray()
                        val cursor = db.readableDatabase.rawQuery(
                            "SELECT entry_number, description FROM journal_entries WHERE is_deleted = 0 ORDER BY id DESC LIMIT 20",
                            null
                        )
                        cursor.use {
                            while (it.moveToNext()) {
                                arr.put(JSONObject().apply {
                                    put("entry_number", it.getString(0))
                                    put("description", it.getString(1))
                                })
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", arr)
                    }

                    // ===== الأصول =====
                    "get_assets" -> {
                        val arr = JSONArray()
                        val cursor = db.readableDatabase.rawQuery(
                            "SELECT * FROM fixed_assets WHERE station_id = 1 AND is_deleted = 0 ORDER BY asset_name",
                            null
                        )
                        cursor.use {
                            while (it.moveToNext()) {
                                arr.put(JSONObject().apply {
                                    put("asset_id", it.getInt(it.getColumnIndexOrThrow("id")))
                                    put("asset_name", it.getString(it.getColumnIndexOrThrow("asset_name")))
                                    put("asset_type", it.getString(it.getColumnIndexOrThrow("asset_type")))
                                    put("current_value", it.getDouble(it.getColumnIndexOrThrow("current_value")))
                                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                                })
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", arr)
                    }

                    // ===== لوحة التحكم =====
                    "get_dashboard" -> {
                        responseJson.put("success", true)
                        val stats = db.getDashboardStats(1)
                        stats.put("diesel_price", db.getDieselPrice())
                        stats.put("gasoline_price", db.getGasolinePrice())
                        stats.put("manager_phone", db.getManagerPhone() ?: "")
                        stats.put("retention_days", db.getRetentionDays())
                        responseJson.put("data", stats)
                    }
                    "get_daily_sales" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getDailySales(1))
                    }
                    "get_monthly_sales" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getMonthlySales(1))
                    }
                    "get_eod_report" -> {
                        val from = params["from_date"]?.firstOrNull()
                        val to = params["to_date"]?.firstOrNull()
                        responseJson.put("success", true)
                        responseJson.put("data", db.getEodReport(1, from, to))
                    }
                    "get_profit_report" -> {
                        val from = params["from_date"]?.firstOrNull()
                        val to = params["to_date"]?.firstOrNull()
                        val report = db.getEodReport(1, from, to)
                        val profit = report.optDouble("total_sales", 0.0) - report.optDouble("total_payments", 0.0)
                        report.put("profit", profit)
                        report.put("revenue", report.optDouble("total_sales", 0.0))
                        report.put("cost", report.optDouble("total_payments", 0.0))
                        responseJson.put("success", true)
                        responseJson.put("data", report)
                    }
                    "export_data" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.exportAllData())
                    }

                    // ===== المخزون =====
                    "get_refills" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getRefills())
                    }
                    "add_refill" -> {
                        val supplier = params["supplier"]?.firstOrNull() ?: ""
                        val totalQty = params["total_qty"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val fuelTypeId = params["fuel_type_id"]?.firstOrNull()?.toIntOrNull() ?: 1
                        val price = params["sell_price"]?.firstOrNull()?.toDoubleOrNull() ?: 500.0
                        val allowCredit = params["allow_credit"]?.firstOrNull()?.toIntOrNull() ?: 1
                        val threshold = params["alert_threshold"]?.firstOrNull()?.toDoubleOrNull() ?: 1000.0

                        if (supplier.isBlank() || totalQty <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val dbWritable = db.writableDatabase
                            val cv = android.content.ContentValues().apply {
                                put("uuid", UUID.randomUUID().toString())
                                put("refill_code", "REF-${System.currentTimeMillis()}")
                                put("tank_id", 1)
                                put("supplier_name", supplier)
                                put("station_id", 1)
                                put("fuel_type_id", fuelTypeId)
                                put("delivered_quantity", totalQty)
                                put("actual_quantity", totalQty)
                                put("unit_price", price)
                                put("status", "completed")
                            }
                            val id = dbWritable.insert("tank_refills", null, cv)
                            db.execSQL("UPDATE tanks SET current_quantity = current_quantity + ? WHERE id = 1", arrayOf(totalQty))
                            db.logActivity("System", "refill", "تعبئة جديدة: $totalQty لتر من $supplier")
                            responseJson.put("success", true)
                            responseJson.put("refill_id", id)
                        }
                    }

                    // ===== الخزانات والمضخات =====
                    "get_tanks" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getTanks(1))
                    }
                    "get_pumps" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getPumps(1))
                    }
                    "get_fuel_types" -> {
                        val arr = JSONArray()
                        val cursor = db.readableDatabase.rawQuery(
                            "SELECT id, fuel_code, fuel_name, fuel_name_ar, default_sale_price FROM fuel_types WHERE is_active=1 AND is_deleted=0",
                            null
                        )
                        cursor.use {
                            while (it.moveToNext()) {
                                arr.put(JSONObject().apply {
                                    put("fuel_type_id", it.getInt(0))
                                    put("fuel_code", it.getString(1))
                                    put("fuel_name", it.getString(2))
                                    put("fuel_name_ar", it.getString(3))
                                    put("default_sale_price", it.getDouble(4))
                                })
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", arr)
                    }
                    "update_tank_quantity" -> {
                        val tankId = params["tank_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val quantity = params["quantity"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        if (tankId <= 0 || quantity < 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            db.updateTankQuantity(tankId, quantity, "System")
                            responseJson.put("success", true)
                        }
                    }

                    // ===== الموظفين =====
                    "get_employees" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getEmployees(1))
                    }
                    "add_employee" -> {
                        val name = params["full_name"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val jobTitle = params["job_title"]?.firstOrNull() ?: ""
                        val salary = params["basic_salary"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        if (name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "الاسم مطلوب")
                        } else {
                            db.addEmployee(name, null, phone, jobTitle, salary, 1)
                            responseJson.put("success", true)
                        }
                    }
                    "update_employee" -> {
                        val id = params["employee_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val name = params["full_name"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val jobTitle = params["job_title"]?.firstOrNull() ?: ""
                        val salary = params["basic_salary"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val status = params["status"]?.firstOrNull() ?: "active"
                        if (id <= 0 || name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            db.updateEmployee(id, name, null, phone, jobTitle, salary, status, "", 1)
                            responseJson.put("success", true)
                        }
                    }
                    "delete_employee" -> {
                        val id = params["employee_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            db.deleteEmployee(id)
                            responseJson.put("success", true)
                        }
                    }
                    "add_employee_payment" -> {
                        val employeeId = params["employee_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val amount = params["amount"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val type = params["type"]?.firstOrNull() ?: "salary"
                        val desc = params["description"]?.firstOrNull() ?: ""
                        val operator = params["operator"]?.firstOrNull() ?: "System"
                        if (employeeId <= 0 || amount <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            db.addEmployeePayment(employeeId, amount, type, desc, operator)
                            responseJson.put("success", true)
                        }
                    }

                    // ===== الصيانة =====
                    "add_maintenance_request" -> {
                        val assetType = params["asset_type"]?.firstOrNull() ?: "pump"
                        val assetId = params["asset_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val requestType = params["request_type"]?.firstOrNull() ?: ""
                        val priority = params["priority"]?.firstOrNull() ?: "medium"
                        val title = params["title"]?.firstOrNull() ?: ""
                        val description = params["description"]?.firstOrNull() ?: ""
                        val reportedBy = 1
                        if (assetId <= 0 || requestType.isBlank() || title.isBlank() || description.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val id = db.addMaintenanceRequest(assetType, assetId, requestType, priority, title, description, reportedBy, 1)
                            responseJson.put("success", true)
                            responseJson.put("request_id", id)
                        }
                    }
                    "get_maintenance_requests" -> {
                        val status = params["status"]?.firstOrNull()
                        responseJson.put("success", true)
                        responseJson.put("data", db.getMaintenanceRequests(1, status))
                    }
                    "update_maintenance_status" -> {
                        val requestId = params["request_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val status = params["status"]?.firstOrNull() ?: ""
                        if (requestId <= 0 || status.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val dbWritable = db.writableDatabase
                            val cv = android.content.ContentValues().apply {
                                put("status", status)
                                if (status == "completed") {
                                    put("completed_at", DATETIME_FORMAT.format(Date()))
                                }
                            }
                            dbWritable.update("maintenance_requests", cv, "id=?", arrayOf(requestId.toString()))
                            db.logActivity("System", "maintenance", "تحديث حالة الصيانة $requestId إلى $status")
                            responseJson.put("success", true)
                        }
                    }

                    // ===== الديون =====
                    "get_debts" -> {
                        val type = params["type"]?.firstOrNull() ?: "all"
                        val debts = db.getBadDebts()
                        val filtered = JSONArray()
                        for (i in 0 until debts.length()) {
                            val d = debts.getJSONObject(i)
                            if (type == "all" || d.getString("type") == type) {
                                filtered.put(d)
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", filtered)
                    }
                    "resolve_debt" -> {
                        val debtId = params["debt_id"]?.firstOrNull()?.toIntOrNull() ?: 0
                        if (debtId <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            db.resolveBadDebt(debtId)
                            responseJson.put("success", true)
                        }
                    }

                    // ===== الرسائل =====
                    "send_sms" -> {
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val message = params["message"]?.firstOrNull() ?: ""
                        if (phone.isBlank() || message.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "رقم الهاتف والرسالة مطلوبان")
                        } else {
                            val sent = sendSMS(db, phone, message, "manual")
                            responseJson.put("success", sent)
                            responseJson.put("message", if (sent) "تم الإرسال" else "فشل الإرسال")
                        }
                    }
                    "send_overdue_sms" -> {
                        val overdue = db.getOverdueTransactions(1)
                        var sentCount = 0
                        val total = minOf(overdue.length(), MAX_OVERDUE_SMS)
                        for (i in 0 until total) {
                            val t = overdue.getJSONObject(i)
                            val phone = t.optString("customer_phone", "")
                            if (phone.isNotEmpty() && isSmsAllowed(phone, db)) {
                                val msg = "تذكير: لديك مبلغ ${t.getDouble("remaining_amount").toInt()} ريال مستحق منذ ${t.optString("due_date", "")}"
                                if (sendSMS(db, phone, msg, "overdue_reminder")) sentCount++
                                Thread.sleep(SMS_DELAY_MS)
                            }
                        }
                        responseJson.put("success", true)
                        responseJson.put("message", "تم إرسال $sentCount رسالة تذكير من $total")
                    }
                    "get_sms_logs" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getSmsLogs())
                    }

                    // ===== القائمة البيضاء =====
                    "get_whitelist" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getSmsWhitelist())
                    }
                    "add_whitelist" -> {
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val name = params["name"]?.firstOrNull() ?: ""
                        if (phone.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "رقم الهاتف مطلوب")
                        } else {
                            db.addToSmsWhitelist(phone, name)
                            responseJson.put("success", true)
                        }
                    }
                    "remove_whitelist" -> {
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        if (phone.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "رقم الهاتف مطلوب")
                        } else {
                            db.removeFromSmsWhitelist(phone)
                            responseJson.put("success", true)
                        }
                    }

                    // ===== السجلات =====
                    "get_activity_logs" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getActivityLogs())
                    }

                    // ===== الإعدادات =====
                    "get_setting" -> {
                        val key = params["key"]?.firstOrNull() ?: ""
                        if (key.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "المفتاح مطلوب")
                        } else {
                            val value = db.getSetting(key)
                            responseJson.put("success", true)
                            responseJson.put("value", value)
                        }
                    }
                    "set_setting" -> {
                        val key = params["key"]?.firstOrNull() ?: ""
                        val value = params["value"]?.firstOrNull() ?: ""
                        if (key.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "المفتاح مطلوب")
                        } else {
                            db.setSetting(key, value)
                            responseJson.put("success", true)
                        }
                    }

                    // ===== الذكاء الاصطناعي =====
                    "ai_chat" -> {
                        val message = params["message"]?.firstOrNull() ?: ""
                        val sessionId = params["session_id"]?.firstOrNull() ?: "default"
                        if (message.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "الرسالة فارغة")
                        } else {
                            val history = db.getAiChatHistory(sessionId)
                            val context = if (history.length() > 0) {
                                "سياق المحادثة السابقة: ${history.toString().take(500)}\n\n"
                            } else ""
                            val prompt = "$context\nالسؤال: $message\nالرد بالعربية مختصراً ومهنياً."

                            val reply = callAIWithFallback(prompt, db)
                            db.saveAiMessage(sessionId, "assistant", reply)
                            responseJson.put("success", true)
                            responseJson.put("reply", reply)
                        }
                    }
                    "get_ai_insight" -> {
                        if (geminiApiKey.isEmpty() && deepseekApiKey.isEmpty() && grokApiKey.isEmpty() && kimiApiKey.isEmpty() && chatgptApiKey.isEmpty()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "لا توجد مفاتيح API مُكوّنة")
                        } else {
                            val stats = db.getDashboardStats(1)
                            val prompt = """
                                أنت مساعد ذكي لمحطة وقود. قدم تحليلاً مختصراً للبيانات التالية:
                                - المخزون المتبقي: ${stats.optDouble("total_remaining", 0.0).toInt()} لتر
                                - الديون المستحقة: ${stats.optDouble("total_due", 0.0).toInt()} ريال
                                - مبيعات اليوم: ${stats.optDouble("total_sales", 0.0).toInt()} ريال
                                - عدد العملاء: ${stats.optInt("total_customers", 0)}
                                قدم توصية واحدة عملية مختصرة (سطرين فقط).
                            """.trimIndent()
                            val insight = callAIWithFallback(prompt, db)
                            responseJson.put("success", true)
                            responseJson.put("insight", insight)
                        }
                    }

                    else -> {
                        responseJson.put("success", false)
                        responseJson.put("error", "إجراء غير معروف: $action")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "API Error in $action: ${e.message}", e)
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

        // ============================================================
        // الملفات الثابتة (تقديم واجهة الويب)
        // ============================================================
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

    // ================================================================
    // الذكاء الاصطناعي (مع الاحتياط بين المفاتيح)
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
    // دوال الرسائل النصية (SMS)
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
}
