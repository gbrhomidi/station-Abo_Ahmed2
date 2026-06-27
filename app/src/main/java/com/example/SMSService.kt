package com.aistudio.dieselstationsms.kxmpzq

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask

class SMSService : Service() {

    private var server: ApiServer? = null
    private var backupTimer: Timer? = null
    private val GEMINI_API_KEY = "AQ.Ab8RN6I0aFiSTYYPG2gsxkbZG0cZ90lpCSQU0ZjCFj7npGP9tA"
    private val GEMINI_PROJECT_ID = "481549332362"

    override fun onCreate() {
        super.onCreate()
        server = ApiServer()
        try {
            server?.start()
            Log.d("SMSService", "Server started at port 8080")
        } catch (e: IOException) {
            e.printStackTrace()
        }
        setupAutoBackup()
    }

    private fun setupAutoBackup() {
        backupTimer = Timer()
        backupTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    val db = DatabaseHelper(this@SMSService)
                    val out = db.exportAllData().toString(2)
                    val dir = File(filesDir, "backups")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "auto_backup_${System.currentTimeMillis()}.json")
                    val writer = FileWriter(file)
                    writer.write(out)
                    writer.close()
                    Log.d("SMSService", "Auto backup completed")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, 1000 * 60 * 60, 1000 * 60 * 60 * 24)
    }

    override fun onDestroy() {
        server?.stop()
        backupTimer?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ==================== GEMINI AI ====================
    private fun callGeminiAPI(prompt: String): String {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$GEMINI_API_KEY")
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

            conn.outputStream.write(requestBody.toString().toByteArray())

            val responseCode = conn.responseCode
            val response = if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{\"error\": \"HTTP $responseCode\"}"
            }
            conn.disconnect()

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
            Log.e("Gemini", "Error: ${e.message}")
            "عذراً، حدث خطأ في الاتصال بـ Gemini: ${e.message}"
        }
    }

    private fun generateAIInsight(stats: JSONObject, sales: JSONArray): String {
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
        if (sales.length() < 3) return "لا توجد بيانات كافية للتوقع"

        val prompt = """
            أنت محلل بيانات لمحطة وقود.
            بناءً على بيانات المبيعات اليومية التالية، قدم توقعاً للمبيعات القادمة:

            ${(0 until sales.length()).joinToString("\n") { i ->
                val s = sales.getJSONObject(i)
                "- ${s.optString("date")}: ${s.optDouble("total_qty", 0.0).toInt()} لتر"
            }}

            قدم توقعاً مختصراً للأسبوع القادم.
        """.trimIndent()

        return callGeminiAPI(prompt)
    }

    private fun analyzeCustomerBehavior(customerData: JSONObject): String {
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
    private inner class ApiServer : NanoHTTPD(8080) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
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

            val db = DatabaseHelper(this@SMSService)
            val responseJson = JSONObject()

            try {
                if (uri.startsWith("/api")) {
                    val params = session.parameters
                    val action = params["action"]?.firstOrNull() ?: ""

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
                            if (userData != null && userData.getInt("biometric_enabled") == 1) {
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
                            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
                            val refillId = params["refill_id"]?.firstOrNull()?.toInt() ?: 0
                            val qty = params["quantity_liters"]?.firstOrNull()?.toDouble() ?: 0.0
                            val price = params["unit_price"]?.firstOrNull()?.toDouble() ?: 0.0
                            val paid = params["paid_amount"]?.firstOrNull()?.toDouble() ?: 0.0
                            val paymentMethod = params["payment_method"]?.firstOrNull() ?: "cash"
                            val dueDate = params["due_date"]?.firstOrNull() ?: ""
                            val paymentType = params["payment_type"]?.firstOrNull() ?: "نقداً"
                            val operator = params["operator"]?.firstOrNull() ?: "System"

                            val total = qty * price
                            val due = total - paid

                            val refill = db.getRefill(refillId)
                            if (refill == null || refill.getDouble("remaining_quantity") < qty) {
                                responseJson.put("success", false)
                                responseJson.put("error", "الكمية غير متوفرة")
                            } else {
                                db.updateRefillQty(refillId, qty, operator)
                                val tid = db.insertTransaction(customerId, refillId, qty, price, paid, due, paymentMethod, dueDate, paymentType, operator)
                                responseJson.put("success", true)
                                responseJson.put("transaction_id", tid)
                                responseJson.put("invoice_number", db.getTransactionById(tid)?.getString("invoice_number"))
                                responseJson.put("message", "تم البيع بنجاح")

                                if (due > 0.0) {
                                    val customer = db.getCustomer(customerId)
                                    val phone = customer?.getString("phone")
                                    if (phone != null) {
                                        val msg = "تذكير: لديك مبلغ مستحق $due ريال قبل $dueDate"
                                        sendSMS(db, phone, msg, "new_sale_due")
                                    }
                                }
                            }
                        }
                        "make_payment" -> {
                            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
                            val amount = params["amount"]?.firstOrNull()?.toDouble() ?: 0.0
                            val operator = params["operator"]?.firstOrNull() ?: "System"
                            db.processPayment(customerId, amount, operator)
                            responseJson.put("success", true)
                            responseJson.put("message", "تم التسديد")
                        }
                        "get_dashboard" -> {
                            val stats = db.getDashboardStats()
                            responseJson.put("success", true)
                            responseJson.put("data", stats)

                            if (db.getSetting("ai_enabled") == "1") {
                                val sales = db.getDailySales()
                                val aiInsight = generateAIInsight(stats.getJSONObject(0), sales)
                                responseJson.put("ai_insight", aiInsight)
                            }
                        }
                        "get_ai_insight" -> {
                            val stats = db.getDashboardStats().getJSONObject(0)
                            val sales = db.getDailySales()
                            responseJson.put("success", true)
                            responseJson.put("insight", generateAIInsight(stats, sales))
                        }
                        "get_sales_forecast" -> {
                            val sales = db.getDailySales()
                            responseJson.put("success", true)
                            responseJson.put("forecast", generateSalesForecast(sales))
                        }
                        "analyze_customer" -> {
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
                        "ai_chat" -> {
                            val message = params["message"]?.firstOrNull() ?: ""
                            val sessionId = params["session_id"]?.firstOrNull() ?: "default"

                            db.saveAiMessage(sessionId, "user", message)

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
                        "send_sms" -> {
                            val phone = params["phone"]?.firstOrNull() ?: ""
                            val msg = params["message"]?.firstOrNull() ?: ""
                            val sent = sendSMS(db, phone, msg, "manual")
                            responseJson.put("success", sent)
                            responseJson.put("message", if (sent) "تم الإرسال" else "فشل")
                        }
                        "export_data" -> {
                            responseJson.put("success", true)
                            responseJson.put("data", db.exportAllData())
                        }
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
                        "send_overdue_sms" -> {
                            val overdue = db.getOverdueTransactions()
                            var sentCount = 0
                            val failedList = JSONArray()
                            for (i in 0 until overdue.length()) {
                                val t = overdue.getJSONObject(i)
                                val phone = t.getString("customer_phone")
                                val name = t.getString("customer_name")
                                val due = t.getDouble("due")
                                val dueDate = t.getString("due_date")
                                val msg = "عزيزي $name، نحيطكم علماً بوجود مبلغ مستحق قدره $due ريال تجاوز تاريخ الاستحقاق ($dueDate). يرجى المبادرة بالتسديد. محطة أبو أحمد."
                                if (sendSMS(db, phone, msg, "overdue_reminder")) sentCount++ else failedList.put(name)
                            }
                            responseJson.put("success", true)
                            responseJson.put("message", "تم إرسال $sentCount رسائل. فشل: ${failedList.length()}")
                            responseJson.put("failed_customers", failedList)
                        }
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
                            db.markAlertRead(alertId)
                            responseJson.put("success", true)
                        }
                        "get_loyalty_history" -> {
                            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
                            responseJson.put("success", true)
                            responseJson.put("data", db.getLoyaltyHistory(customerId))
                        }
                        "redeem_loyalty" -> {
                            val customerId = params["customer_id"]?.firstOrNull()?.toInt() ?: 0
                            val points = params["points"]?.firstOrNull()?.toInt() ?: 0
                            val desc = params["description"]?.firstOrNull() ?: ""
                            val success = db.redeemLoyaltyPoints(customerId, points, desc)
                            responseJson.put("success", success)
                            responseJson.put("message", if (success) "تم استبدال النقاط" else "النقاط غير كافية")
                        }
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
                Log.e("SMSService", "Error: ${e.message}", e)
                responseJson.put("success", false)
                responseJson.put("error", e.message)
            }

            val res = newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
            headers.forEach { (k, v) -> res.addHeader(k, v) }
            return res
        }

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

    private fun sendSMS(db: DatabaseHelper, phone: String, msg: String, type: String): Boolean {
        return try {
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(phone, null, msg, null, null)
            db.logSms(phone, msg, type, "sent")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            db.logSms(phone, msg, type, "failed: ${e.message}")
            false
        }
    }
}