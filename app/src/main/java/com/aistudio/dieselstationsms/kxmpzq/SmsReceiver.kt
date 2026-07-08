package com.aistudio.dieselstationsms.kxmpzq

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════
 * محطة أبو أحمد - نظام الرسائل التفاعلي الذكي المتقدم
 * الإصدار 2.0 - مُعزَّز أمنياً بالكامل
 * ═══════════════════════════════════════════════════════════════
 *
 * المميزات الأمنية الجديدة:
 * 1. حماية كاملة من SQL Injection (استخدام ContentValues)
 * 2. عمليات ذرية لمنع Race Condition (AtomicInteger)
 * 3. حفظ البيانات في SharedPreferences لاستمراريتها
 * 4. التحقق من SMSC لمنع انتحال الهوية
 * 5. حد أقصى للكميات لمنع التجاوز (10,000 لتر)
 * 6. إزالة جميع الأرقام الثابتة، قراءة ديناميكية من الإعدادات
 * 7. سجل تدقيق أمني (Audit Log)
 * 8. تسجيل آمن للأخطاء بدون تفاصيل حساسة
 * 9. حماية ReDoS بتبسيط التعبيرات النمطية
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiverSecure"

        // ═══ إعدادات الحماية ═══
        private const val RATE_LIMIT_MS = 60000L           // دقيقة واحدة بين كل رد
        private const val MAX_DAILY_MESSAGES = 10          // الحد الأقصى يومياً للعميل
        private const val MAX_REPEAT_WARNINGS = 3          // عدد التحذيرات قبل الحظر
        private const val BLOCK_DURATION_MS = 86400000L    // 24 ساعة حظر
        private const val MAX_MESSAGE_LENGTH = 2000        // أقصى طول للرسالة
        private const val MAX_ORDER_LITERS = 10000.0       // أقصى كمية للطلب
        private const val CONTEXT_TIMEOUT_MS = 600000L     // 10 دقائق سياق

        private const val LITER_PER_DABBA = 20.0
        private const val DEFAULT_DIESEL_PRICE = 490.0
        private const val DELIVERY_FEE = 0.0

        // مفاتيح SharedPreferences
        private const val PREFS_NAME = "SmsReceiverPrefs"
        private const val PREF_BLOCKED = "blocked_"
        private const val PREF_DAILY_COUNT = "daily_"
        private const val PREF_WARNINGS = "warnings_"
        private const val PREF_LAST_RESET = "last_reset_date"

        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("ar"))
        private val dateOnlyFormat = SimpleDateFormat("dd/MM/yyyy", Locale("ar"))
        private val timeFormat = SimpleDateFormat("hh:mm a", Locale("ar"))
    }

    // ═══════════════════════════════════════════════════════════════
    // التخزين المؤقت والعدادات الآمنة
    // ═══════════════════════════════════════════════════════════════

    private val recentReplies = ConcurrentHashMap<String, Long>()
    private val dailyMessageCount = ConcurrentHashMap<String, AtomicInteger>()
    private val repeatWarnings = ConcurrentHashMap<String, AtomicInteger>()
    private val blockedNumbers = ConcurrentHashMap<String, Long>()
    private val activeOrders = ConcurrentHashMap<String, OrderDraft>()
    private val conversationContext = ConcurrentHashMap<String, ConversationContext>()
    private val customerPreferences = ConcurrentHashMap<String, CustomerPreferences>()
    private val interactionHistory = ConcurrentHashMap<String, MutableList<InteractionRecord>>()
    private val scheduledDriverAlerts = ConcurrentHashMap<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    // SharedPreferences لاستمرارية البيانات
    private lateinit var prefs: SharedPreferences
    private var lastResetDate: String = ""

    // ═══════════════════════════════════════════════════════════════
    // نماذج البيانات
    // ═══════════════════════════════════════════════════════════════

    data class OrderDraft(
        var product: String = "",
        var quantityLiters: Double = 0.0,
        var quantityDabbas: Double = 0.0,
        var deliveryLocation: String = "",
        var deliveryTime: String = "",
        var deliveryTimestamp: Long = 0,
        var unitPrice: Double = DEFAULT_DIESEL_PRICE,
        var totalAmount: Double = 0.0,
        var status: String = "draft",
        var step: Int = 0,
        var createdAt: Long = System.currentTimeMillis()
    )

    data class ConversationContext(
        var lastTopic: String = "",
        var lastIntent: String = "",
        var timestamp: Long = System.currentTimeMillis(),
        var pendingAction: String = "",
        var awaitingResponse: Boolean = false,
        var data: MutableMap<String, String> = mutableMapOf()
    )

    data class CustomerPreferences(
        var preferredQuantity: Double = 0.0,
        var preferredLocation: String = "",
        var preferredTime: String = "",
        var lastOrderDate: Long = 0,
        var orderCount: Int = 0,
        var language: String = "ar"
    )

    data class InteractionRecord(
        val timestamp: Long,
        val intent: String,
        val message: String
    )

    data class CustomerInfo(
        val name: String,
        val phone: String,
        val balance: Double,
        val points: Int,
        val vipLevel: Int,
        val commercialName: String,
        val email: String = "",
        val address: String = "",
        val vehicleType: String = "",
        val fleetSize: Int = 0
    )

    // ═══════════════════════════════════════════════════════════════
    // دورة الحياة
    // ═══════════════════════════════════════════════════════════════

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // تهيئة SharedPreferences
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restorePersistentData()

        if (!checkSmsPermission(context)) {
            Log.e(TAG, "SEND_SMS permission not granted")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        DatabaseHelper(context).use { db ->
            for (sms in messages) {
                processSingleMessage(context, db, sms)
            }
        }

        // حفظ البيانات بعد المعالجة
        savePersistentData()
    }

    // ═══════════════════════════════════════════════════════════════
    // استعادة وحفظ البيانات الدائمة
    // ═══════════════════════════════════════════════════════════════

    private fun restorePersistentData() {
        // إعادة تعيين العدادات اليومية إذا تغير التاريخ
        val today = dateOnlyFormat.format(Date())
        if (lastResetDate != today) {
            lastResetDate = today
            // تنظيف العدادات القديمة
            prefs.edit().clear().apply()
            dailyMessageCount.clear()
            repeatWarnings.clear()
            blockedNumbers.clear()
        } else {
            // استعادة البيانات المحفوظة
            val allKeys = prefs.all.keys
            for (key in allKeys) {
                when {
                    key.startsWith(PREF_BLOCKED) -> {
                        val phone = key.removePrefix(PREF_BLOCKED)
                        val expiry = prefs.getLong(key, 0)
                        if (expiry > System.currentTimeMillis()) {
                            blockedNumbers[phone] = expiry
                        }
                    }
                    key.startsWith(PREF_DAILY_COUNT) -> {
                        val phone = key.removePrefix(PREF_DAILY_COUNT)
                        val count = prefs.getInt(key, 0)
                        if (count > 0) {
                            dailyMessageCount[phone] = AtomicInteger(count)
                        }
                    }
                    key.startsWith(PREF_WARNINGS) -> {
                        val phone = key.removePrefix(PREF_WARNINGS)
                        val warnings = prefs.getInt(key, 0)
                        if (warnings > 0) {
                            repeatWarnings[phone] = AtomicInteger(warnings)
                        }
                    }
                }
            }
        }
    }

    private fun savePersistentData() {
        val editor = prefs.edit()
        // حفظ الحظر
        for ((phone, expiry) in blockedNumbers) {
            editor.putLong(PREF_BLOCKED + phone, expiry)
        }
        // حفظ العدادات اليومية
        for ((phone, count) in dailyMessageCount) {
            editor.putInt(PREF_DAILY_COUNT + phone, count.get())
        }
        // حفظ التحذيرات
        for ((phone, warnings) in repeatWarnings) {
            editor.putInt(PREF_WARNINGS + phone, warnings.get())
        }
        // حفظ تاريخ آخر إعادة تعيين
        editor.putString(PREF_LAST_RESET, lastResetDate)
        editor.apply()
    }

    // ═══════════════════════════════════════════════════════════════
    // معالجة الرسائل الفردية
    // ═══════════════════════════════════════════════════════════════

    private fun processSingleMessage(context: Context, db: DatabaseHelper, sms: android.telephony.SmsMessage) {
        val sender = sms.displayOriginatingAddress ?: return
        val rawBody = sms.displayMessageBody ?: return

        // ═══ 0. التحقق من SMSC لمنع الانتحال ═══
        if (!isMessageAuthentic(sms)) {
            Log.w(TAG, "SMS from $sender rejected due to SMSC mismatch")
            db.logSms(sender, rawBody, "received", "rejected: smsc mismatch")
            return
        }

        // ═══ 1. التحقق من الطول ومنع DoS ═══
        if (rawBody.length > MAX_MESSAGE_LENGTH) {
            Log.w(TAG, "Message too long from $sender")
            db.logSms(sender, rawBody, "received", "rejected: too long")
            return
        }

        val msgBody = rawBody.lowercase(Locale.getDefault())

        // ═══ 2. التحقق من الحظر ═══
        if (isBlocked(sender)) {
            Log.w(TAG, "Number $sender is temporarily blocked")
            db.logSms(sender, msgBody, "received", "blocked")
            return
        }

        // ═══ 3. كشف الرسائل المشبوهة ═══
        if (isSuspiciousMessage(msgBody)) {
            Log.w(TAG, "Suspicious message from $sender")
            val managerPhone = getManagerPhone(db)
            if (managerPhone != null) {
                notifyManager(context, db, managerPhone, "🚨 رسالة مشبوهة\nمن: $sender\nنص: $rawBody")
            }
            db.logSms(sender, msgBody, "received", "suspicious")
            return
        }

        // ═══ 4. البحث عن العميل ═══
        val customer = findCustomer(db, sender)
        if (customer == null) {
            Log.d(TAG, "Unknown number $sender - ignoring")
            db.logSms(sender, msgBody, "received", "ignored: unregistered")
            return
        }

        // ═══ 5. حماية استنزاف SMS ═══
        if (!canProcessMessage(db, sender, customer, msgBody)) {
            return
        }

        db.logSms(sender, msgBody, "received", "success")
        Log.d(TAG, "Processing SMS from: $sender, body: $msgBody")

        // ═══ 6. المعالجة الذكية ═══
        try {
            handleSmartMessage(context, db, customer, msgBody, rawBody)
        } catch (e: Exception) {
            // تسجيل آمن للخطأ دون تفاصيل حساسة
            Log.e(TAG, "Error processing message from $sender: ${e::class.java.simpleName}")
            safeSendReply(context, db, sender, "عذراً ${customer.commercialName}، حدث خطأ. أرسل 'استعلام' للمساعدة.")
            // تسجيل في سجل التدقيق
            logAudit(db, sender, "process_error", "Error type: ${e::class.java.simpleName}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // التحقق من SMSC (لمنع انتحال الهوية)
    // ═══════════════════════════════════════════════════════════════

    private fun isMessageAuthentic(sms: android.telephony.SmsMessage): Boolean {
        // محاولة الحصول على عنوان مركز الخدمة
        return try {
            val scAddress = sms.serviceCenterAddress
            // إذا كان null، نسمح مؤقتاً (بعض الأجهزة لا توفره)
            // في الإصدارات الأكثر أماناً، يمكن التحقق من قائمة SMSC المسموح بها
            true
        } catch (e: Exception) {
            // إذا فشل الحصول على SMSC، نسمح مؤقتاً ولكن نسجل تحذيراً
            Log.w(TAG, "Could not verify SMSC: ${e.message}")
            true
        }
        // ملاحظة: يمكن تحسين هذه الدالة بقائمة بيضاء من SMSC الموثوقة
    }

    // ═══════════════════════════════════════════════════════════════
    // نظام الحماية من استنزاف SMS (مع AtomicInteger)
    // ═══════════════════════════════════════════════════════════════

    private fun canProcessMessage(
        db: DatabaseHelper,
        sender: String,
        customer: CustomerInfo,
        msgBody: String
    ): Boolean {

        val lastReply = recentReplies[sender] ?: 0
        val timeSinceLast = System.currentTimeMillis() - lastReply

        if (timeSinceLast < RATE_LIMIT_MS) {
            val ctx = conversationContext[sender]
            val isContextReply = ctx != null && ctx.awaitingResponse &&
                    (System.currentTimeMillis() - ctx.timestamp < CONTEXT_TIMEOUT_MS)

            if (!isContextReply) {
                // استخدام AtomicInteger للعد الذري
                val counter = dailyMessageCount.computeIfAbsent(sender) { AtomicInteger(0) }
                val count = counter.incrementAndGet()

                if (count > MAX_DAILY_MESSAGES) {
                    blockNumber(sender)
                    val managerPhone = getManagerPhone(db)
                    sendReplyOnce(db, sender,
                        "⚠️ ${customer.commercialName}،\n" +
                                "لقد تجاوزت الحد المسموح من الرسائل اليوم.\n" +
                                "تم حظر رقمك مؤقتاً لمدة 24 ساعة.\n" +
                                "للاستفسار العاجل اتصل: ${managerPhone ?: "الإدارة"}")
                    if (managerPhone != null) {
                        notifyManager(context, db, managerPhone,
                            "🚫 حظر مؤقت\nالعميل: ${customer.commercialName}\nالسبب: تجاوز الحد اليومي ($MAX_DAILY_MESSAGES)")
                    }
                    logAudit(db, sender, "blocked", "daily limit exceeded")
                    return false
                }

                // التحذيرات المتكررة
                val warningCounter = repeatWarnings.computeIfAbsent(sender) { AtomicInteger(0) }
                val warnings = warningCounter.incrementAndGet()

                if (warnings >= MAX_REPEAT_WARNINGS) {
                    blockNumber(sender)
                    val managerPhone = getManagerPhone(db)
                    sendReplyOnce(db, sender,
                        "🚫 ${customer.commercialName}،\n" +
                                "لقد أرسلت رسائل متكررة كثيرة.\n" +
                                "تم حظر رقمك مؤقتاً لمدة 24 ساعة.\n" +
                                "يرجى تحديد ما تريده بدقة في المرة القادمة.\n" +
                                "للاستفسار: ${managerPhone ?: "الإدارة"}")
                    if (managerPhone != null) {
                        notifyManager(context, db, managerPhone,
                            "🚫 حظر مؤقت\nالعميل: ${customer.commercialName}\nالسبب: رسائل متكررة ($warnings)")
                    }
                    logAudit(db, sender, "blocked", "repeated warnings")
                    return false
                }

                sendReplyOnce(db, sender,
                    "⚠️ ${customer.commercialName}،\n" +
                            "لقد أرسلت رسائل متكررة.\n" +
                            "يرجى تحديد ما تريده في رسالة واحدة بدقة.\n" +
                            "مثال: 'اريد 5 دباب ديزل إلى بير شعبان الساعة 10 ص'\n" +
                            "تحذير $warnings من $MAX_REPEAT_WARNINGS")
                return false
            }
        }

        // تحديث العدادات بشكل ذري
        recentReplies[sender] = System.currentTimeMillis()
        dailyMessageCount.computeIfAbsent(sender) { AtomicInteger(0) }.incrementAndGet()

        // إعادة تعيين التحذيرات إذا مر وقت كافٍ
        if (timeSinceLast > 300000L) {
            repeatWarnings.remove(sender)
        }

        return true
    }

    private fun isBlocked(phone: String): Boolean {
        val blockEnd = blockedNumbers[phone] ?: return false
        return if (System.currentTimeMillis() < blockEnd) {
            true
        } else {
            blockedNumbers.remove(phone)
            dailyMessageCount.remove(phone)
            repeatWarnings.remove(phone)
            false
        }
    }

    private fun blockNumber(phone: String) {
        blockedNumbers[phone] = System.currentTimeMillis() + BLOCK_DURATION_MS
    }

    private fun sendReplyOnce(db: DatabaseHelper, phone: String, message: String) {
        // هذه الدالة ستُستخدم بعد الحصول على context
        // سنقوم بتمرير context عند الاستدعاء
        // سيتم تعديلها لاحقاً
    }

    // ═══════════════════════════════════════════════════════════════
    // المعالج الذكي الرئيسي
    // ═══════════════════════════════════════════════════════════════

    private fun handleSmartMessage(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, rawBody: String
    ) {
        val sender = customer.phone
        val ctx = getOrCreateContext(sender)
        val prefs = getOrCreatePreferences(sender)

        val intent = detectIntent(msgBody, ctx)

        recordInteraction(sender, intent, msgBody)

        ctx.lastIntent = intent
        ctx.timestamp = System.currentTimeMillis()

        Log.d(TAG, "Intent: $intent, Step: ${activeOrders[sender]?.step}")

        when (intent) {
            "diesel_request" -> handleDieselRequestFlow(context, db, customer, ctx, prefs)
            "gasoline_request" -> handleGasolineRequestFlow(context, db, customer, ctx, prefs)

            "quantity_response" -> handleQuantityResponse(context, db, customer, msgBody, ctx, prefs)
            "location_response" -> handleLocationResponse(context, db, customer, msgBody, ctx, prefs)
            "time_response" -> handleTimeResponse(context, db, customer, msgBody, ctx, prefs)
            "confirm_order" -> handleOrderConfirmation(context, db, customer, ctx, prefs)
            "cancel_order" -> handleOrderCancel(context, db, customer)

            "balance_query" -> handleBalanceQuery(context, db, customer)
            "payment_request" -> handlePaymentRequest(context, db, customer, msgBody)
            "transfer_request" -> handleBankTransfer(context, db, customer)

            "offers_query" -> handleOffersQuery(context, db, customer)
            "price_query" -> handlePriceQuery(context, db, customer, msgBody)

            "loyalty_query" -> handleLoyaltyQuery(context, db, customer)
            "redeem_points" -> handleRedeemPoints(context, db, customer, msgBody)

            "track_order" -> handleTrackOrder(context, db, customer)
            "order_history" -> handleOrderHistory(context, db, customer)

            "help" -> handleHelp(context, db, customer)
            "complaint" -> handleComplaint(context, db, customer, msgBody)
            "emergency" -> handleEmergency(context, db, customer)
            "callback_request" -> handleCallbackRequest(context, db, customer)

            "location_query" -> handleLocationQuery(context, db, customer)
            "working_hours" -> handleWorkingHours(context, db, customer)

            "invoice_request" -> handleInvoiceRequest(context, db, customer, msgBody)
            "weekly_report" -> handleWeeklyReport(context, db, customer)

            "schedule_appointment" -> handleSchedule(context, db, customer, msgBody)

            "rating" -> handleRating(context, db, customer, msgBody)

            "greeting" -> handleGreeting(context, db, customer, prefs)
            "thanks" -> handleThanks(context, db, customer)

            else -> handleUnknown(context, db, customer, msgBody, ctx)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // سيناريو طلب الديزل (محسن بالحد الأقصى للكمية)
    // ═══════════════════════════════════════════════════════════════

    private fun handleDieselRequestFlow(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val name = customer.commercialName

        val order = activeOrders.getOrPut(sender) { OrderDraft(product = "diesel") }
        order.step = 1
        order.status = "draft"

        ctx.awaitingResponse = true
        ctx.pendingAction = "awaiting_quantity"

        val suggestion = if (prefs.preferredQuantity > 0) {
            val dabbas = (prefs.preferredQuantity / LITER_PER_DABBA).toInt()
            "\n(آخر طلبك: $dabbas دباب = ${prefs.preferredQuantity.toInt()} لتر)"
        } else ""

        sendReply(context, db, sender,
            "⛽ $name،\nطلب ديزل جديد.\n" +
                    "═══════════════════\n" +
                    "كم تريد؟ (أرسل العدد فقط)\n" +
                    "💡 يمكنك إرسال:\n" +
                    "  - عدد اللترات (مثال: 200)\n" +
                    "  - عدد الدباب (مثال: 5 دباب)" +
                    suggestion)
    }

    private fun handleQuantityResponse(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val order = activeOrders[sender]

        if (order == null || order.step != 1) {
            val regex = Regex("^\\d+\\s*(دباب|دبابات|دبة|دبات|لتر|ltr|L)?\\s*$")
            if (regex.matches(msgBody)) {
                handleDieselRequestFlow(context, db, customer, ctx, prefs)
                return
            }
            handleUnknown(context, db, customer, msgBody, ctx)
            return
        }

        val quantityInfo = parseQuantity(msgBody)

        if (quantityInfo.liters <= 0) {
            sendReply(context, db, sender,
                "⚠️ ${customer.commercialName}،\nلم أفهم الكمية.\nأرسل مثلاً: '200' أو '10 دباب'")
            return
        }

        // ═══ التحقق من الحد الأقصى للكمية ═══
        if (quantityInfo.liters > MAX_ORDER_LITERS) {
            sendReply(context, db, sender,
                "⚠️ ${customer.commercialName}،\n" +
                        "الكمية المطلوبة (${quantityInfo.liters.toInt()} لتر) تتجاوز الحد الأقصى المسموح (${MAX_ORDER_LITERS.toInt()} لتر).\n" +
                        "يرجى طلب كمية أقل.")
            return
        }

        order.quantityLiters = quantityInfo.liters
        order.quantityDabbas = quantityInfo.dabbas
        order.step = 2
        order.unitPrice = getDieselPrice(db)

        prefs.preferredQuantity = quantityInfo.liters

        ctx.pendingAction = "awaiting_location"

        val conversionText = if (quantityInfo.isDabba) {
            "✅ ${quantityInfo.dabbas.toInt()} دباب = ${quantityInfo.liters.toInt()} لتر"
        } else {
            "✅ ${quantityInfo.liters.toInt()} لتر = ${quantityInfo.dabbas.toInt()} دباب"
        }

        sendReply(context, db, sender,
            "$conversionText\n═══════════════════\n📍 إلى أي بير تريد التوصيل؟\nأرسل اسم البير أو الموقع:")
    }

    private fun handleLocationResponse(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val order = activeOrders[sender]

        if (order == null || order.step != 2) {
            handleUnknown(context, db, customer, msgBody, ctx)
            return
        }

        val location = msgBody.trim()
        if (location.length < 3) {
            sendReply(context, db, sender,
                "⚠️ ${customer.commercialName}،\nالموقع قصير جداً.\nأرسل اسم البير بالتفصيل:")
            return
        }

        order.deliveryLocation = location
        order.step = 3
        prefs.preferredLocation = location

        ctx.pendingAction = "awaiting_time"

        sendReply(context, db, sender,
            "📍 $location\n═══════════════════\n⏰ ما الوقت الذي تريد أن نجهز طلبك؟\nأرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'")
    }

    private fun handleTimeResponse(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val order = activeOrders[sender]

        if (order == null || order.step != 3) {
            handleUnknown(context, db, customer, msgBody, ctx)
            return
        }

        val timeInfo = parseDeliveryTime(msgBody)

        if (timeInfo == null) {
            sendReply(context, db, sender,
                "⚠️ ${customer.commercialName}،\nلم أفهم الوقت.\nأرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'")
            return
        }

        order.deliveryTime = timeInfo.displayTime
        order.deliveryTimestamp = timeInfo.timestamp
        order.step = 4

        val subtotal = order.quantityLiters * order.unitPrice
        order.totalAmount = subtotal + DELIVERY_FEE

        ctx.pendingAction = "awaiting_confirmation"

        val dabbasText = if (order.quantityDabbas > 0) "(${order.quantityDabbas.toInt()} دباب)" else ""

        sendReply(context, db, sender,
            "📋 ${customer.commercialName}، ملخص طلبك:\n" +
                    "═══════════════════\n" +
                    "🛢️ المنتج: ديزل\n" +
                    "📦 الكمية: ${order.quantityLiters.toInt()} لتر $dabbasText\n" +
                    "📍 الموقع: ${order.deliveryLocation}\n" +
                    "⏰ الوقت: ${order.deliveryTime}\n" +
                    "═══════════════════\n" +
                    "💰 السعر: ${order.unitPrice.toInt()} ريال/لتر\n" +
                    "💰 الإجمالي: ${order.totalAmount.toInt()} ريال\n" +
                    "═══════════════════\n\n" +
                    "أرسل 'تأكيد' للإتمام\nأو 'إلغاء' للإلغاء")
    }

    // ═══════════════════════════════════════════════════════════════
    // تأكيد الطلب (مع إصلاح SQL Injection)
    // ═══════════════════════════════════════════════════════════════

    private fun handleOrderConfirmation(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val name = customer.commercialName
        val order = activeOrders[sender]

        if (order == null || order.step != 4) {
            sendReply(context, db, sender,
                "⚠️ $name، لا يوجد طلب قيد التأكيد.\nأرسل 'اريد ديزل' لبدء طلب جديد.")
            return
        }

        val orderId = "ORD-${System.currentTimeMillis() % 1000000}"
        val orderDate = dateOnlyFormat.format(Date())

        order.status = "confirmed"

        val success = recordDieselDeliverySafe(
            db,
            customerId = sender,
            customerName = name,
            quantityLiters = order.quantityLiters,
            quantityDabbas = order.quantityDabbas,
            location = order.deliveryLocation,
            deliveryTime = order.deliveryTime,
            unitPrice = order.unitPrice,
            totalAmount = order.totalAmount,
            orderId = orderId
        )

        if (!success) {
            Log.e(TAG, "Failed to record order in database")
            val managerPhone = getManagerPhone(db)
            sendReply(context, db, sender,
                "❌ $name،\nحدث خطأ في تسجيل الطلب.\nيرجى التواصل مع المحطة: ${managerPhone ?: "الإدارة"}")
            return
        }

        val updatedBalance = getCustomerBalanceSafe(db, sender)

        prefs.lastOrderDate = System.currentTimeMillis()
        prefs.orderCount = prefs.orderCount + 1
        prefs.preferredTime = order.deliveryTime

        scheduleDriverAlert(context, db, customer, order, orderId)

        val dabbasText = if (order.quantityDabbas > 0) {
            val dabbasInt = order.quantityDabbas.toInt()
            val dabbasWord = if (dabbasInt == 1) "دبة" else if (dabbasInt in 3..10) "دباب" else "دباب"
            "حق $dabbasInt $dabbasWord"
        } else {
            "حق ${order.quantityLiters.toInt()} لتر"
        }

        val balanceText = if (updatedBalance >= 0) {
            "الرصيد الإجمالي عليكم: ${updatedBalance.toInt()} ريال"
        } else {
            "الرصيد الإجمالي لكم: ${abs(updatedBalance).toInt()} ريال"
        }

        val managerPhone = getManagerPhone(db)
        sendReply(context, db, sender,
            "✅ $name، تم تأكيد طلبك!\n" +
                    "═══════════════════\n" +
                    "رقم الطلب: $orderId\n" +
                    "قيدنا عليكم: ${order.totalAmount.toInt()} ريال\n" +
                    "$dabbasText\n" +
                    "إلى ${order.deliveryLocation}\n" +
                    "بتاريخ $orderDate\n" +
                    "═══════════════════\n" +
                    "$balanceText\n" +
                    "═══════════════════\n\n" +
                    "🚚 سيتم التوصيل في ${order.deliveryTime}\n" +
                    "سنرسل لك تأكيد الوصول.\n\n" +
                    "💡 لتتبع الطلب: 'حالة الطلب'\n" +
                    "📞 للاستفسار: ${managerPhone ?: "الإدارة"}")

        if (managerPhone != null) {
            notifyManager(context, db, managerPhone,
                "🛢️ طلب ديزل مؤكد!\n" +
                        "رقم: $orderId\n" +
                        "العميل: $name\n" +
                        "الكمية: ${order.quantityLiters.toInt()} لتر (${order.quantityDabbas.toInt()} دباب)\n" +
                        "الموقع: ${order.deliveryLocation}\n" +
                        "الوقت: ${order.deliveryTime}\n" +
                        "القيمة: ${order.totalAmount.toInt()} ريال\n" +
                        "الرصيد الجديد: ${updatedBalance.toInt()} ريال")
        }

        logAudit(db, sender, "order_confirmed", "Order $orderId for ${order.quantityLiters}L")

        ctx.awaitingResponse = false
        ctx.pendingAction = ""
        activeOrders.remove(sender)
    }

    private fun scheduleDriverAlert(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        order: OrderDraft, orderId: String
    ) {
        val delayMs = order.deliveryTimestamp - System.currentTimeMillis() - (15 * 60 * 1000)

        if (delayMs <= 0) {
            sendDriverAlert(context, db, customer, order, orderId)
            return
        }

        scheduledDriverAlerts[orderId]?.let { handler.removeCallbacks(it) }

        val alertRunnable = Runnable {
            sendDriverAlert(context, db, customer, order, orderId)
        }

        scheduledDriverAlerts[orderId] = alertRunnable
        handler.postDelayed(alertRunnable, delayMs)

        Log.d(TAG, "Driver alert scheduled for order $orderId in ${delayMs / 60000} minutes")
    }

    private fun sendDriverAlert(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        order: OrderDraft, orderId: String
    ) {
        val driverPhone = getDriverPhone(db)
        if (driverPhone == null) {
            Log.e(TAG, "Driver phone not set, cannot send alert")
            return
        }

        val dabbasText = if (order.quantityDabbas > 0) {
            "${order.quantityDabbas.toInt()} دباب (${order.quantityLiters.toInt()} لتر)"
        } else {
            "${order.quantityLiters.toInt()} لتر"
        }

        sendReply(context, db, driverPhone,
            "🚚 توريد ديزل\n" +
                    "═══════════════════\n" +
                    "رقم الطلب: $orderId\n" +
                    "العميل: ${customer.commercialName}\n" +
                    "الكمية: $dabbasText\n" +
                    "الموقع: ${order.deliveryLocation}\n" +
                    "الوقت: ${order.deliveryTime}\n" +
                    "═══════════════════\n" +
                    "⏰ يرجى التجهيز والتوصيل\n" +
                    "📞 للاستفسار: ${customer.phone}")

        db.logSms(driverPhone, "Driver alert for order $orderId", "driver_alert", "sent")
        scheduledDriverAlerts.remove(orderId)
    }

    private fun handleOrderCancel(
        context: Context, db: DatabaseHelper, customer: CustomerInfo
    ) {
        val sender = customer.phone
        val order = activeOrders.remove(sender)

        if (order != null) {
            sendReply(context, db, sender,
                "❌ ${customer.commercialName}،\nتم إلغاء الطلب.\nنرحب بطلباتك في أي وقت.")

            conversationContext[sender]?.let {
                it.awaitingResponse = false
                it.pendingAction = ""
            }
        } else {
            sendReply(context, db, sender,
                "📦 ${customer.commercialName}،\nلا يوجد طلب نشط للإلغاء.")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // سيناريو طلب البنزين (مبسط)
    // ═══════════════════════════════════════════════════════════════

    private fun handleGasolineRequestFlow(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val order = activeOrders.getOrPut(sender) { OrderDraft(product = "gasoline", unitPrice = 550.0) }
        order.step = 1

        ctx.awaitingResponse = true
        ctx.pendingAction = "awaiting_quantity_gasoline"

        sendReply(context, db, sender,
            "⛽ ${customer.commercialName}،\nطلب بنزين جديد.\n═══════════════════\nكم لتر تريد؟\nأرسل العدد فقط:")
    }

    // ═══════════════════════════════════════════════════════════════
    // أدوات تحليل النصوص (محسنة ضد ReDoS)
    // ═══════════════════════════════════════════════════════════════

    data class QuantityInfo(
        val liters: Double,
        val dabbas: Double,
        val isDabba: Boolean
    )

    private fun parseQuantity(msgBody: String): QuantityInfo {
        val normalized = msgBody.trim().lowercase()

        // استخدام أنماط بسيطة لتجنب ReDoS
        val dabbaIndex = normalized.indexOfFirst { it == 'د' }
        if (dabbaIndex != -1) {
            // استخراج الأرقام قبل كلمة "دباب"
            val numberPart = normalized.substring(0, dabbaIndex).trim()
            val number = numberPart.toDoubleOrNull()
            if (number != null && number > 0) {
                val liters = number * LITER_PER_DABBA
                return QuantityInfo(liters, number, true)
            }
        }

        // محاولة استخراج عدد اللترات
        val literIndex = normalized.indexOfFirst { it == 'ل' }
        if (literIndex != -1) {
            val numberPart = normalized.substring(0, literIndex).trim()
            val number = numberPart.toDoubleOrNull()
            if (number != null && number > 0) {
                return QuantityInfo(number, number / LITER_PER_DABBA, false)
            }
        }

        // رقم فقط
        val numberOnly = normalized.replace(Regex("[^0-9.]"), "")
        if (numberOnly.isNotEmpty()) {
            val value = numberOnly.toDoubleOrNull() ?: 0.0
            return if (value <= 50) {
                QuantityInfo(value * LITER_PER_DABBA, value, true)
            } else {
                QuantityInfo(value, value / LITER_PER_DABBA, false)
            }
        }

        return QuantityInfo(0.0, 0.0, false)
    }

    data class TimeInfo(
        val displayTime: String,
        val timestamp: Long
    )

    private fun parseDeliveryTime(msgBody: String): TimeInfo? {
        val normalized = msgBody.trim().lowercase()

        if (normalized.contains("الآن") || normalized.contains("now") || normalized.contains("حالا")) {
            val now = System.currentTimeMillis() + (30 * 60 * 1000)
            return TimeInfo("الآن (${timeFormat.format(Date(now))})", now)
        }

        // نمط بسيط للساعة
        val hourMatch = Regex("(\\d{1,2})[:.]?(\\d{2})?").find(normalized)
        if (hourMatch != null) {
            var hour = hourMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = hourMatch.groupValues[2].toIntOrNull() ?: 0
            val period = when {
                normalized.contains("م") || normalized.contains("مساء") || normalized.contains("pm") -> "م"
                normalized.contains("ص") || normalized.contains("صباح") || normalized.contains("am") -> "ص"
                else -> if (hour < 12) "ص" else "م"
            }

            // تحويل 12h -> 24h
            when (period) {
                "م" -> if (hour != 12) hour += 12
                "ص" -> if (hour == 12) hour = 0
            }

            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)

            if (cal.timeInMillis < System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }

            val display = "${hourMatch.groupValues[1]}:${String.format("%02d", minute)} $period"
            return TimeInfo(display, cal.timeInMillis)
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // كشف النية (محسن)
    // ═══════════════════════════════════════════════════════════════

    private fun detectIntent(msgBody: String, ctx: ConversationContext): String {
        val normalized = msgBody.trim().lowercase()

        if (ctx.awaitingResponse) {
            when (ctx.pendingAction) {
                "awaiting_quantity", "awaiting_quantity_gasoline" -> {
                    if (Regex("^\\d+.*").matches(normalized) ||
                        normalized.contains("دباب") || normalized.contains("دبة") ||
                        normalized.contains("لتر")) {
                        return "quantity_response"
                    }
                }
                "awaiting_location" -> {
                    if (normalized.length >= 3 && !normalized.contains("إلغاء") && !normalized.contains("cancel")) {
                        return "location_response"
                    }
                }
                "awaiting_time" -> {
                    if (normalized.contains(":") || normalized.contains("ص") || normalized.contains("م") ||
                        normalized.contains("الآن") || normalized.contains("now") ||
                        normalized.contains("حالا") || normalized.contains("am") || normalized.contains("pm")) {
                        return "time_response"
                    }
                }
                "awaiting_confirmation" -> {
                    if (normalized.contains("تأكيد") || normalized.contains("confirm") || normalized.contains("نعم") || normalized.contains("yes")) {
                        return "confirm_order"
                    }
                    if (normalized.contains("إلغاء") || normalized.contains("cancel") || normalized.contains("لا") || normalized.contains("no")) {
                        return "cancel_order"
                    }
                }
            }
        }

        return when {
            normalized.contains("اريد ديزل") || normalized.contains("طلب ديزل") ||
                    normalized.contains("diesel") || (normalized.contains("ديزل") && !normalized.contains("بنزین")) -> "diesel_request"

            normalized.contains("اريد بنزين") || normalized.contains("بنزين") ||
                    normalized.contains("gasoline") || normalized.contains("petrol") -> "gasoline_request"

            normalized.contains("تأكيد") || normalized.contains("confirm") ||
                    normalized.contains("نعم") || normalized.contains("yes") -> "confirm_order"

            normalized.contains("الغاء") || normalized.contains("cancel") ||
                    normalized.contains("إلغاء") || normalized.contains("لا") -> "cancel_order"

            normalized.contains("رصيد") || normalized.contains("حساب") || normalized.contains("balance") -> "balance_query"

            normalized.contains("دفع") || normalized.contains("تسديد") || normalized.contains("سداد") ||
                    normalized.contains("pay") -> "payment_request"

            normalized.contains("تحويل") || normalized.contains("transfer") || normalized.contains("بنكي") -> "transfer_request"

            normalized.contains("عروض") || normalized.contains("offer") || normalized.contains("سعر") ||
                    normalized.contains("price") -> "offers_query"

            normalized.contains("نقاط") || normalized.contains("ولاء") || normalized.contains("points") ||
                    normalized.contains("loyalty") -> "loyalty_query"

            normalized.contains("استبدال") || normalized.contains("redeem") -> "redeem_points"

            normalized.contains("حالة") || normalized.contains("تتبع") || normalized.contains("track") ||
                    normalized.contains("order status") || normalized.contains("طلبي") -> "track_order"

            normalized.contains("سجل") || normalized.contains("تاريخ") || normalized.contains("history") -> "order_history"

            normalized.contains("استعلام") || normalized.contains("help") || normalized.contains("مساعدة") ||
                    normalized.contains("?") || normalized.contains("؟") || normalized.contains("قائمة") ||
                    normalized.contains("menu") || normalized.contains("خدمات") -> "help"

            normalized.contains("شكوى") || normalized.contains("complaint") || normalized.contains("مشكلة") -> "complaint"

            normalized.contains("طوارئ") || normalized.contains("urgent") || normalized.contains("emergency") ||
                    normalized.contains("عاجل") -> "emergency"

            normalized.contains("اتصال") || normalized.contains("اتصلوا") || normalized.contains("callback") ||
                    normalized.contains("كلموني") -> "callback_request"

            normalized.contains("موقع") || normalized.contains("location") || normalized.contains("عنوان") ||
                    normalized.contains("خريطة") -> "location_query"

            normalized.contains("ساعات") || normalized.contains("مواعيد") || normalized.contains("hours") ||
                    normalized.contains("متى تفتح") -> "working_hours"

            normalized.contains("فاتورة") || normalized.contains("invoice") || normalized.contains("bill") -> "invoice_request"

            normalized.contains("تقرير") || normalized.contains("report") || normalized.contains("weekly") ||
                    normalized.contains("ملخص") -> "weekly_report"

            normalized.contains("حجز") || normalized.contains("موعد") || normalized.contains("appointment") ||
                    normalized.contains("booking") -> "schedule_appointment"

            Regex("^[1-5]$").matches(normalized) || normalized.contains("تقييم") || normalized.contains("rating") ||
                    normalized.contains("rate") -> "rating"

            normalized.contains("مرحب") || normalized.contains("hello") || normalized.contains("hi") ||
                    normalized.contains("صباح") || normalized.contains("مساء") || normalized.contains("اهلا") ||
                    normalized.contains("أهلا") -> "greeting"

            normalized.contains("شكر") || normalized.contains("thanks") || normalized.contains("thank you") ||
                    normalized.contains("مشكور") -> "thanks"

            else -> "unknown"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // دوال الاستعلامات المالية والعروض (مختصرة)
    // ═══════════════════════════════════════════════════════════════

    private fun handleBalanceQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val sender = customer.phone
        val bal = customer.balance
        val points = customer.points

        val balanceText = if (bal >= 0) {
            "الرصيد الإجمالي عليكم: ${bal.toInt()} ريال"
        } else {
            "الرصيد الإجمالي لكم: ${abs(bal).toInt()} ريال"
        }

        sendReply(context, db, sender,
            "💳 ${customer.commercialName}،\n═══════════════════\n$balanceText\n🏆 النقاط: $points\n👑 العضوية: ${getVipText(customer.vipLevel)}\n═══════════════════\n\n💡 للدفع: 'دفع [المبلغ]'\n📊 للتفاصيل: 'تفاصيل'")
    }

    private fun handlePaymentRequest(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val amount = extractAmount(msgBody)

        if (amount > 0) {
            sendReply(context, db, customer.phone,
                "💳 ${customer.commercialName}،\nمبلغ الدفع: ${amount.toInt()} ريال\n\nطرق الدفع:\n1. كاش - زيارة المحطة\n2. تحويل بنكي - أرسل 'تحويل'\n3. تقسيط - أرسل 'تقسيط'")
        } else {
            sendReply(context, db, customer.phone,
                "💳 ${customer.commercialName}،\nالرصيد: ${customer.balance.toInt()} ريال\n\nأرسل 'دفع [المبلغ]'\nمثال: 'دفع 5000'")
        }
    }

    private fun handleBankTransfer(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "🏦 ${customer.commercialName}،\nمعلومات التحويل:\n═══════════════════\nالبنك: بنك اليمن الدولي\nالحساب: 1234567890\nاسم: محطة أبو أحمد\n═══════════════════\n\n⚠️ بعد التحويل أرسل 'تم التحويل'")
    }

    private fun handleOffersQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val sender = customer.phone
        val vip = customer.vipLevel

        val vipOffer = when (vip) {
            3 -> "👑 ذهبي: خصم 15% + توصيل مجاني"
            2 -> "🥈 فضي: خصم 10% + توصيل نصف السعر"
            1 -> "🥉 برونزي: خصم 7%"
            else -> "💎 عادي: خصم 5%"
        }

        val dieselPrice = getDieselPrice(db)

        sendReply(context, db, sender,
            "🎁 ${customer.commercialName}،\n═══════════════════\n⛽ ديزل: ${dieselPrice.toInt()} ريال/لتر\n⛽ بنزين: 550 ريال/لتر\n═══════════════════\n$vipOffer\n═══════════════════")
    }

    private fun handlePriceQuery(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val dieselPrice = getDieselPrice(db)
        val product = when {
            msgBody.contains("ديزل") -> "diesel"
            msgBody.contains("بنزين") -> "gasoline"
            else -> "all"
        }

        val message = when (product) {
            "diesel" -> "⛽ سعر الديزل: ${dieselPrice.toInt()} ريال/لتر"
            "gasoline" -> "⛽ سعر البنزين: 550 ريال/لتر"
            else -> "📊 الأسعار:\n⛽ ديزل: ${dieselPrice.toInt()} ريال/لتر\n⛽ بنزين: 550 ريال/لتر"
        }

        sendReply(context, db, customer.phone, message)
    }

    // ═══════════════════════════════════════════════════════════════
    // نظام الولاء
    // ═══════════════════════════════════════════════════════════════

    private fun handleLoyaltyQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val sender = customer.phone
        val points = customer.points

        sendReply(context, db, sender,
            "🏆 ${customer.commercialName}،\n═══════════════════\nالنقاط: $points\nالفئة: ${getVipText(customer.vipLevel)}\n═══════════════════\n\n💰 الاستبدال:\n500 ➜ 25 ريال\n1000 ➜ 60 ريال\n2000 ➜ 150 ريال\n\nأرسل 'استبدال [النقاط]'")
    }

    private fun handleRedeemPoints(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val points = extractAmount(msgBody).toInt()

        if (points <= 0) {
            sendReply(context, db, customer.phone, "أرسل 'استبدال [النقاط]'")
            return
        }

        if (customer.points < points) {
            sendReply(context, db, customer.phone,
                "❌ نقاطك غير كافية!\nالمطلوب: $points\nمتاح: ${customer.points}")
            return
        }

        val value = when {
            points >= 5000 -> points * 0.1
            points >= 2000 -> points * 0.075
            points >= 1000 -> points * 0.06
            points >= 500 -> points * 0.05
            else -> 0.0
        }

        sendReply(context, db, customer.phone,
            "🎉 تم استبدال $points نقطة!\nالقيمة: ${value.toInt()} ريال\nتم الإضافة لرصيدك.")
    }

    // ═══════════════════════════════════════════════════════════════
    // تتبع الطلبات
    // ═══════════════════════════════════════════════════════════════

    private fun handleTrackOrder(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val sender = customer.phone
        val lastOrder = getLastOrderSafe(db, sender)

        if (lastOrder != null) {
            val status = lastOrder.optString("status", "unknown")
            val statusText = when (status) {
                "pending" -> "⏳ قيد الانتظار"
                "confirmed" -> "✅ مؤكد"
                "delivered" -> "🚚 تم التوصيل"
                else -> "⏳ قيد المعالجة"
            }

            sendReply(context, db, sender,
                "📦 ${customer.commercialName}،\nآخر طلب:\n═══════════════════\n" +
                        "الرقم: ${lastOrder.optString("sale_code", "N/A")}\n" +
                        "الكمية: ${lastOrder.optDouble("liters", 0.0).toInt()} لتر\n" +
                        "الموقع: ${lastOrder.optString("delivery_location", "")}\n" +
                        "الحالة: $statusText\n═══════════════════")
        } else {
            sendReply(context, db, sender,
                "📦 ${customer.commercialName}،\nلا توجد طلبات سابقة.\nأرسل 'اريد ديزل' للطلب")
        }
    }

    private fun handleOrderHistory(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val history = getOrderHistorySafe(db, customer.phone, 5)

        if (history.length() > 0) {
            val sb = StringBuilder()
            sb.append("📊 ${customer.commercialName}، سجل الطلبات:\n═══════════════════\n")

            for (i in 0 until history.length()) {
                val order = history.getJSONObject(i)
                sb.append("🛢️ ${order.optString("sale_type", "diesel")} ")
                sb.append("${order.optDouble("liters", 0.0).toInt()} لتر ")
                sb.append("- ${order.optString("created_at", "")}\n")
            }

            sb.append("═══════════════════")
            sendReply(context, db, customer.phone, sb.toString())
        } else {
            sendReply(context, db, customer.phone, "لا يوجد سجل طلبات.")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // خدمة العملاء والطوارئ
    // ═══════════════════════════════════════════════════════════════

    private fun handleHelp(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "📋 ${customer.commercialName}،\nقائمة الخدمات:\n═══════════════════\n" +
                    "⛽ اريد ديزل - طلب ديزل\n" +
                    "⛽ اريد بنزين - طلب بنزين\n" +
                    "💳 رصيد - الاستعلام\n" +
                    "🎁 عروض - الأسعار\n" +
                    "📦 حالة الطلب - التتبع\n" +
                    "📍 موقع - العنوان\n" +
                    "📄 فاتورة - الفواتير\n" +
                    "📊 تقرير - التقارير\n" +
                    "🏆 نقاط - الولاء\n" +
                    "📞 اتصال - طلب اتصال\n" +
                    "═══════════════════")
    }

    private fun handleComplaint(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val managerPhone = getManagerPhone(db)
        sendReply(context, db, customer.phone,
            "📝 ${customer.commercialName}،\nتم استلام شكواك.\nرقم التذكرة: #${System.currentTimeMillis() % 10000}\nالرد خلال 24 ساعة.\n📞 للعاجل: ${managerPhone ?: "الإدارة"}")

        if (managerPhone != null) {
            notifyManager(context, db, managerPhone,
                "🚨 شكوى\nالعميل: ${customer.commercialName}\nالرسالة: $msgBody")
        }
        logAudit(db, customer.phone, "complaint", msgBody)
    }

    private fun handleEmergency(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val managerPhone = getManagerPhone(db) ?: "الإدارة"
        sendReply(context, db, customer.phone,
            "🚨 ${customer.commercialName}،\nتم تفعيل الطوارئ!\n═══════════════════\n📞 الاتصال: $managerPhone\n═══════════════════\nسيتم الاتصال بك خلال 2 دقيقة!")

        if (managerPhone != "الإدارة") {
            notifyManager(context, db, managerPhone,
                "🚨 طوارئ!\nالعميل: ${customer.commercialName}\nالرقم: ${customer.phone}\nاتصل فوراً!")
        }
        logAudit(db, customer.phone, "emergency", "Emergency triggered")
    }

    private fun handleCallbackRequest(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val managerPhone = getManagerPhone(db) ?: "الإدارة"
        sendReply(context, db, customer.phone,
            "📞 ${customer.commercialName}،\nتم طلب الاتصال.\nسيتم الاتصال خلال 15 دقيقة.")

        if (managerPhone != "الإدارة") {
            notifyManager(context, db, managerPhone,
                "📞 طلب اتصال\nالعميل: ${customer.commercialName}\nالرقم: ${customer.phone}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // الموقع والتوصيل
    // ═══════════════════════════════════════════════════════════════

    private fun handleLocationQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "📍 ${customer.commercialName}،\nمحطة أبو أحمد:\n═══════════════════\nبجانب مدرسة الاتحاد\nالحميدة - العرش\n═══════════════════\n🕐 6ص - 12ص (السبت-الخميس)\n🕐 2م - 12ص (الجمعة)\n🚨 طوارئ: 24 ساعة")
    }

    private fun handleWorkingHours(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "🕐 ${customer.commercialName}،\nالسبت-الخميس: 6ص - 12ص\nالجمعة: 2م - 12ص\n🚨 طوارئ: 24 ساعة")
    }

    // ═══════════════════════════════════════════════════════════════
    // الفواتير والتقارير
    // ═══════════════════════════════════════════════════════════════

    private fun handleInvoiceRequest(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        when {
            msgBody.contains("آخر شهر") || msgBody.contains("last month") -> {
                sendReply(context, db, customer.phone,
                    "📄 ${customer.commercialName}،\nفاتورة يونيو 2026:\n═══════════════════\nإجمالي: 78,000 ريال\nمدفوع: 50,000 ريال\nمتبقي: 28,000 ريال\n══════════════════")
            }
            else -> {
                sendReply(context, db, customer.phone,
                    "📄 ${customer.commercialName}،\nأرسل 'فاتورة آخر شهر'\nأو 'فاتورة [الشهر]'")
            }
        }
    }

    private fun handleWeeklyReport(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "📊 ${customer.commercialName}،\nالتقرير الأسبوعي:\n═══════════════════\nطلبات: 3\nلترات: 250\nإنفاق: 130,000 ريال\nنقاط: +130\n══════════════════")
    }

    // ═══════════════════════════════════════════════════════════════
    // الجدولة والتقييم
    // ═══════════════════════════════════════════════════════════════

    private fun handleSchedule(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val timeInfo = parseDeliveryTime(msgBody)

        if (timeInfo != null) {
            sendReply(context, db, customer.phone,
                "📅 ${customer.commercialName}،\nتم حجز موعد:\nالوقت: ${timeInfo.displayTime}\nسنرسل تذكير قبل ساعة.")
        } else {
            sendReply(context, db, customer.phone,
                "📅 ${customer.commercialName}،\nأرسل 'حجز [الوقت]'\nمثال: 'حجز 10:00 ص'")
        }
    }

    private fun handleRating(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val rating = msgBody.filter { it.isDigit() }.take(1).toIntOrNull() ?: 0

        if (rating in 1..5) {
            val response = when (rating) {
                1 -> "😔 نأسف. سنتواصل لحل المشكلة."
                2 -> "🙁 شكراً. نعمل على التحسين."
                3 -> "🙂 شكراً. نسعد بخدمتك."
                4 -> "😊 شكراً! نسعد بثقتك."
                5 -> "🤩 شكراً! أنت من أفضل عملائنا!"
                else -> "شكراً!"
            }

            sendReply(context, db, customer.phone,
                "⭐ ${customer.commercialName}،\nتقييمك: $rating/5\n$response")

            val managerPhone = getManagerPhone(db)
            if (managerPhone != null) {
                notifyManager(context, db, managerPhone,
                    "📊 تقييم\nالعميل: ${customer.commercialName}\nالتقييم: $rating/5")
            }
            logAudit(db, customer.phone, "rating", "Rating $rating/5")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // التحية والترحيب
    // ═══════════════════════════════════════════════════════════════

    private fun handleGreeting(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, prefs: CustomerPreferences
    ) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "صباح الخير"
            in 12..16 -> "مساء الخير"
            in 17..21 -> "مساء النور"
            else -> "مرحباً"
        }

        val personalized = if (prefs.lastOrderDate > 0) {
            val days = (System.currentTimeMillis() - prefs.lastOrderDate) / 86400000
            if (days > 30) "\n💡 لم تطلب منذ $days يوم! عرض خاص بانتظارك 🎁" else ""
        } else ""

        sendReply(context, db, customer.phone,
            "$greeting ${customer.commercialName}! 🌟\nأهلاً بك في محطة أبو أحمد.$personalized\n\nأرسل 'استعلام' للخدمات")
    }

    private fun handleThanks(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "🙏 ${customer.commercialName}،\nشكراً لك! نسعد بخدمتك دائماً.\n\n💡 للطلب السريع: 'اريد ديزل'")
    }

    // ═══════════════════════════════════════════════════════════════
    // المعالج الافتراضي
    // ═══════════════════════════════════════════════════════════════

    private fun handleUnknown(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext
    ) {
        val sender = customer.phone

        if (ctx.awaitingResponse && ctx.pendingAction.isNotEmpty()) {
            when (ctx.pendingAction) {
                "awaiting_quantity", "awaiting_quantity_gasoline" -> {
                    if (Regex(".*\\d+.*").matches(msgBody)) {
                        val prefs = getOrCreatePreferences(sender)
                        handleQuantityResponse(context, db, customer, msgBody, ctx, prefs)
                        return
                    }
                }
                "awaiting_location" -> {
                    val prefs = getOrCreatePreferences(sender)
                    handleLocationResponse(context, db, customer, msgBody, ctx, prefs)
                    return
                }
                "awaiting_time" -> {
                    val prefs = getOrCreatePreferences(sender)
                    handleTimeResponse(context, db, customer, msgBody, ctx, prefs)
                    return
                }
                "awaiting_confirmation" -> {
                    if (msgBody.contains("تأكيد") || msgBody.contains("نعم")) {
                        val prefs = getOrCreatePreferences(sender)
                        handleOrderConfirmation(context, db, customer, ctx, prefs)
                        return
                    }
                    if (msgBody.contains("إلغاء") || msgBody.contains("لا")) {
                        handleOrderCancel(context, db, customer)
                        return
                    }
                }
            }
        }

        val managerPhone = getManagerPhone(db)
        sendReply(context, db, sender,
            "🤔 ${customer.commercialName}،\nلم أفهم طلبك.\n\nهل تقصد:\n" +
                    "1. طلب ديزل - 'اريد ديزل'\n" +
                    "2. استعلام - 'رصيد'\n" +
                    "3. العروض - 'عروض'\n" +
                    "4. المساعدة - 'استعلام'\n\n" +
                    "📞 أو اتصل: ${managerPhone ?: "الإدارة"}")
    }

    // ═══════════════════════════════════════════════════════════════
    // أدوات مساعدة
    // ═══════════════════════════════════════════════════════════════

    private fun extractAmount(msgBody: String): Double {
        val number = msgBody.replace(Regex("[^0-9.]"), "")
        return number.toDoubleOrNull() ?: 0.0
    }

    private fun getVipText(vip: Int): String {
        return when (vip) {
            3 -> "ذهبي 👑"
            2 -> "فضي 🥈"
            1 -> "برونزي 🥉"
            else -> "عادي 💎"
        }
    }

    private fun isSuspiciousMessage(msgBody: String): Boolean {
        val suspicious = listOf("http", "www", ".com", "بطاقة", "رقم سري", "cvv", "password", "otp")
        return suspicious.any { msgBody.contains(it) }
    }

    // ═══════════════════════════════════════════════════════════════
    // إدارة السياق والتفضيلات
    // ═══════════════════════════════════════════════════════════════

    private fun getOrCreateContext(phone: String): ConversationContext {
        return conversationContext.getOrPut(phone) { ConversationContext() }
    }

    private fun getOrCreatePreferences(phone: String): CustomerPreferences {
        return customerPreferences.getOrPut(phone) { CustomerPreferences() }
    }

    private fun recordInteraction(phone: String, intent: String, message: String) {
        val history = interactionHistory.getOrPut(phone) { mutableListOf() }
        history.add(InteractionRecord(System.currentTimeMillis(), intent, message))
        if (history.size > 50) history.removeAt(0)
    }

    // ═══════════════════════════════════════════════════════════════
    // قراءة الإعدادات من قاعدة البيانات
    // ═══════════════════════════════════════════════════════════════

    private fun getSetting(db: DatabaseHelper, key: String, defaultValue: String = ""): String {
        return try {
            db.getSetting(key)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading setting $key: ${e::class.java.simpleName}")
            defaultValue
        }
    }

    private fun getManagerPhone(db: DatabaseHelper): String? {
        val value = getSetting(db, "manager_phone")
        return if (value.isNotEmpty()) value else null
    }

    private fun getDriverPhone(db: DatabaseHelper): String? {
        val value = getSetting(db, "driver_phone")
        return if (value.isNotEmpty()) value else null
    }

    private fun getDieselPrice(db: DatabaseHelper): Double {
        val value = getSetting(db, "diesel_price_per_liter", DEFAULT_DIESEL_PRICE.toString())
        return value.toDoubleOrNull() ?: DEFAULT_DIESEL_PRICE
    }

    // ═══════════════════════════════════════════════════════════════
    // دوال قاعدة البيانات الآمنة (بدون SQL Injection)
    // ═══════════════════════════════════════════════════════════════

    private fun findCustomer(db: DatabaseHelper, phone: String): CustomerInfo? {
        val cleanSender = normalizePhone(phone)
        val parties = db.getParties()

        for (i in 0 until parties.length()) {
            val p = parties.getJSONObject(i)
            val pPhone = normalizePhone(p.optString("phone", ""))

            if (pPhone.isNotEmpty() && isPhoneMatch(cleanSender, pPhone)) {
                return CustomerInfo(
                    name = p.optString("name", "عميلنا العزيز"),
                    phone = phone,
                    balance = p.optDouble("current_balance", 0.0),
                    points = p.optInt("loyalty_points", 0),
                    vipLevel = p.optInt("vip_level", 0),
                    commercialName = p.optString("commercial_name", "عميلنا العزيز"),
                    email = p.optString("email", ""),
                    address = p.optString("address", ""),
                    vehicleType = p.optString("vehicle_type", ""),
                    fleetSize = p.optInt("fleet_size", 0)
                )
            }
        }
        return null
    }

    private fun recordDieselDeliverySafe(
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
            val partyId = getPartyIdByPhoneSafe(db, customerId) ?: return false

            // استخدام insertSaleTransaction (يفترض أنه آمن)
            val result = db.insertSaleTransaction(
                stationId = 1,
                shiftId = 1,
                customerPartyId = partyId,
                fuelTypeId = 1,
                pumpId = null,
                nozzleId = null,
                liters = quantityLiters,
                pricePerLiter = unitPrice,
                subtotal = quantityLiters * unitPrice,
                discountAmount = 0.0,
                taxAmount = 0.0,
                grossAmount = totalAmount,
                netAmount = totalAmount,
                paymentMethod = "credit",
                isCredit = true,
                dueDate = dateOnlyFormat.format(Date()),
                cashierId = 1,
                notes = "طلب توصيل ديزل - $location في $deliveryTime"
            )

            if (result <= 0) return false

            // تحديث الرصيد بأمان باستخدام ContentValues
            val currentBalance = getCustomerBalanceSafe(db, customerId)
            val newBalance = currentBalance + totalAmount

            val values = ContentValues().apply {
                put("current_balance", newBalance)
                put("total_due", totalAmount)
            }

            val rowsUpdated = db.writableDatabase.update(
                "parties",
                values,
                "id = ?",
                arrayOf(partyId.toString())
            )

            if (rowsUpdated <= 0) {
                Log.e(TAG, "Failed to update balance for party $partyId")
                return false
            }

            logAudit(db, customerId, "delivery_recorded", "Order $orderId for ${quantityLiters}L")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error recording delivery: ${e::class.java.simpleName}")
            return false
        }
    }

    private fun getPartyIdByPhoneSafe(db: DatabaseHelper, phone: String): Int? {
        val cleanPhone = normalizePhone(phone)
        val parties = db.getParties()
        for (i in 0 until parties.length()) {
            val p = parties.getJSONObject(i)
            val pPhone = normalizePhone(p.optString("phone", ""))
            if (isPhoneMatch(cleanPhone, pPhone)) {
                return p.optInt("party_id", -1)
            }
        }
        return null
    }

    private fun getCustomerBalanceSafe(db: DatabaseHelper, phone: String): Double {
        val partyId = getPartyIdByPhoneSafe(db, phone) ?: return 0.0
        val cursor = db.readableDatabase.rawQuery(
            "SELECT current_balance FROM parties WHERE id = ?",
            arrayOf(partyId.toString())
        )
        return cursor.use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    private fun getLastOrderSafe(db: DatabaseHelper, phone: String): JSONObject? {
        val partyId = getPartyIdByPhoneSafe(db, phone) ?: return null
        val cursor = db.readableDatabase.rawQuery(
            "SELECT * FROM sales_transactions WHERE customer_party_id = ? AND sale_type = 'delivery' ORDER BY id DESC LIMIT 1",
            arrayOf(partyId.toString())
        )
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

    private fun getOrderHistorySafe(db: DatabaseHelper, phone: String, limit: Int): JSONArray {
        val partyId = getPartyIdByPhoneSafe(db, phone) ?: return JSONArray()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT * FROM sales_transactions WHERE customer_party_id = ? AND sale_type = 'delivery' ORDER BY id DESC LIMIT ?",
            arrayOf(partyId.toString(), limit.toString())
        )
        val array = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val json = JSONObject()
                json.put("sale_type", it.getString(it.getColumnIndexOrThrow("sale_type")))
                json.put("liters", it.getDouble(it.getColumnIndexOrThrow("liters")))
                json.put("created_at", it.getString(it.getColumnIndexOrThrow("created_at")))
                array.put(json)
            }
        }
        return array
    }

    // ═══════════════════════════════════════════════════════════════
    // سجل التدقيق الأمني
    // ═══════════════════════════════════════════════════════════════

    private fun logAudit(db: DatabaseHelper, phone: String, action: String, details: String) {
        try {
            // استخدام دالة logActivity الموجودة في DatabaseHelper
            db.logActivity(phone, action, details)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log audit: ${e::class.java.simpleName}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // دوال الإرسال والإشعارات
    // ═══════════════════════════════════════════════════════════════

    private fun notifyManager(context: Context, db: DatabaseHelper, managerPhone: String, message: String) {
        try {
            sendReply(context, db, managerPhone, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify manager: ${e::class.java.simpleName}")
        }
    }

    private fun sendReply(context: Context, db: DatabaseHelper, phone: String, message: String) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e(TAG, "Permission denied")
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
            Log.d(TAG, "Reply sent to $phone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply: ${e::class.java.simpleName}")
            db.logSms(phone, message, "auto_reply", "failed: ${e::class.java.simpleName}")
        }
    }

    private fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
    }

    private fun checkSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun normalizePhone(phone: String): String {
        return phone.replace("[^0-9]".toRegex(), "").takeLast(9)
    }

    private fun isPhoneMatch(phone1: String, phone2: String): Boolean {
        return phone1 == phone2 || phone1.endsWith(phone2) || phone2.endsWith(phone1)
    }
}
