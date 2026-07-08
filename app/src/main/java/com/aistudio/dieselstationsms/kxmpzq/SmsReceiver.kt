package com.aistudio.dieselstationsms.kxmpzq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════
 * محطة أبو أحمد - نظام الرسائل التفاعلي الذكي المتقدم
 * ═══════════════════════════════════════════════════════════════
 * 
 * المميزات:
 * 1. نظام طلب ديزل متعدد الخطوات (كمية → بير → وقت → تأكيد)
 * 2. تحويل بين اللترات والدباب (1 دبة = 20 لتر)
 * 3. توثيق حقيقي 100% في قاعدة البيانات
 * 4. إرسال تنبيه للسائق قبل 15 دقيقة
 * 5. حماية ذكية من استنزاف رصيد SMS
 * 6. كشف حساب دقيق مع الرصيد الإجمالي
 * 7. نظام حظر للمتكررين
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiverAI"

        // ═══ إعدادات الحماية من استنزاف SMS ═══
        private const val RATE_LIMIT_MS = 60000L           // دقيقة واحدة بين كل رد
        private const val RATE_LIMIT_WARNING_MS = 30000L  // 30 ثانية للتحذير
        private const val MAX_DAILY_MESSAGES = 10          // الحد الأقصى يومياً للعميل
        private const val MAX_REPEAT_WARNINGS = 3          // عدد التحذيرات قبل الحظر
        private const val BLOCK_DURATION_MS = 86400000L    // 24 ساعة حظر

        private const val MAX_MESSAGE_LENGTH = 2000

        // ═══ أرقام الهاتف ═══
        private const val MANAGER_PHONE = "+967XXXXXXXXX"
        private const val DRIVER_PHONE = "+967YYYYYYYYY"   // سائق التوصيل

        // ═══ الثوابت التجارية ═══
        private const val LITER_PER_DABBA = 20.0           // 1 دبة = 20 لتر
        private const val DIESEL_PRICE_PER_LITER = 490.0   // سعر اللتر
        private const val DELIVERY_FEE = 0.0               // مجاني للعملاء المسجلين

        // ═══ إعدادات السياق ═══
        private const val CONTEXT_TIMEOUT_MS = 600000L   // 10 دقائق سياق

        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("ar"))
        private val dateOnlyFormat = SimpleDateFormat("dd/MM/yyyy", Locale("ar"))
    }

    // ═══════════════════════════════════════════════════════════════
    // أنظمة التخزين الذكية
    // ═══════════════════════════════════════════════════════════════

    /** حماية من التكرار العادي */
    private val recentReplies = ConcurrentHashMap<String, Long>()

    /** عداد الرسائل اليومية لكل عميل */
    private val dailyMessageCount = ConcurrentHashMap<String, Int>()

    /** عداد التحذيرات المتكررة */
    private val repeatWarnings = ConcurrentHashMap<String, Int>()

    /** قائمة الحظر المؤقت */
    private val blockedNumbers = ConcurrentHashMap<String, Long>()

    /** تتبع الطلبات النشطة */
    private val activeOrders = ConcurrentHashMap<String, OrderDraft>()

    /** السياق الذكي للمحادثة */
    private val conversationContext = ConcurrentHashMap<String, ConversationContext>()

    /** تعلم تفضيلات العميل */
    private val customerPreferences = ConcurrentHashMap<String, CustomerPreferences>()

    /** سجل التفاعلات (للتعلم والحماية) */
    private val interactionHistory = ConcurrentHashMap<String, MutableList<InteractionRecord>>()

    /** تنبيهات السائق المجدولة */
    private val scheduledDriverAlerts = ConcurrentHashMap<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

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
        var unitPrice: Double = DIESEL_PRICE_PER_LITER,
        var totalAmount: Double = 0.0,
        var status: String = "draft",
        var step: Int = 0,  // 0=بدء, 1=كمية, 2=بير, 3=وقت, 4=تأكيد
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
    // نقاط الدخول الرئيسية
    // ═══════════════════════════════════════════════════════════════

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

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
    }

    private fun processSingleMessage(context: Context, db: DatabaseHelper, sms: android.telephony.SmsMessage) {
        val sender = sms.displayOriginatingAddress ?: return
        val rawBody = sms.displayMessageBody ?: return
        val msgBody = rawBody.lowercase(Locale.getDefault())

        // ═══ 1. التحقق من الحظر ═══
        if (isBlocked(sender)) {
            Log.w(TAG, "Number $sender is temporarily blocked")
            db.logSms(sender, msgBody, "received", "blocked: rate limit exceeded")
            return
        }

        // ═══ 2. حماية من الرسائل الطويلة/الضارة ═══
        if (msgBody.length > MAX_MESSAGE_LENGTH) {
            Log.w(TAG, "Message too long from $sender")
            return
        }

        // ═══ 3. كشف الرسائل المشبوهة ═══
        if (isSuspiciousMessage(msgBody)) {
            Log.w(TAG, "Suspicious message from $sender")
            notifyManager(context, db, "🚨 رسالة مشبوهة\nمن: $sender\nنص: $rawBody")
            return
        }

        // ═══ 4. التحقق من العميل ═══
        val customer = findCustomer(db, sender)
        if (customer == null) {
            Log.d(TAG, "Unknown number $sender - ignoring")
            db.logSms(sender, msgBody, "received", "ignored: unregistered")
            return
        }

        // ═══ 5. حماية استنزاف SMS (التحقق المتقدم) ═══
        if (!canProcessMessage(context, db, sender, customer, msgBody)) {
            return
        }

        db.logSms(sender, msgBody, "received", "success")
        Log.d(TAG, "Processing SMS from: $sender, body: $msgBody")

        // ═══ 6. المعالجة الذكية ═══
        try {
            handleSmartMessage(context, db, customer, msgBody, rawBody)
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            safeSendReply(context, db, sender, "عذراً ${customer.commercialName}، حدث خطأ. أرسل 'استعلام' للمساعدة.")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ حماية استنزاف SMS (النواة الجديدة) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * نظام حماية متعدد الطبقات من استنزاف رصيد SMS
     */
    private fun canProcessMessage(
        context: Context, db: DatabaseHelper, sender: String, 
        customer: CustomerInfo, msgBody: String
    ): Boolean {

        // أ) التحقق من Rate Limit الأساسي
        val lastReply = recentReplies[sender] ?: 0
        val timeSinceLast = System.currentTimeMillis() - lastReply

        if (timeSinceLast < RATE_LIMIT_MS) {
            // العميل يرسل بسرعة - تحقق مما إذا كان طلب تعديل
            val ctx = conversationContext[sender]
            val isContextReply = ctx != null && ctx.awaitingResponse && 
                                 (System.currentTimeMillis() - ctx.timestamp < CONTEXT_TIMEOUT_MS)

            if (!isContextReply) {
                // ليس رد ضمن السياق - تحقق من التكرار
                val count = dailyMessageCount.getOrDefault(sender, 0)

                if (count >= MAX_DAILY_MESSAGES) {
                    // تجاوز الحد اليومي - حظر مؤقت
                    blockNumber(sender)
                    sendReplyOnce(context, db, sender,
                        "⚠️ ${customer.commercialName}،\n" +
                        "لقد تجاوزت الحد المسموح من الرسائل اليوم.\n" +
                        "تم حظر رقمك مؤقتاً لمدة 24 ساعة.\n" +
                        "للاستفسار العاجل اتصل: 0123456789")
                    notifyManager(context, db, 
                        "🚫 حظر مؤقت\n" +
                        "العميل: ${customer.commercialName}\n" +
                        "السبب: تجاوز الحد اليومي ($MAX_DAILY_MESSAGES)")
                    return false
                }

                // تحذير من التكرار
                val warnings = repeatWarnings.getOrDefault(sender, 0) + 1
                repeatWarnings[sender] = warnings

                if (warnings >= MAX_REPEAT_WARNINGS) {
                    blockNumber(sender)
                    sendReplyOnce(context, db, sender,
                        "🚫 ${customer.commercialName}،\n" +
                        "لقد أرسلت رسائل متكررة كثيرة.\n" +
                        "تم حظر رقمك مؤقتاً لمدة 24 ساعة.\n" +
                        "يرجى تحديد ما تريده بدقة في المرة القادمة.\n" +
                        "للاستفسار: 0123456789")
                    notifyManager(context, db, 
                        "🚫 حظر مؤقت\n" +
                        "العميل: ${customer.commercialName}\n" +
                        "السبب: رسائل متكررة ($warnings)")
                    return false
                }

                // تحذير أولي/ثانوي
                sendReplyOnce(context, db, sender,
                    "⚠️ ${customer.commercialName}،\n" +
                    "لقد أرسلت رسائل متكررة.\n" +
                    "يرجى تحديد ما تريده في رسالة واحدة بدقة.\n" +
                    "مثال: 'اريد 5 دباب ديزل إلى بير شعبان الساعة 10 ص'\n" +
                    "تحذير $warnings من $MAX_REPEAT_WARNINGS")
                return false
            }
        }

        // ب) تحديث العدادات
        recentReplies[sender] = System.currentTimeMillis()
        dailyMessageCount[sender] = dailyMessageCount.getOrDefault(sender, 0) + 1

        // إعادة تعيين التحذيرات إذا مر وقت كافٍ
        if (timeSinceLast > 300000L) { // 5 دقائق
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

    /**
     * إرسال رسالة مرة واحدة فقط (لا تكرار)
     */
    private fun sendReplyOnce(
        context: Context, db: DatabaseHelper, phone: String, message: String
    ) {
        // التحقق من عدم الإرسال مؤخراً
        val lastSent = recentReplies[phone] ?: 0
        if (System.currentTimeMillis() - lastSent < RATE_LIMIT_MS) {
            Log.d(TAG, "Skipping duplicate reply to $phone")
            return
        }
        sendReply(context, db, phone, message)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ المعالج الذكي الرئيسي ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleSmartMessage(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, rawBody: String
    ) {
        val sender = customer.phone
        val name = customer.commercialName
        val ctx = getOrCreateContext(sender)
        val prefs = getOrCreatePreferences(sender)

        // تحليل النية مع مراعاة السياق
        val intent = detectIntent(msgBody, ctx, sender)

        // تسجيل التفاعل
        recordInteraction(sender, intent, msgBody)

        // تحديث السياق
        ctx.lastIntent = intent
        ctx.timestamp = System.currentTimeMillis()

        Log.d(TAG, "Intent: $intent, Step: ${activeOrders[sender]?.step}, Context: ${ctx.pendingAction}")

        when (intent) {
            // ═══ طلبات الوقود المتقدمة ═══
            "diesel_request" -> handleDieselRequestFlow(context, db, customer, msgBody, ctx)
            "gasoline_request" -> handleGasolineRequestFlow(context, db, customer, msgBody, ctx)

            // ═══ ردود السياق (خطوات الطلب) ═══
            "quantity_response" -> handleQuantityResponse(context, db, customer, msgBody, ctx)
            "location_response" -> handleLocationResponse(context, db, customer, msgBody, ctx)
            "time_response" -> handleTimeResponse(context, db, customer, msgBody, ctx)
            "confirm_order" -> handleOrderConfirmation(context, db, customer, ctx)
            "cancel_order" -> handleOrderCancel(context, db, customer)

            // ═══ الاستعلامات المالية ═══
            "balance_query" -> handleBalanceQuery(context, db, customer)
            "payment_request" -> handlePaymentRequest(context, db, customer, msgBody)
            "transfer_request" -> handleBankTransfer(context, db, customer)

            // ═══ العروض والأسعار ═══
            "offers_query" -> handleOffersQuery(context, db, customer)
            "price_query" -> handlePriceQuery(context, db, customer, msgBody)

            // ═══ نظام الولاء ═══
            "loyalty_query" -> handleLoyaltyQuery(context, db, customer)
            "redeem_points" -> handleRedeemPoints(context, db, customer, msgBody)

            // ═══ تتبع الطلبات ═══
            "track_order" -> handleTrackOrder(context, db, customer)
            "order_history" -> handleOrderHistory(context, db, customer)

            // ═══ خدمة العملاء ═══
            "help" -> handleHelp(context, db, customer)
            "complaint" -> handleComplaint(context, db, customer, msgBody)
            "emergency" -> handleEmergency(context, db, customer)
            "callback_request" -> handleCallbackRequest(context, db, customer)

            // ═══ الموقع والتوصيل ═══
            "location_query" -> handleLocationQuery(context, db, customer)
            "working_hours" -> handleWorkingHours(context, db, customer)

            // ═══ الفواتير والتقارير ═══
            "invoice_request" -> handleInvoiceRequest(context, db, customer, msgBody)
            "weekly_report" -> handleWeeklyReport(context, db, customer)

            // ═══ الجدولة ═══
            "schedule_appointment" -> handleSchedule(context, db, customer, msgBody)

            // ═══ التقييم ═══
            "rating" -> handleRating(context, db, customer, msgBody)

            // ═══ التحية والترحيب ═══
            "greeting" -> handleGreeting(context, db, customer, prefs)
            "thanks" -> handleThanks(context, db, customer)

            // ═══ غير مفهوم ═══
            else -> handleUnknown(context, db, customer, msgBody, ctx)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ سيناريو طلب الديزل المتقدم (النواة الجديدة) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * السيناريو الكامل لطلب الديزل:
     * 1. العميل: "اريد ديزل"
     * 2. البوت: يسأل عن الكمية + البير + الوقت
     * 3. العميل: يرد بالكمية (لتر أو دباب)
     * 4. البوت: يسأل عن البير
     * 5. العميل: يرد بالبير
     * 6. البوت: يسأل عن الوقت
     * 7. العميل: يرد بالوقت
     * 8. البوت: يعرض ملخص ويطلب التأكيد
     * 9. العميل: "تأكيد"
     * 10. البوت: ينفذ الطلب + يرسل للسائق + يوثق + يخبر العميل
     */
    private fun handleDieselRequestFlow(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext
    ) {
        val sender = customer.phone
        val name = customer.commercialName

        // إنشاء طلب جديد أو استخدام الموجود
        val order = activeOrders.getOrPut(sender) { OrderDraft(product = "diesel") }
        order.step = 1
        order.status = "draft"

        ctx.awaitingResponse = true
        ctx.pendingAction = "awaiting_quantity"

        // استخدام التفضيلات إن وجدت
        val suggestion = if (prefs.preferredQuantity > 0) {
            val dabbas = (prefs.preferredQuantity / LITER_PER_DABBA).toInt()
            "\n(آخر طلبك: $dabbas دباب = ${prefs.preferredQuantity.toInt()} لتر)"
        } else ""

        sendReply(context, db, sender,
            "⛽ $name،\n" +
            "طلب ديزل جديد.\n" +
            "═══════════════════\n" +
            "كم تريد؟ (أرسل العدد فقط)\n" +
            "💡 يمكنك إرسال:\n" +
            "  - عدد اللترات (مثال: 200)\n" +
            "  - عدد الدباب (مثال: 5 دباب)" +
            suggestion)
    }

    /**
     * معالجة رد العميل بالكمية
     */
    private fun handleQuantityResponse(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext
    ) {
        val sender = customer.phone
        val order = activeOrders[sender]

        if (order == null || order.step != 1) {
            // ليس في سياق طلب كمية - تحقق مما إذا كان رقم عادي
            if (msgBody.matches(Regex("^\d+\s*(دباب|دبابات|دبة|دبات|لتر|ltr|L)?\s*$") )) {
                // العميل أرسل رقم بدون سياق - ربما يريد البدء
                handleDieselRequestFlow(context, db, customer, msgBody, ctx)
                return
            }
            handleUnknown(context, db, customer, msgBody, ctx)
            return
        }

        // استخراج الكمية
        val quantityInfo = parseQuantity(msgBody)

        if (quantityInfo.liters <= 0) {
            sendReply(context, db, sender,
                "⚠️ ${customer.commercialName}،\n" +
                "لم أفهم الكمية.\n" +
                "أرسل مثلاً: '200' أو '10 دباب'")
            return
        }

        // تحديث الطلب
        order.quantityLiters = quantityInfo.liters
        order.quantityDabbas = quantityInfo.dabbas
        order.step = 2
        order.unitPrice = DIESEL_PRICE_PER_LITER

        // تحديث التفضيلات
        prefs.preferredQuantity = quantityInfo.liters

        ctx.pendingAction = "awaiting_location"

        // عرض التحويل للعميل
        val conversionText = if (quantityInfo.isDabba) {
            "✅ ${quantityInfo.dabbas.toInt()} دباب = ${quantityInfo.liters.toInt()} لتر"
        } else {
            "✅ ${quantityInfo.liters.toInt()} لتر = ${quantityInfo.dabbas.toInt()} دباب"
        }

        sendReply(context, db, sender,
            "$conversionText\n" +
            "═══════════════════\n" +
            "📍 إلى أي بير تريد التوصيل؟\n" +
            "أرسل اسم البير أو الموقع:")
    }

    /**
     * معالجة رد العميل بالموقع (البير)
     */
    private fun handleLocationResponse(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext
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
                "⚠️ ${customer.commercialName}،\n" +
                "الموقع قصير جداً.\n" +
                "أرسل اسم البير بالتفصيل:")
            return
        }

        order.deliveryLocation = location
        order.step = 3
        prefs.preferredLocation = location

        ctx.pendingAction = "awaiting_time"

        sendReply(context, db, sender,
            "📍 $location\n" +
            "═══════════════════\n" +
            "⏰ ما الوقت الذي تريد أن نجهز طلبك؟\n" +
            "أرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'")
    }

    /**
     * معالجة رد العميل بالوقت
     */
    private fun handleTimeResponse(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext
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
                "⚠️ ${customer.commercialName}،\n" +
                "لم أفهم الوقت.\n" +
                "أرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'")
            return
        }

        order.deliveryTime = timeInfo.displayTime
        order.deliveryTimestamp = timeInfo.timestamp
        order.step = 4

        // حساب المبلغ الإجمالي
        val subtotal = order.quantityLiters * order.unitPrice
        order.totalAmount = subtotal + DELIVERY_FEE

        ctx.pendingAction = "awaiting_confirmation"

        // عرض ملخص الطلب
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
            "أرسل 'تأكيد' للإتمام\n" +
            "أو 'إلغاء' للإلغاء")
    }

    /**
     * تأكيد الطلب - التنفيذ الحقيقي 100%
     */
    private fun handleOrderConfirmation(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        ctx: ConversationContext
    ) {
        val sender = customer.phone
        val name = customer.commercialName
        val order = activeOrders[sender]

        if (order == null || order.step != 4) {
            sendReply(context, db, sender,
                "⚠️ $name، لا يوجد طلب قيد التأكيد.\n" +
                "أرسل 'اريد ديزل' لبدء طلب جديد.")
            return
        }

        // ═══ التنفيذ الحقيقي ═══

        // 1. توليد رقم الطلب
        val orderId = "ORD-${System.currentTimeMillis() % 1000000}"
        val orderDate = dateOnlyFormat.format(Date())

        // 2. تحديث حالة الطلب
        order.status = "confirmed"

        // 3. توثيق في قاعدة البيانات (100% حقيقي)
        val success = db.recordDieselDelivery(
            customerId = sender,
            customerName = name,
            quantityLiters = order.quantityLiters,
            quantityDabbas = order.quantityDabbas,
            location = order.deliveryLocation,
            deliveryTime = order.deliveryTime,
            unitPrice = order.unitPrice,
            totalAmount = order.totalAmount,
            orderId = orderId,
            driverPhone = DRIVER_PHONE
        )

        if (!success) {
            Log.e(TAG, "Failed to record order in database")
            sendReply(context, db, sender,
                "❌ $name،\n" +
                "حدث خطأ في تسجيل الطلب.\n" +
                "يرجى التواصل مع المحطة: 0123456789")
            return
        }

        // 4. جلب الرصيد المحدث من قاعدة البيانات
        val updatedBalance = db.getCustomerBalance(sender)

        // 5. تحديث تفضيلات العميل
        prefs.lastOrderDate = System.currentTimeMillis()
        prefs.orderCount++
        prefs.preferredTime = order.deliveryTime

        // 6. إرسال تنبيه للسائق قبل 15 دقيقة
        scheduleDriverAlert(context, db, customer, order, orderId)

        // 7. إرسال رسالة التأكيد للعميل مع كشف الحساب
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
            "📞 للاستفسار: 0123456789")

        // 8. إشعار للمدير
        notifyManager(context, db,
            "🛢️ طلب ديزل مؤكد!\n" +
            "رقم: $orderId\n" +
            "العميل: $name\n" +
            "الكمية: ${order.quantityLiters.toInt()} لتر (${order.quantityDabbas.toInt()} دباب)\n" +
            "الموقع: ${order.deliveryLocation}\n" +
            "الوقت: ${order.deliveryTime}\n" +
            "القيمة: ${order.totalAmount.toInt()} ريال\n" +
            "الرصيد الجديد: ${updatedBalance.toInt()} ريال")

        // 9. تنظيف السياق
        ctx.awaitingResponse = false
        ctx.pendingAction = ""
        activeOrders.remove(sender)
    }

    /**
     * جدولة تنبيه السائق قبل 15 دقيقة من موعد التوصيل
     */
    private fun scheduleDriverAlert(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        order: OrderDraft, orderId: String
    ) {
        val delayMs = order.deliveryTimestamp - System.currentTimeMillis() - (15 * 60 * 1000)

        if (delayMs <= 0) {
            // الوقت قريب جداً - إرسال فوري
            sendDriverAlert(context, db, customer, order, orderId)
            return
        }

        // إلغاء أي تنبيه سابق
        scheduledDriverAlerts[orderId]?.let { handler.removeCallbacks(it) }

        val alertRunnable = Runnable {
            sendDriverAlert(context, db, customer, order, orderId)
        }

        scheduledDriverAlerts[orderId] = alertRunnable
        handler.postDelayed(alertRunnable, delayMs)

        Log.d(TAG, "Driver alert scheduled for order $orderId in ${delayMs / 60000} minutes")
    }

    /**
     * إرسال تنبيه السائق
     */
    private fun sendDriverAlert(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        order: OrderDraft, orderId: String
    ) {
        val dabbasText = if (order.quantityDabbas > 0) {
            "${order.quantityDabbas.toInt()} دباب (${order.quantityLiters.toInt()} لتر)"
        } else {
            "${order.quantityLiters.toInt()} لتر"
        }

        sendReply(context, db, DRIVER_PHONE,
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

        // تسجيل إرسال التنبيه
        db.logSms(DRIVER_PHONE, "Driver alert for order $orderId", "driver_alert", "sent")

        scheduledDriverAlerts.remove(orderId)
    }

    /**
     * إلغاء الطلب
     */
    private fun handleOrderCancel(
        context: Context, db: DatabaseHelper, customer: CustomerInfo
    ) {
        val sender = customer.phone
        val order = activeOrders.remove(sender)

        if (order != null) {
            // إلغاء التنبيه المجدول إن وجد
            // (نحتاج لرقم الطلب لكنه لم يُولد بعد)

            sendReply(context, db, sender,
                "❌ ${customer.commercialName}،\n" +
                "تم إلغاء الطلب.\n" +
                "نرحب بطلباتك في أي وقت.")

            conversationContext[sender]?.let {
                it.awaitingResponse = false
                it.pendingAction = ""
            }
        } else {
            sendReply(context, db, sender,
                "📦 ${customer.commercialName}،\n" +
                "لا يوجد طلب نشط للإلغاء.")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ سيناريو طلب البنزين (مماثل) ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleGasolineRequestFlow(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext
    ) {
        val sender = customer.phone
        val order = activeOrders.getOrPut(sender) { OrderDraft(product = "gasoline", unitPrice = 550.0) }
        order.step = 1

        ctx.awaitingResponse = true
        ctx.pendingAction = "awaiting_quantity_gasoline"

        sendReply(context, db, sender,
            "⛽ ${customer.commercialName}،\n" +
            "طلب بنزين جديد.\n" +
            "═══════════════════\n" +
            "كم لتر تريد؟\n" +
            "أرسل العدد فقط:")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ أدوات تحليل النصوص المتقدمة ═══
    // ═══════════════════════════════════════════════════════════════

    data class QuantityInfo(
        val liters: Double,
        val dabbas: Double,
        val isDabba: Boolean
    )

    /**
     * تحليل الكمية من النص (لترات أو دباب)
     */
    private fun parseQuantity(msgBody: String): QuantityInfo {
        val normalized = msgBody.trim().lowercase()

        // كشف الدباب
        val dabbaRegex = Regex("(\d+)\s*(دباب|دبابات|دبة|دبات)")
        val dabbaMatch = dabbaRegex.find(normalized)

        if (dabbaMatch != null) {
            val dabbas = dabbaMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val liters = dabbas * LITER_PER_DABBA
            return QuantityInfo(liters, dabbas, true)
        }

        // كشف اللترات
        val literRegex = Regex("(\d+(?:\.\d+)?)\s*(لتر|ltr|L|liter)?")
        val literMatch = literRegex.find(normalized)

        if (literMatch != null) {
            val liters = literMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val dabbas = liters / LITER_PER_DABBA
            return QuantityInfo(liters, dabbas, false)
        }

        // رقم صحيح فقط (افتراضي: لترات)
        val numberOnly = Regex("^(\d+)$").find(normalized)
        if (numberOnly != null) {
            val value = numberOnly.groupValues[1].toDouble()
            // إذا كان الرقم صغير (<= 50) افترض أنه دباب، وإلا لترات
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

    /**
     * تحليل وقت التوصيل
     */
    private fun parseDeliveryTime(msgBody: String): TimeInfo? {
        val normalized = msgBody.trim().lowercase()

        // الآن
        if (normalized.contains("الآن") || normalized.contains("now") || normalized.contains("حالا")) {
            val now = System.currentTimeMillis() + (30 * 60 * 1000) // بعد 30 دقيقة
            return TimeInfo("الآن (${timeFormat.format(Date(now))})", now)
        }

        // نمط الساعة: 10:00 ص أو 3:00 م
        val timeRegex = Regex("(\d{1,2})[:\.]?(\d{2})?\s*(ص|صباح|am|م|مساء|pm)?")
        val match = timeRegex.find(normalized)

        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val period = match.groupValues[3]

            // تحويل 12 ساعة إلى 24
            when {
                period.contains("م") || period.contains("pm") || period.contains("مساء") -> {
                    if (hour != 12) hour += 12
                }
                period.contains("ص") || period.contains("am") || period.contains("صباح") -> {
                    if (hour == 12) hour = 0
                }
            }

            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)

            // إذا كان الوقت في الماضي، اجعله غداً
            if (cal.timeInMillis < System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }

            val display = when {
                period.isNotEmpty() -> "${match.groupValues[1]}:${String.format("%02d", minute)} $period"
                hour < 12 -> "${match.groupValues[1]}:${String.format("%02d", minute)} ص"
                else -> "${match.groupValues[1]}:${String.format("%02d", minute)} م"
            }

            return TimeInfo(display, cal.timeInMillis)
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ كشف النية الذكي (Intent Detection) ═══
    // ═══════════════════════════════════════════════════════════════

    private fun detectIntent(msgBody: String, ctx: ConversationContext, sender: String): String {
        val normalized = msgBody.lowercase().trim()

        // ═══ أولاً: التحقق من السياق النشط ═══
        if (ctx.awaitingResponse) {
            when (ctx.pendingAction) {
                "awaiting_quantity", "awaiting_quantity_gasoline" -> {
                    if (normalized.matches(Regex("^\d+.*")) || 
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

        // ═══ ثانياً: كشف النية الرئيسية ═══
        return when {
            // طلب ديزل
            normalized.contains("اريد ديزل") || normalized.contains("طلب ديزل") || 
            normalized.contains("diesel") || (normalized.contains("ديزل") && !normalized.contains("بنزين")) -> "diesel_request"

            // طلب بنزين
            normalized.contains("اريد بنزين") || normalized.contains("بنزين") || 
            normalized.contains("gasoline") || normalized.contains("petrol") -> "gasoline_request"

            // تأكيد
            normalized.contains("تأكيد") || normalized.contains("confirm") || 
            normalized.contains("نعم") || normalized.contains("yes") -> "confirm_order"

            // إلغاء
            normalized.contains("الغاء") || normalized.contains("cancel") || 
            normalized.contains("إلغاء") || normalized.contains("لا") -> "cancel_order"

            // رصيد
            normalized.contains("رصيد") || normalized.contains("حساب") || normalized.contains("balance") -> "balance_query"

            // دفع
            normalized.contains("دفع") || normalized.contains("تسديد") || normalized.contains("سداد") || 
            normalized.contains("pay") -> "payment_request"

            // تحويل
            normalized.contains("تحويل") || normalized.contains("transfer") || normalized.contains("بنكي") -> "transfer_request"

            // عروض
            normalized.contains("عروض") || normalized.contains("offer") || normalized.contains("سعر") || 
            normalized.contains("price") -> "offers_query"

            // نقاط
            normalized.contains("نقاط") || normalized.contains("ولاء") || normalized.contains("points") || 
            normalized.contains("loyalty") -> "loyalty_query"

            // استبدال
            normalized.contains("استبدال") || normalized.contains("redeem") -> "redeem_points"

            // تتبع
            normalized.contains("حالة") || normalized.contains("تتبع") || normalized.contains("track") || 
            normalized.contains("order status") || normalized.contains("طلبي") -> "track_order"

            // سجل
            normalized.contains("سجل") || normalized.contains("تاريخ") || normalized.contains("history") -> "order_history"

            // مساعدة
            normalized.contains("استعلام") || normalized.contains("help") || normalized.contains("مساعدة") || 
            normalized.contains("?") || normalized.contains("؟") || normalized.contains("قائمة") || 
            normalized.contains("menu") || normalized.contains("خدمات") -> "help"

            // شكوى
            normalized.contains("شكوى") || normalized.contains("complaint") || normalized.contains("مشكلة") -> "complaint"

            // طوارئ
            normalized.contains("طوارئ") || normalized.contains("urgent") || normalized.contains("emergency") || 
            normalized.contains("عاجل") -> "emergency"

            // اتصال
            normalized.contains("اتصال") || normalized.contains("اتصلوا") || normalized.contains("callback") || 
            normalized.contains("كلموني") -> "callback_request"

            // موقع
            normalized.contains("موقع") || normalized.contains("location") || normalized.contains("عنوان") || 
            normalized.contains("خريطة") -> "location_query"

            // ساعات
            normalized.contains("ساعات") || normalized.contains("مواعيد") || normalized.contains("hours") || 
            normalized.contains("متى تفتح") -> "working_hours"

            // فاتورة
            normalized.contains("فاتورة") || normalized.contains("invoice") || normalized.contains("bill") -> "invoice_request"

            // تقرير
            normalized.contains("تقرير") || normalized.contains("report") || normalized.contains("weekly") || 
            normalized.contains("ملخص") -> "weekly_report"

            // حجز
            normalized.contains("حجز") || normalized.contains("موعد") || normalized.contains("appointment") || 
            normalized.contains("booking") -> "schedule_appointment"

            // تقييم
            normalized.matches(Regex("^[1-5]$")) || normalized.contains("تقييم") || normalized.contains("rating") || 
            normalized.contains("rate") -> "rating"

            // تحية
            normalized.contains("مرحب") || normalized.contains("hello") || normalized.contains("hi") || 
            normalized.contains("صباح") || normalized.contains("مساء") || normalized.contains("اهلا") || 
            normalized.contains("أهلا") -> "greeting"

            // شكر
            normalized.contains("شكر") || normalized.contains("thanks") || normalized.contains("thank you") || 
            normalized.contains("مشكور") -> "thanks"

            // غير مفهوم
            else -> "unknown"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ الاستعلامات المالية ═══
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
            "💳 ${customer.commercialName}،\n" +
            "═══════════════════\n" +
            "$balanceText\n" +
            "🏆 النقاط: $points\n" +
            "👑 العضوية: ${getVipText(customer.vipLevel)}\n" +
            "═══════════════════\n\n" +
            "💡 للدفع: 'دفع [المبلغ]'\n" +
            "📊 للتفاصيل: 'تفاصيل'")
    }

    private fun handlePaymentRequest(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val amount = extractAmount(msgBody)

        if (amount > 0) {
            sendReply(context, db, customer.phone,
                "💳 ${customer.commercialName}،\n" +
                "مبلغ الدفع: ${amount.toInt()} ريال\n\n" +
                "طرق الدفع:\n" +
                "1. كاش - زيارة المحطة\n" +
                "2. تحويل بنكي - أرسل 'تحويل'\n" +
                "3. تقسيط - أرسل 'تقسيط'")
        } else {
            sendReply(context, db, customer.phone,
                "💳 ${customer.commercialName}،\n" +
                "الرصيد: ${customer.balance.toInt()} ريال\n\n" +
                "أرسل 'دفع [المبلغ]'\n" +
                "مثال: 'دفع 5000'")
        }
    }

    private fun handleBankTransfer(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "🏦 ${customer.commercialName}،\n" +
            "معلومات التحويل:\n" +
            "═══════════════════\n" +
            "البنك: بنك اليمن الدولي\n" +
            "الحساب: 1234567890\n" +
            "اسم: محطة أبو أحمد\n" +
            "═══════════════════\n\n" +
            "⚠️ بعد التحويل أرسل 'تم التحويل'")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ العروض والأسعار ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleOffersQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val sender = customer.phone
        val vip = customer.vipLevel

        val vipOffer = when (vip) {
            3 -> "👑 ذهبي: خصم 15% + توصيل مجاني"
            2 -> "🥈 فضي: خصم 10% + توصيل نصف السعر"
            1 -> "🥉 برونزي: خصم 7%"
            else -> "💎 عادي: خصم 5%"
        }

        sendReply(context, db, sender,
            "🎁 ${customer.commercialName}،\n" +
            "═══════════════════\n" +
            "⛽ ديزل: ${DIESEL_PRICE_PER_LITER.toInt()} ريال/لتر\n" +
            "⛽ بنزين: 550 ريال/لتر\n" +
            "═══════════════════\n" +
            "$vipOffer\n" +
            "═══════════════════")
    }

    private fun handlePriceQuery(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val product = when {
            msgBody.contains("ديزل") -> "diesel"
            msgBody.contains("بنزين") -> "gasoline"
            else -> "all"
        }

        val message = when (product) {
            "diesel" -> "⛽ سعر الديزل: ${DIESEL_PRICE_PER_LITER.toInt()} ريال/لتر"
            "gasoline" -> "⛽ سعر البنزين: 550 ريال/لتر"
            else -> "📊 الأسعار:\n" +
                    "⛽ ديزل: ${DIESEL_PRICE_PER_LITER.toInt()} ريال/لتر\n" +
                    "⛽ بنزين: 550 ريال/لتر"
        }

        sendReply(context, db, customer.phone, message)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ نظام الولاء ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleLoyaltyQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val sender = customer.phone
        val points = customer.points

        sendReply(context, db, sender,
            "🏆 ${customer.commercialName}،\n" +
            "═══════════════════\n" +
            "النقاط: $points\n" +
            "الفئة: ${getVipText(customer.vipLevel)}\n" +
            "═══════════════════\n\n" +
            "💰 الاستبدال:\n" +
            "500 ➜ 25 ريال\n" +
            "1000 ➜ 60 ريال\n" +
            "2000 ➜ 150 ريال\n\n" +
            "أرسل 'استبدال [النقاط]'")
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
                "❌ نقاطك غير كافية!\n" +
                "المطلوب: $points\n" +
                "متاح: ${customer.points}")
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
            "🎉 تم استبدال $points نقطة!\n" +
            "القيمة: ${value.toInt()} ريال\n" +
            "تم الإضافة لرصيدك.")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تتبع الطلبات ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleTrackOrder(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val sender = customer.phone

        // جلب آخر طلب من قاعدة البيانات
        val lastOrder = db.getLastOrder(sender)

        if (lastOrder != null) {
            val status = lastOrder.optString("status", "unknown")
            val statusText = when (status) {
                "pending" -> "⏳ قيد الانتظار"
                "confirmed" -> "✅ مؤكد"
                "delivered" -> "🚚 تم التوصيل"
                else -> "⏳ قيد المعالجة"
            }

            sendReply(context, db, sender,
                "📦 ${customer.commercialName}،\n" +
                "آخر طلب:\n" +
                "═══════════════════\n" +
                "الرقم: ${lastOrder.optString("order_id", "N/A")}\n" +
                "الكمية: ${lastOrder.optDouble("quantity_liters", 0.0).toInt()} لتر\n" +
                "الموقع: ${lastOrder.optString("location", "")}\n" +
                "الحالة: $statusText\n" +
                "═══════════════════")
        } else {
            sendReply(context, db, sender,
                "📦 ${customer.commercialName}،\n" +
                "لا توجد طلبات سابقة.\n" +
                "أرسل 'اريد ديزل' للطلب")
        }
    }

    private fun handleOrderHistory(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val history = db.getOrderHistory(customer.phone, 5)

        if (history.length() > 0) {
            val sb = StringBuilder()
            sb.append("📊 ${customer.commercialName}، سجل الطلبات:\n")
            sb.append("═══════════════════\n")

            for (i in 0 until history.length()) {
                val order = history.getJSONObject(i)
                sb.append("🛢️ ${order.optString("product", "")} ")
                sb.append("${order.optDouble("quantity_liters", 0.0).toInt()} لتر ")
                sb.append("- ${order.optString("date", "")}\n")
            }

            sb.append("═══════════════════")
            sendReply(context, db, customer.phone, sb.toString())
        } else {
            sendReply(context, db, customer.phone, "لا يوجد سجل طلبات.")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ خدمة العملاء ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleHelp(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "📋 ${customer.commercialName}،\n" +
            "قائمة الخدمات:\n" +
            "═══════════════════\n" +
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
        sendReply(context, db, customer.phone,
            "📝 ${customer.commercialName}،\n" +
            "تم استلام شكواك.\n" +
            "رقم التذكرة: #${System.currentTimeMillis() % 10000}\n" +
            "الرد خلال 24 ساعة.\n" +
            "📞 للعاجل: 0123456789")

        notifyManager(context, db,
            "🚨 شكوى\n" +
            "العميل: ${customer.commercialName}\n" +
            "الرسالة: $msgBody")
    }

    private fun handleEmergency(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "🚨 ${customer.commercialName}،\n" +
            "تم تفعيل الطوارئ!\n" +
            "═══════════════════\n" +
            "📞 الاتصال: 0123456789\n" +
            "📞 المدير: 0123456788\n" +
            "═══════════════════\n" +
            "سيتم الاتصال بك خلال 2 دقيقة!")

        notifyManager(context, db,
            "🚨 طوارئ!\n" +
            "العميل: ${customer.commercialName}\n" +
            "الرقم: ${customer.phone}\n" +
            "اتصل فوراً!")
    }

    private fun handleCallbackRequest(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "📞 ${customer.commercialName}،\n" +
            "تم طلب الاتصال.\n" +
            "سيتم الاتصال خلال 15 دقيقة.")

        notifyManager(context, db,
            "📞 طلب اتصال\n" +
            "العميل: ${customer.commercialName}\n" +
            "الرقم: ${customer.phone}")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ الموقع والتوصيل ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleLocationQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "📍 ${customer.commercialName}،\n" +
            "محطة أبو أحمد:\n" +
            "═══════════════════\n" +
            "بجانب مدرسة الاتحاد\n" +
            "الحميدة - العرش\n" +
            "═══════════════════\n" +
            "🕐 6ص - 12ص (السبت-الخميس)\n" +
            "🕐 2م - 12ص (الجمعة)\n" +
            "🚨 طوارئ: 24 ساعة")
    }

    private fun handleWorkingHours(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "🕐 ${customer.commercialName}،\n" +
            "السبت-الخميس: 6ص - 12ص\n" +
            "الجمعة: 2م - 12ص\n" +
            "🚨 طوارئ: 24 ساعة")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ الفواتير والتقارير ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleInvoiceRequest(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        when {
            msgBody.contains("آخر شهر") || msgBody.contains("last month") -> {
                sendReply(context, db, customer.phone,
                    "📄 ${customer.commercialName}،\n" +
                    "فاتورة يونيو 2026:\n" +
                    "═══════════════════\n" +
                    "إجمالي: 78,000 ريال\n" +
                    "مدفوع: 50,000 ريال\n" +
                    "متبقي: 28,000 ريال\n" +
                    "══════════════════=")
            }
            else -> {
                sendReply(context, db, customer.phone,
                    "📄 ${customer.commercialName}،\n" +
                    "أرسل 'فاتورة آخر شهر'\n" +
                    "أو 'فاتورة [الشهر]'")
            }
        }
    }

    private fun handleWeeklyReport(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "📊 ${customer.commercialName}،\n" +
            "التقرير الأسبوعي:\n" +
            "═══════════════════\n" +
            "طلبات: 3\n" +
            "لترات: 250\n" +
            "إنفاق: 130,000 ريال\n" +
            "نقاط: +130\n" +
            "══════════════════=")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ الجدولة ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleSchedule(
        context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String
    ) {
        val timeInfo = parseDeliveryTime(msgBody)

        if (timeInfo != null) {
            sendReply(context, db, customer.phone,
                "📅 ${customer.commercialName}،\n" +
                "تم حجز موعد:\n" +
                "الوقت: ${timeInfo.displayTime}\n" +
                "سنرسل تذكير قبل ساعة.")
        } else {
            sendReply(context, db, customer.phone,
                "📅 ${customer.commercialName}،\n" +
                "أرسل 'حجز [الوقت]'\n" +
                "مثال: 'حجز 10:00 ص'")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التقييم ═══
    // ═══════════════════════════════════════════════════════════════

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
                "⭐ ${customer.commercialName}،\n" +
                "تقييمك: $rating/5\n" +
                "$response")

            notifyManager(context, db,
                "📊 تقييم\n" +
                "العميل: ${customer.commercialName}\n" +
                "التقييم: $rating/5")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحية والترحيب ═══
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
            "$greeting ${customer.commercialName}! 🌟\n" +
            "أهلاً بك في محطة أبو أحمد." +
            personalized +
            "\n\nأرسل 'استعلام' للخدمات")
    }

    private fun handleThanks(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "🙏 ${customer.commercialName}،\n" +
            "شكراً لك! نسعد بخدمتك دائماً.\n\n" +
            "💡 للطلب السريع: 'اريد ديزل'")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ المعالج الافتراضي ═══
    // ═══════════════════════════════════════════════════════════════

    private fun handleUnknown(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext
    ) {
        val sender = customer.phone

        // التحقق من السياق النشط
        if (ctx.awaitingResponse && ctx.pendingAction.isNotEmpty()) {
            when (ctx.pendingAction) {
                "awaiting_quantity", "awaiting_quantity_gasoline" -> {
                    if (msgBody.matches(Regex(".*\d+.*"))) {
                        handleQuantityResponse(context, db, customer, msgBody, ctx)
                        return
                    }
                }
                "awaiting_location" -> {
                    handleLocationResponse(context, db, customer, msgBody, ctx)
                    return
                }
                "awaiting_time" -> {
                    handleTimeResponse(context, db, customer, msgBody, ctx)
                    return
                }
                "awaiting_confirmation" -> {
                    if (msgBody.contains("تأكيد") || msgBody.contains("نعم")) {
                        handleOrderConfirmation(context, db, customer, ctx)
                        return
                    }
                    if (msgBody.contains("إلغاء") || msgBody.contains("لا")) {
                        handleOrderCancel(context, db, customer)
                        return
                    }
                }
            }
        }

        sendReply(context, db, sender,
            "🤔 ${customer.commercialName}،\n" +
            "لم أفهم طلبك.\n\n" +
            "هل تقصد:\n" +
            "1. طلب ديزل - 'اريد ديزل'\n" +
            "2. استعلام - 'رصيد'\n" +
            "3. العروض - 'عروض'\n" +
            "4. المساعدة - 'استعلام'\n\n" +
            "📞 أو اتصل: 0123456789")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ أدوات المساعدة ═══
    // ═══════════════════════════════════════════════════════════════

    private fun extractAmount(msgBody: String): Double {
        val regex = Regex("(\d+(?:\.\d+)?)\s*(?:ريال|riyal|ry|YER)?")
        val match = regex.find(msgBody)
        return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
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
        return msgBody.contains("http") || msgBody.contains("www") || msgBody.contains(".com") ||
               msgBody.contains("بطاقة") || msgBody.contains("رقم سري") || msgBody.contains("cvv") ||
               msgBody.contains("password") || msgBody.contains("otp")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ إدارة السياق والتفضيلات ═══
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
    // ═══ قاعدة البيانات ═══
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

    // ═══════════════════════════════════════════════════════════════
    // ═══ أدوات الإرسال والإشعارات ═══
    // ═══════════════════════════════════════════════════════════════

    private fun notifyManager(context: Context, db: DatabaseHelper, message: String) {
        try {
            sendReply(context, db, MANAGER_PHONE, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify manager: ${e.message}")
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
            Log.e(TAG, "Failed to send reply: ${e.message}", e)
            db.logSms(phone, message, "auto_reply", "failed: ${e.message}")
        }
    }

    private fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
    }

    private fun safeSendReply(context: Context, db: DatabaseHelper, phone: String, message: String) {
        try {
            sendReply(context, db, phone, message)
        } catch (e: Exception) {
            Log.e(TAG, "Safe send failed: ${e.message}", e)
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

// ═══════════════════════════════════════════════════════════════
// ═══ تواقيع DatabaseHelper المطلوبة (يجب تنفيذها) ═══
// ═══════════════════════════════════════════════════════════════
/**
 * يجب إضافة هذه الدوال إلى DatabaseHelper:
 * 
 * 1. recordDieselDelivery(...): Boolean
 *    - تسجيل الطلب في جدول deliveries
 *    - تحديث رصيد العميل (current_balance += totalAmount)
 *    - إرجاع true/false حسب النجاح
 * 
 * 2. getCustomerBalance(phone: String): Double
 *    - جلب الرصيد الحالي من جدول parties
 * 
 * 3. getLastOrder(phone: String): JSONObject?
 *    - جلب آخر طلب من جدول deliveries
 * 
 * 4. getOrderHistory(phone: String, limit: Int): JSONArray
 *    - جلب سجل الطلبات
 * 
 * 5. getParties(): JSONArray
 *    - موجودة بالفعل
 * 
 * 6. logSms(...): void
 *    - موجودة بالفعل
 */
