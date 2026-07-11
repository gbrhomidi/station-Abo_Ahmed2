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

class SMSService : Service() {

    companion object {
        private const val TAG = "SMSService"
        private const val PORT = 8080
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

    private var server: ApiServer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDestroyed = AtomicBoolean(false)

    private val geminiApiKey: String by lazy { BuildConfig.GEMINI_API_KEY }
    private val deepseekApiKey: String by lazy { BuildConfig.DEEPSEEK_API_KEY }
    private val grokApiKey: String by lazy { BuildConfig.GROK_API_KEY }
    private val kimiApiKey: String by lazy { BuildConfig.KIMI_API_KEY }
    private val chatgptApiKey: String by lazy { BuildConfig.CHATGPT_API_KEY }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SMSService onCreate")
        createNotificationChannel()
        isDestroyed.set(false)

        serviceScope.launch {
            try {
                startForegroundService()
                startServer()
                scheduleAutoBackup()
                Log.d(TAG, "Service initialization completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal Error in service initialization: ${e.message}", e)
                stopSelf()
            }
        }
    }

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SMSService onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!isDestroyed.get() && (server == null || !server!!.isAlive)) {
            serviceScope.launch { startServer() }
        }
        return START_STICKY
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
            .setContentTitle("محطة أبو أحمد")
            .setContentText("خدمة الرسائل النصية نشطة")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛽ محطة أبو أحمد")
            .setContentText("الخادم المحلي يعمل على المنفذ $PORT...")
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

    private fun startServer() {
        if (isDestroyed.get()) {
            Log.w(TAG, "Service is destroyed, not starting server")
            return
        }

        try {
            server?.stop()
            server = ApiServer(PORT)
            server?.start()
            Log.d(TAG, "Server started successfully at port $PORT")
            updateNotification("الخادم المحلي يعمل على المنفذ $PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server on port $PORT: ${e.message}", e)
            updateNotification("فشل بدء الخادم على المنفذ $PORT")
        }
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

    // ================================================================
    //  API SERVER (NanoHTTPD)
    // ================================================================
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
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }

            val db = DatabaseHelper(this@SMSService)
            val responseJson = JSONObject()
            val params = session.parameters ?: mutableMapOf()
            val action = params["action"]?.firstOrNull() ?: ""

            try {
                when (action) {

                    // ============================================================
                    //  AUTHENTICATION
                    // ============================================================
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

                    // ============================================================
                    //  CUSTOMERS (Parties with type = customer)
                    // ============================================================
                    "get_customers" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getParties("customer"))
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
                            val json = JSONObject().apply {
                                put("commercial_name", name)
                                put("phone", phone)
                                put("credit_limit", credit)
                                put("party_type_id", type)
                            }
                            val id = db.insertParty(json)
                            responseJson.put("success", true)
                            responseJson.put("party_id", id)
                        }
                    }
                    "update_customer" -> {
                        val id = params["party_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        val name = params["commercial_name"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val credit = params["credit_limit"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        if (id <= 0 || name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val json = JSONObject().apply {
                                put("commercial_name", name)
                                put("phone", phone)
                                put("credit_limit", credit)
                            }
                            db.updateParty(id, json)
                            responseJson.put("success", true)
                        }
                    }
                    "delete_customer" -> {
                        val id = params["party_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            db.deleteParty(id)
                            responseJson.put("success", true)
                        }
                    }

                    // ============================================================
                    //  SUPPLIERS (Parties with type = supplier)
                    // ============================================================
                    "get_suppliers" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getParties("supplier"))
                    }

                    // ============================================================
                    //  DRIVERS (Parties with type = driver)
                    // ============================================================
                    "get_drivers" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getParties("driver"))
                    }

                    // ============================================================
                    //  SALES (sales_transactions with fuel and customer names)
                    // ============================================================
                    "get_sales" -> {
                        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 200
                        val offset = params["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val sales = db.getSalesTransactions(1, limit, offset)
                        // إضافة أسماء الوقود والعملاء يدوياً (أو تحسين الاستعلام في DB)
                        // نحن نستخدم طريقة آمنة: إضافة الحقول المطلوبة عبر دالة إضافية.
                        val enriched = JSONArray()
                        for (i in 0 until sales.length()) {
                            val s = sales.getJSONObject(i)
                            val fuelTypeId = s.optInt("fuel_type_id", 0)
                            val customerId = s.optInt("customer_party_id", 0)
                            // جلب اسم الوقود
                            if (fuelTypeId > 0) {
                                val fuelName = db.getFuelNameById(fuelTypeId)
                                s.put("fuel_name", fuelName ?: "")
                            } else {
                                s.put("fuel_name", "")
                            }
                            // جلب اسم العميل
                            if (customerId > 0) {
                                val customer = db.getParty(customerId)
                                s.put("customer_name", customer?.optString("commercial_name") ?: "")
                            } else {
                                s.put("customer_name", "")
                            }
                            enriched.put(s)
                        }
                        responseJson.put("success", true)
                        responseJson.put("data", enriched)
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
                        val shiftId = params["shift_id"]?.firstOrNull()?.toIntOrNull() ?: 1
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
                                shiftId = shiftId,
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
                                responseJson.put("success", true)
                                responseJson.put("sale_id", saleId)
                                responseJson.put("message", "تم البيع بنجاح")
                                responseJson.put("invoice_number", "INV-${System.currentTimeMillis()}")
                            }
                        }
                    }
                    "delete_sale" -> {
                        val saleId = params["sale_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (saleId <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            // soft delete
                            val dbWritable = db.writableDatabase
                            val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                            val rows = dbWritable.update("sales_transactions", cv, "id=?", arrayOf(saleId.toString()))
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "delete_sale", "حذف مبيعة $saleId")
                        }
                    }

                    // ============================================================
                    //  PAYMENTS (with customer name)
                    // ============================================================
                    "get_payments" -> {
                        val payments = db.getPaymentsWithCustomer()
                        responseJson.put("success", true)
                        responseJson.put("data", payments)
                    }
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
                    "delete_payment" -> {
                        val paymentId = params["payment_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (paymentId <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            val dbWritable = db.writableDatabase
                            val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                            val rows = dbWritable.update("payments", cv, "id=?", arrayOf(paymentId.toString()))
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "delete_payment", "حذف دفعة $paymentId")
                        }
                    }
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
                                responseJson.put("success", true)
                                responseJson.put("message", "تم الإيداع بنجاح")
                            } else {
                                responseJson.put("success", false)
                                responseJson.put("error", "فشل الإيداع")
                            }
                        }
                    }

                    // ============================================================
                    //  PRODUCTS (جدول products)
                    // ============================================================
                    "get_products" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getProducts(1))
                    }
                    "add_product" -> {
                        val code = params["product_code"]?.firstOrNull() ?: ""
                        val name = params["product_name"]?.firstOrNull() ?: ""
                        val fuelTypeId = params["fuel_type_id"]?.firstOrNull()?.toIntOrNull()
                        val categoryId = params["category_id"]?.firstOrNull()?.toIntOrNull()
                        val salePrice = params["sale_price"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val purchasePrice = params["purchase_price"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val quantity = params["quantity"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val minStock = params["minimum_stock"]?.firstOrNull()?.toDoubleOrNull() ?: 10.0
                        val unit = params["unit_id"]?.firstOrNull() ?: "لتر"

                        if (code.isBlank() || name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "الرمز والاسم مطلوبان")
                        } else {
                            val json = JSONObject().apply {
                                put("product_code", code)
                                put("product_name", name)
                                fuelTypeId?.let { put("fuel_type_id", it) }
                                categoryId?.let { put("category_id", it) }
                                put("sale_price", salePrice)
                                put("purchase_price", purchasePrice)
                                put("quantity", quantity)
                                put("minimum_stock", minStock)
                                put("unit_id", unit)
                                put("station_id", 1)
                            }
                            val id = db.insertProduct(json)
                            responseJson.put("success", true)
                            responseJson.put("product_id", id)
                        }
                    }
                    "update_product" -> {
                        val id = params["product_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            val json = JSONObject().apply {
                                params["product_code"]?.firstOrNull()?.let { put("product_code", it) }
                                params["product_name"]?.firstOrNull()?.let { put("product_name", it) }
                                params["fuel_type_id"]?.firstOrNull()?.toIntOrNull()?.let { put("fuel_type_id", it) }
                                params["category_id"]?.firstOrNull()?.toIntOrNull()?.let { put("category_id", it) }
                                params["sale_price"]?.firstOrNull()?.toDoubleOrNull()?.let { put("sale_price", it) }
                                params["purchase_price"]?.firstOrNull()?.toDoubleOrNull()?.let { put("purchase_price", it) }
                                params["quantity"]?.firstOrNull()?.toDoubleOrNull()?.let { put("quantity", it) }
                                params["minimum_stock"]?.firstOrNull()?.toDoubleOrNull()?.let { put("minimum_stock", it) }
                                params["unit_id"]?.firstOrNull()?.let { put("unit_id", it) }
                            }
                            val rows = db.updateProduct(id, json)
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "update_product", "تحديث منتج $id")
                        }
                    }
                    "delete_product" -> {
                        val id = params["product_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            val rows = db.deleteProduct(id)
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "delete_product", "حذف منتج $id")
                        }
                    }

                    // ============================================================
                    //  PRODUCT CATEGORIES
                    // ============================================================
                    "get_categories" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getProductCategories())
                    }

                    // ============================================================
                    //  FUEL TYPES
                    // ============================================================
                    "get_fuel_types" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getFuelTypes())
                    }

                    // ============================================================
                    //  EMPLOYEES
                    // ============================================================
                    "get_employees" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getEmployees(1))
                    }
                    "add_employee" -> {
                        val name = params["full_name"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val jobTitle = params["job_title"]?.firstOrNull() ?: ""
                        val department = params["department"]?.firstOrNull() ?: ""
                        val salary = params["basic_salary"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val status = params["status"]?.firstOrNull() ?: "active"
                        if (name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "الاسم مطلوب")
                        } else {
                            val json = JSONObject().apply {
                                put("full_name", name)
                                put("phone", phone)
                                put("job_title", jobTitle)
                                put("department", department)
                                put("basic_salary", salary)
                                put("status", status)
                                put("station_id", 1)
                            }
                            val id = db.addEmployee(json)
                            responseJson.put("success", true)
                            responseJson.put("employee_id", id)
                        }
                    }
                    "update_employee" -> {
                        val id = params["employee_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        val name = params["full_name"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val jobTitle = params["job_title"]?.firstOrNull() ?: ""
                        val department = params["department"]?.firstOrNull() ?: ""
                        val salary = params["basic_salary"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
                        val status = params["status"]?.firstOrNull() ?: "active"
                        if (id <= 0 || name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val json = JSONObject().apply {
                                put("full_name", name)
                                put("phone", phone)
                                put("job_title", jobTitle)
                                put("department", department)
                                put("basic_salary", salary)
                                put("status", status)
                            }
                            val rows = db.updateEmployee(id, json)
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "update_employee", "تحديث موظف $id")
                        }
                    }
                    "delete_employee" -> {
                        val id = params["employee_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            val rows = db.deleteEmployee(id.toInt())
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "delete_employee", "حذف موظف $id")
                        }
                    }

                    // ============================================================
                    //  USERS (with roles)
                    // ============================================================
                    "get_users" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getUsers())
                    }
                    "add_user" -> {
                        val username = params["username"]?.firstOrNull() ?: ""
                        val password = params["password"]?.firstOrNull() ?: ""
                        val email = params["email"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val fullName = params["full_name"]?.firstOrNull() ?: ""
                        val roleId = params["role_id"]?.firstOrNull()?.toIntOrNull() ?: 4
                        val status = params["status"]?.firstOrNull() ?: "active"

                        if (username.isBlank() || fullName.isBlank() || password.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "اسم المستخدم، الاسم الكامل وكلمة المرور مطلوبة")
                        } else {
                            val json = JSONObject().apply {
                                put("username", username)
                                put("password", password)
                                put("email", email)
                                put("phone", phone)
                                put("full_name", fullName)
                                put("role_id", roleId)
                                put("status", status)
                                put("station_id", 1)
                                put("company_id", 1)
                            }
                            val id = db.addUser(json)
                            responseJson.put("success", true)
                            responseJson.put("user_id", id)
                        }
                    }
                    "update_user" -> {
                        val id = params["user_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        val email = params["email"]?.firstOrNull() ?: ""
                        val phone = params["phone"]?.firstOrNull() ?: ""
                        val fullName = params["full_name"]?.firstOrNull() ?: ""
                        val roleId = params["role_id"]?.firstOrNull()?.toIntOrNull()
                        val status = params["status"]?.firstOrNull() ?: "active"

                        if (id <= 0 || fullName.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val json = JSONObject().apply {
                                put("full_name", fullName)
                                put("email", email)
                                put("phone", phone)
                                roleId?.let { put("role_id", it) }
                                put("status", status)
                            }
                            val rows = db.updateUser(id, json)
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "update_user", "تحديث مستخدم $id")
                        }
                    }
                    "delete_user" -> {
                        val id = params["user_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            val rows = db.deleteUser(id)
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "delete_user", "حذف مستخدم $id")
                        }
                    }

                    // ============================================================
                    //  ROLES
                    // ============================================================
                    "get_roles" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getRoles())
                    }
                    "add_role" -> {
                        val code = params["role_code"]?.firstOrNull() ?: ""
                        val name = params["role_name"]?.firstOrNull() ?: ""
                        val desc = params["description"]?.firstOrNull() ?: ""
                        val level = params["level"]?.firstOrNull()?.toIntOrNull() ?: 1
                        val isSystem = params["is_system_role"]?.firstOrNull()?.toIntOrNull() ?: 0

                        if (code.isBlank() || name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "الرمز والاسم مطلوبان")
                        } else {
                            val json = JSONObject().apply {
                                put("role_code", code)
                                put("role_name", name)
                                put("description", desc)
                                put("level", level)
                                put("is_system_role", isSystem)
                            }
                            val id = db.addRole(json)
                            responseJson.put("success", true)
                            responseJson.put("role_id", id)
                        }
                    }
                    "update_role" -> {
                        val id = params["role_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        val name = params["role_name"]?.firstOrNull() ?: ""
                        val desc = params["description"]?.firstOrNull() ?: ""
                        val level = params["level"]?.firstOrNull()?.toIntOrNull()
                        val isSystem = params["is_system_role"]?.firstOrNull()?.toIntOrNull()

                        if (id <= 0 || name.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "بيانات غير صالحة")
                        } else {
                            val json = JSONObject().apply {
                                put("role_name", name)
                                put("description", desc)
                                level?.let { put("level", it) }
                                isSystem?.let { put("is_system_role", it) }
                            }
                            val rows = db.updateRole(id, json)
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "update_role", "تحديث دور $id")
                        }
                    }
                    "delete_role" -> {
                        val id = params["role_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            val rows = db.deleteRole(id)
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "delete_role", "حذف دور $id")
                        }
                    }

                    // ============================================================
                    //  SHIFTS
                    // ============================================================
                    "get_shifts" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getShifts(1))
                    }
                    "add_shift" -> {
                        val shiftType = params["shift_type"]?.firstOrNull() ?: "morning"
                        val shiftDate = params["shift_date"]?.firstOrNull() ?: DATE_FORMAT.format(Date())
                        val startTime = params["start_time"]?.firstOrNull() ?: ""
                        val cashierId = params["cashier_id"]?.firstOrNull()?.toIntOrNull() ?: 1
                        val openingCash = params["opening_cash"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0

                        if (shiftDate.isBlank() || startTime.isBlank()) {
                            responseJson.put("success", false)
                            responseJson.put("error", "التاريخ ووقت البدء مطلوبان")
                        } else {
                            // تحويل startTime إلى DateTime
                            val startDateTime = "$shiftDate $startTime:00"
                            val cv = android.content.ContentValues().apply {
                                put("uuid", UUID.randomUUID().toString())
                                put("shift_code", "SHF-${System.currentTimeMillis()}")
                                put("station_id", 1)
                                put("shift_date", shiftDate)
                                put("shift_type", shiftType)
                                put("start_time", startDateTime)
                                put("cashier_id", cashierId)
                                put("opening_cash", openingCash)
                                put("status", "open")
                            }
                            val id = db.writableDatabase.insert("shifts", null, cv)
                            responseJson.put("success", id > 0)
                            if (id > 0) db.logActivity("system", "add_shift", "إضافة وردية $id")
                        }
                    }
                    "delete_shift" -> {
                        val id = params["shift_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (id <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            val dbWritable = db.writableDatabase
                            val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                            val rows = dbWritable.update("shifts", cv, "id=?", arrayOf(id.toString()))
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "delete_shift", "حذف وردية $id")
                        }
                    }

                    // ============================================================
                    //  MAINTENANCE REQUESTS
                    // ============================================================
                    "get_maintenance_requests" -> {
                        val status = params["status"]?.firstOrNull()
                        responseJson.put("success", true)
                        responseJson.put("data", db.getMaintenanceRequests(1, status))
                    }
                    "add_maintenance_request" -> {
                        val assetType = params["asset_type"]?.firstOrNull() ?: "tank"
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
                    "update_maintenance_status" -> {
                        val requestId = params["request_id"]?.firstOrNull()?.toLongOrNull() ?: 0
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
                            val rows = dbWritable.update("maintenance_requests", cv, "id=?", arrayOf(requestId.toString()))
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "update_maintenance", "تحديث حالة الصيانة $requestId إلى $status")
                        }
                    }
                    "delete_maintenance" -> {
                        val requestId = params["request_id"]?.firstOrNull()?.toLongOrNull() ?: 0
                        if (requestId <= 0) {
                            responseJson.put("success", false)
                            responseJson.put("error", "معرف غير صالح")
                        } else {
                            val dbWritable = db.writableDatabase
                            val cv = android.content.ContentValues().apply { put("is_deleted", 1) }
                            val rows = dbWritable.update("maintenance_requests", cv, "id=?", arrayOf(requestId.toString()))
                            responseJson.put("success", rows > 0)
                            if (rows > 0) db.logActivity("system", "delete_maintenance", "حذف طلب صيانة $requestId")
                        }
                    }

                    // ============================================================
                    //  TANKS & PUMPS
                    // ============================================================
                    "get_tanks" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getTanks(1))
                    }
                    "get_pumps" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getPumps(1))
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

                    // ============================================================
                    //  CASHIERS (users with role CASHIER)
                    // ============================================================
                    "get_cashiers" -> {
                        val cashiers = db.getUsersByRole("CASHIER")
                        responseJson.put("success", true)
                        responseJson.put("data", cashiers)
                    }

                    // ============================================================
                    //  NOTIFICATIONS
                    // ============================================================
                    "get_notifications" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getNotifications())
                    }

                    // ============================================================
                    //  SMS LOGS
                    // ============================================================
                    "get_sms_logs" -> {
                        responseJson.put("success", true)
                        responseJson.put("data", db.getSmsLogs())
                    }

                    // ============================================================
                    //  SMS WHITELIST
                    // ============================================================
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

                    // ============================================================
                    //  REPORTS
                    // ============================================================
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

                    // ============================================================
                    //  AI
                    // ============================================================
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

                    // ============================================================
                    //  SETTINGS
                    // ============================================================
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

                    // ============================================================
                    //  UNKNOWN ACTION
                    // ============================================================
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
    }

    // ================================================================
    //  AI FALLBACK
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
    //  SMS SENDING
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
