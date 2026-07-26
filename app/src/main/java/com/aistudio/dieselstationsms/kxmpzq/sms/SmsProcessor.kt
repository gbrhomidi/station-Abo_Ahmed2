package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import com.aistudio.dieselstationsms.kxmpzq.utils.SystemEventLogger

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════
 * معالج الرسائل الرئيسي - SmsProcessor
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. معالجة الرسائل الواردة
 * 2. التحقق من التكرار (Hash)
 * 3. التحقق من SMSC
 * 4. كشف الرسائل المشبوهة
 * 5. Rate Limiting
 * 6. توجيه الرسالة للمعالج المناسب
 * 7. إرجاع Boolean للإشارة إلى نجاح/فشل المعالجة
 */
class SmsProcessor(
    private val context: Context,
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "SmsProcessor"
        private const val DEFAULT_RETENTION_DAYS = 90
        private const val CONTEXT_TIMEOUT_MS = 600000L
    }

    private val security = SmsSecurity(context, db)
    private val intentDetector = SmsIntentDetector()
    private val conversationManager = SmsConversationManager(db)
    private val customerResolver = SmsCustomerResolver(db)
    private val replyManager = SmsReplyManager(context, db)
    private val metrics = SmsMetrics(db)

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("ar"))
    private val dateOnlyFormat = SimpleDateFormat("dd/MM/yyyy", Locale("ar"))
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale("ar"))

    suspend fun process(intent: Intent): Boolean = withContext(Dispatchers.IO) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return@withContext false
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?: return@withContext false

        var totalLength = 0
        for (sms in messages) {
            totalLength += sms?.displayMessageBody?.length ?: 0
        }

        if (security.isMessageTooLong(totalLength)) {
            val sender = messages[0]?.displayOriginatingAddress ?: "unknown"
            security.logSecurityEvent("DOS_ATTEMPT", sender, "Message too long: $totalLength chars")
            metrics.recordEvent(SmsMetrics.EventType.SMS_REJECTED, sender, "Message too long")
            return@withContext false
        }

        security.cleanupSmsProcessedMessages()

        var allProcessed = true
        for (sms in messages) {
            if (sms == null) continue
            val processed = processSingleMessage(sms)
            if (!processed) allProcessed = false
        }

        cleanupOldData()

        allProcessed
    }

    private suspend fun processSingleMessage(sms: SmsMessage): Boolean {
        val sender = sms.displayOriginatingAddress ?: return false
        val rawBody = sms.displayMessageBody ?: return false
        val msgBody = rawBody.lowercase(Locale.getDefault())
        val smsc = sms.serviceCenterAddress ?: ""

        val messageHash = security.generateMessageHash(sender, rawBody)
        if (security.isSmsAlreadyProcessed(messageHash)) {
            Log.d(TAG, "Skipping duplicate SMS from $sender")
            metrics.recordEvent(SmsMetrics.EventType.SMS_DUPLICATED, sender)
            return false
        }

        if (!security.isTrustedSmsc(smsc)) {
            security.logSecurityEvent("SPOOFING_ATTEMPT", sender, "Untrusted SMSC: $smsc")
            db.logSms(sender, msgBody, "received", "rejected: untrusted SMSC")
            metrics.recordEvent(SmsMetrics.EventType.SMS_SPOOFED, sender, "SMSC: $smsc")
            return false
        }

        if (security.isBlocked(sender)) {
            security.logSecurityEvent("BLOCKED_MESSAGE", sender, "Rate limit exceeded")
            db.logSms(sender, msgBody, "received", "blocked: rate limit exceeded")
            metrics.recordEvent(SmsMetrics.EventType.SMS_BLOCKED, sender)
            return false
        }

        if (security.isSuspiciousMessage(msgBody)) {
            security.logSecurityEvent("SUSPICIOUS_MESSAGE", sender, "Suspicious content detected")
            val managerPhone = customerResolver.getManagerPhone()
            if (managerPhone != null) {
                replyManager.notifyManager(managerPhone,
                    "🚨 رسالة مشبوهة\nمن: $sender\nنص: ${rawBody.take(100)}")
            }
            metrics.recordEvent(SmsMetrics.EventType.SMS_REJECTED, sender, "Suspicious")
            return false
        }

        val customer = customerResolver.findCustomer(sender)
        if (customer == null) {
            db.logSms(sender, msgBody, "received", "ignored: unregistered")
            metrics.recordEvent(SmsMetrics.EventType.SMS_REJECTED, sender, "Unregistered")
            return false
        }

        val ctx = conversationManager.getOrCreateContext(PhoneUtils.normalize(sender))
        val isContextReply = ctx.awaitingResponse &&
                (System.currentTimeMillis() - ctx.timestamp < CONTEXT_TIMEOUT_MS)

        val rateLimitResult = security.canProcessMessage(sender, customer.commercialName, isContextReply)
        when (rateLimitResult) {
            is SmsSecurity.RateLimitResult.BLOCKED -> {
                replyManager.sendReplyOnce(sender, rateLimitResult.message)
                if (rateLimitResult.managerPhone != null) {
                    replyManager.notifyManager(rateLimitResult.managerPhone,
                        "🚫 حظر مؤقت\nالعميل: ${customer.commercialName}\nالسبب: تجاوز الحد")
                }
                metrics.recordEvent(SmsMetrics.EventType.SMS_BLOCKED, sender)
                return false
            }
            is SmsSecurity.RateLimitResult.WARNING -> {
                replyManager.sendReplyOnce(sender, rateLimitResult.message)
                metrics.recordEvent(SmsMetrics.EventType.SMS_WARNING, sender)
                return false
            }
            is SmsSecurity.RateLimitResult.ALLOWED -> {
            }
        }

        db.logSms(sender, msgBody, "received", "success")
        metrics.recordEvent(SmsMetrics.EventType.SMS_RECEIVED, sender)

        val processed = handleSmartMessage(customer, msgBody, rawBody)

        security.markSmsProcessed(messageHash, sender, rawBody)

        try {
            val data = JSONObject().apply {
                put("phone_number", sender)
                put("message_body", rawBody)
                put("message_type", "incoming")
                put("status", if (processed) "processed" else "failed")
                put("processed_at", System.currentTimeMillis())
            }
            db.addSmsMessage(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving SMS to database", e)
        }

        if (processed) {
            metrics.recordEvent(SmsMetrics.EventType.SMS_PROCESSED, sender)
        } else {
            metrics.recordEvent(SmsMetrics.EventType.SMS_FAILED, sender)
        }

        return processed
    }

    private suspend fun handleSmartMessage(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        rawBody: String
    ): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)
        val ctx = conversationManager.getOrCreateContext(normalizedPhone)
        val prefs = conversationManager.getOrCreatePreferences(normalizedPhone)

        val intentResult = intentDetector.detectIntent(msgBody, SmsIntentDetector.ConversationState(
            awaitingResponse = ctx.awaitingResponse,
            pendingAction = ctx.pendingAction,
            lastTopic = ctx.lastTopic,
            timestamp = ctx.timestamp
        ), sender)

        Log.d(TAG, "Detected intent: ${intentResult.intent} (confidence: ${intentResult.confidence}%)")

        conversationManager.recordInteraction(normalizedPhone, intentResult.intent, msgBody)

        ctx.lastIntent = intentResult.intent
        ctx.timestamp = System.currentTimeMillis()
        conversationManager.saveContext(normalizedPhone, ctx)

        return try {
            val result = when (intentResult.intent) {
                "diesel_request" -> handleDieselRequestFlow(customer, ctx, prefs)
                "gasoline_request" -> handleGasolineRequestFlow(customer, ctx, prefs)
                "quantity_response" -> handleQuantityResponse(customer, msgBody, ctx, prefs)
                "location_response" -> handleLocationResponse(customer, msgBody, ctx, prefs)
                "time_response" -> handleTimeResponse(customer, msgBody, ctx, prefs)
                "confirm_order" -> handleOrderConfirmation(customer, ctx, prefs)
                "cancel_order" -> handleOrderCancel(customer)
                "balance_query" -> handleBalanceQuery(customer)
                "payment_request" -> handlePaymentRequest(customer, msgBody)
                "transfer_request" -> handleBankTransfer(customer)
                "offers_query" -> handleOffersQuery(customer)
                "price_query" -> handlePriceQuery(customer, msgBody)
                "loyalty_query" -> handleLoyaltyQuery(customer)
                "redeem_points" -> handleRedeemPoints(customer, msgBody)
                "track_order" -> handleTrackOrder(customer)
                "order_history" -> handleOrderHistory(customer)
                "help" -> handleHelp(customer)
                "complaint" -> handleComplaint(customer, msgBody)
                "emergency" -> handleEmergency(customer)
                "callback_request" -> handleCallbackRequest(customer)
                "location_query" -> handleLocationQuery(customer)
                "working_hours" -> handleWorkingHours(customer)
                "invoice_request" -> handleInvoiceRequest(customer, msgBody)
                "weekly_report" -> handleWeeklyReport(customer)
                "schedule_appointment" -> handleSchedule(customer, msgBody)
                "schedule_recurring" -> handleRecurringOrder(customer, msgBody)
                "rating" -> handleRating(customer, msgBody)
                "greeting" -> handleGreeting(customer, prefs)
                "thanks" -> handleThanks(customer)
                else -> handleUnknown(customer, msgBody, ctx)
            }
            result
        } catch (e: Exception) {
            val errorId = UUID.randomUUID().toString().take(8)
            Log.e(TAG, "Error [$errorId] for $sender: ${e.javaClass.simpleName}")
            security.logSecurityEvent("PROCESSING_ERROR", sender, "ErrorID: $errorId")
            replyManager.safeSendReply(sender,
                "عذراً ${customer.commercialName}، حدث خطأ. رمز: $errorId")
            false
        }
    }

    private suspend fun handleDieselRequestFlow(
        customer: SmsCustomerResolver.CustomerInfo,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)
        val order = conversationManager.getOrCreateOrderDraft(normalizedPhone, "diesel")
        order.step = 1
        order.status = "draft"
        order.unitPrice = customerResolver.getDieselPrice()

        ctx.awaitingResponse = true
        ctx.pendingAction = "awaiting_quantity"
        conversationManager.saveContext(normalizedPhone, ctx)

        val suggestion = if (prefs.preferredQuantity > 0) {
            val dabbas = (prefs.preferredQuantity / 20.0).toInt()
            "\n(آخر طلبك: $dabbas دباب = ${prefs.preferredQuantity.toInt()} لتر)"
        } else ""

        replyManager.sendReply(sender,
            "⛽ ${customer.commercialName}،\n" +
            "طلب ديزل جديد.\n" +
            "═══════════════════\n" +
            "كم تريد؟ (أرسل العدد فقط)\n" +
            "💡 يمكنك إرسال:\n" +
            "  - عدد اللترات (مثال: 200)\n" +
            "  - عدد الدباب (مثال: 5 دباب)" + suggestion)
        return true
    }

    private suspend fun handleQuantityResponse(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)
        val order = conversationManager.getOrderDraft(normalizedPhone)

        if (order == null || order.step != 1) {
            Regex("""^\d+\s*(?:دباب|دبابات|دبة|دبات|لتر|ltr|L)?\s*$""",
            RegexOption.IGNORE_CASE)
            if (regex.matches(msgBody)) {
                handleDieselRequestFlow(customer, ctx, prefs)
                return true
            }
            return handleUnknown(customer, msgBody, ctx)
        }

        val quantityInfo = intentDetector.parseQuantity(msgBody)

        if (quantityInfo.liters <= 0 || quantityInfo.liters > 10000.0) {
            replyManager.sendReply(sender,
                "⚠️ ${customer.commercialName}،\n" +
                "الكمية غير صالحة.\n" +
                "الحد الأقصى: 10000 لتر.\n" +
                "أرسل مثلاً: '200' أو '10 دباب'")
            return false
        }

        order.quantityLiters = quantityInfo.liters
        order.quantityDabbas = quantityInfo.dabbas
        order.step = 2
        order.unitPrice = customerResolver.getDieselPrice()
        prefs.preferredQuantity = quantityInfo.liters
        ctx.pendingAction = "awaiting_location"
        conversationManager.saveContext(normalizedPhone, ctx)
        conversationManager.savePreferences(normalizedPhone, prefs)

        val conversionText = if (quantityInfo.isDabba) {
            "✅ ${quantityInfo.dabbas.toInt()} دباب = ${quantityInfo.liters.toInt()} لتر"
        } else {
            "✅ ${quantityInfo.liters.toInt()} لتر = ${quantityInfo.dabbas.toInt()} دباب"
        }

        replyManager.sendReply(sender,
            "$conversionText\n" +
            "═══════════════════\n" +
            "📍 إلى أي بير تريد التوصيل؟\n" +
            "أرسل اسم البير أو الموقع:")
        return true
    }

    private suspend fun handleLocationResponse(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)
        val order = conversationManager.getOrderDraft(normalizedPhone)

        if (order == null || order.step != 2) {
            return handleUnknown(customer, msgBody, ctx)
        }

        val location = msgBody.trim()
        if (location.length < 3 || location.length > 200) {
            replyManager.sendReply(sender,
                "⚠️ ${customer.commercialName}،\n" +
                "الموقع غير صالح.\n" +
                "أرسل اسم البير بالتفصيل (3-200 حرف):")
            return false
        }

        order.deliveryLocation = location
        order.step = 3
        prefs.preferredLocation = location
        ctx.pendingAction = "awaiting_time"
        conversationManager.saveContext(normalizedPhone, ctx)
        conversationManager.savePreferences(normalizedPhone, prefs)

        replyManager.sendReply(sender,
            "📍 $location\n" +
            "═══════════════════\n" +
            "⏰ ما الوقت الذي تريد أن نجهز طلبك؟\n" +
            "أرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'")
        return true
    }

    private suspend fun handleTimeResponse(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)
        val order = conversationManager.getOrderDraft(normalizedPhone)

        if (order == null || order.step != 3) {
            return handleUnknown(customer, msgBody, ctx)
        }

        val timeInfo = intentDetector.parseDeliveryTime(msgBody)

        if (timeInfo == null) {
            replyManager.sendReply(sender,
                "⚠️ ${customer.commercialName}،\n" +
                "لم أفهم الوقت.\n" +
                "أرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'")
            return false
        }

        order.deliveryTime = timeInfo.displayTime
        order.deliveryTimestamp = timeInfo.timestamp
        order.step = 4

        val subtotal = customerResolver.safeMultiply(order.quantityLiters, order.unitPrice)
        order.totalAmount = subtotal + 0.0

        ctx.pendingAction = "awaiting_confirmation"
        conversationManager.saveContext(normalizedPhone, ctx)

        val dabbasText = if (order.quantityDabbas > 0) "(${order.quantityDabbas.toInt()} دباب)" else ""

        replyManager.sendReply(sender,
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
        return true
    }

    private suspend fun handleOrderConfirmation(
        customer: SmsCustomerResolver.CustomerInfo,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)
        val name = customer.commercialName
        val order = conversationManager.getOrderDraft(normalizedPhone)

        if (order == null || order.step != 4) {
            replyManager.sendReply(sender,
                "⚠️ $name، لا يوجد طلب قيد التأكيد.\n" +
                "أرسل 'اريد ديزل' لبدء طلب جديد.")
            return false
        }

        val orderId = "ORD-${System.currentTimeMillis() % 1000000}"
        val orderDate = dateOnlyFormat.format(Date())
        order.status = "confirmed"

        val success = customerResolver.recordDieselDelivery(
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
            val errorId = UUID.randomUUID().toString().take(8)
            Log.e(TAG, "Failed to record order [$errorId]")
            val managerPhone = customerResolver.getManagerPhone()
            replyManager.sendReply(sender,
                "❌ $name،\n" +
                "حدث خطأ في تسجيل الطلب. رمز: $errorId\n" +
                "يرجى التواصل مع المحطة: ${managerPhone ?: "غير متوفر"}")
            return false
        }

        val updatedBalance = customerResolver.getCustomerBalanceByPhone(sender)
        prefs.lastOrderDate = System.currentTimeMillis()
        prefs.orderCount = prefs.orderCount + 1
        prefs.preferredTime = order.deliveryTime
        conversationManager.savePreferences(normalizedPhone, prefs)

        scheduleDriverAlert(customer, order, orderId)

        val dabbasText = if (order.quantityDabbas > 0) {
            val dabbasInt = order.quantityDabbas.toInt()
            val dabbasWord = if (dabbasInt == 1) "دبة" else "دباب"
            "حق $dabbasInt $dabbasWord"
        } else {
            "حق ${order.quantityLiters.toInt()} لتر"
        }

        val balanceText = if (updatedBalance >= 0) {
            "الرصيد الإجمالي عليكم: ${updatedBalance.toInt()} ريال"
        } else {
            "الرصيد الإجمالي لكم: ${kotlin.math.abs(updatedBalance).toInt()} ريال"
        }

        val managerPhone = customerResolver.getManagerPhone()
        replyManager.sendReply(sender,
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
            "📞 للاستفسار: ${managerPhone ?: "غير متوفر"}")

        if (managerPhone != null) {
            replyManager.notifyManager(managerPhone,
                "🛢️ طلب ديزل مؤكد!\n" +
                "رقم: $orderId\n" +
                "العميل: $name\n" +
                "الكمية: ${order.quantityLiters.toInt()} لتر (${order.quantityDabbas.toInt()} دباب)\n" +
                "الموقع: ${order.deliveryLocation}\n" +
                "الوقت: ${order.deliveryTime}\n" +
                "القيمة: ${order.totalAmount.toInt()} ريال\n" +
                "الرصيد الجديد: ${updatedBalance.toInt()} ريال")
        }

        ctx.awaitingResponse = false
        ctx.pendingAction = ""
        conversationManager.saveContext(normalizedPhone, ctx)
        conversationManager.removeOrderDraft(normalizedPhone)

        metrics.recordEvent(SmsMetrics.EventType.ORDER_CONFIRMED, sender, orderId)
        return true
    }

    private suspend fun handleOrderCancel(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)
        val order = conversationManager.getOrderDraft(normalizedPhone)

        if (order != null) {
            replyManager.sendReply(sender,
                "❌ ${customer.commercialName}،\n" +
                "تم إلغاء الطلب.\n" +
                "نرحب بطلباتك في أي وقت.")

            val ctx = conversationManager.getOrCreateContext(normalizedPhone)
            ctx.awaitingResponse = false
            ctx.pendingAction = ""
            conversationManager.saveContext(normalizedPhone, ctx)
            conversationManager.removeOrderDraft(normalizedPhone)

            metrics.recordEvent(SmsMetrics.EventType.ORDER_CANCELLED, sender)
            return true
        } else {
            replyManager.sendReply(sender,
                "📦 ${customer.commercialName}،\n" +
                "لا يوجد طلب نشط للإلغاء.")
            return false
        }
    }

    private fun scheduleDriverAlert(
        customer: SmsCustomerResolver.CustomerInfo,
        order: SmsConversationManager.OrderDraft,
        orderId: String
    ) {
        val delayMs = order.deliveryTimestamp - System.currentTimeMillis() - (15 * 60 * 1000)

        if (delayMs <= 0) {
            sendDriverAlert(customer, order, orderId)
            return
        }

        handler.postDelayed({
            sendDriverAlert(customer, order, orderId)
        }, delayMs)

        Log.d(TAG, "Driver alert scheduled for order $orderId in ${delayMs / 60000} minutes")
    }

    private fun sendDriverAlert(
        customer: SmsCustomerResolver.CustomerInfo,
        order: SmsConversationManager.OrderDraft,
        orderId: String
    ) {
        scope.launch {
            val driverPhone = customerResolver.getDriverPhone()
            if (driverPhone == null) {
                Log.e(TAG, "Driver phone not set, cannot send alert")
                return@launch
            }

            val dabbasText = if (order.quantityDabbas > 0) {
                "${order.quantityDabbas.toInt()} دباب (${order.quantityLiters.toInt()} لتر)"
            } else {
                "${order.quantityLiters.toInt()} لتر"
            }

            replyManager.sendReply(driverPhone,
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
        }
    }

    private suspend fun handleGasolineRequestFlow(
        customer: SmsCustomerResolver.CustomerInfo,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)
        val order = conversationManager.getOrCreateOrderDraft(normalizedPhone, "gasoline")
        order.unitPrice = customerResolver.getGasolinePrice()
        order.step = 1
        ctx.awaitingResponse = true
        ctx.pendingAction = "awaiting_quantity_gasoline"
        conversationManager.saveContext(normalizedPhone, ctx)

        replyManager.sendReply(sender,
            "⛽ ${customer.commercialName}،\n" +
            "طلب بنزين جديد.\n" +
            "═══════════════════\n" +
            "كم لتر تريد؟\n" +
            "أرسل العدد فقط:")
        return true
    }

    private suspend fun handleBalanceQuery(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        val bal = customer.balance
        val points = customer.points

        val balanceText = if (bal >= 0) {
            "الرصيد الإجمالي عليكم: ${bal.toInt()} ريال"
        } else {
            "الرصيد الإجمالي لكم: ${kotlin.math.abs(bal).toInt()} ريال"
        }

        replyManager.sendReply(customer.phone,
            "💳 ${customer.commercialName}،\n" +
            "═══════════════════\n" +
            "$balanceText\n" +
            "🏆 النقاط: $points\n" +
            "👑 العضوية: ${customerResolver.getVipText(customer.vipLevel)}\n" +
            "═══════════════════\n\n" +
            "💡 للدفع: 'دفع [المبلغ]'\n" +
            "📊 للتفاصيل: 'تفاصيل'")
        return true
    }

    private suspend fun handlePaymentRequest(customer: SmsCustomerResolver.CustomerInfo, msgBody: String): Boolean {
        val amount = intentDetector.extractAmount(msgBody)
        if (amount > 0) {
            replyManager.sendReply(customer.phone,
                "💳 ${customer.commercialName}،\n" +
                "مبلغ الدفع: ${amount.toInt()} ريال\n\n" +
                "طرق الدفع:\n" +
                "1. كاش - زيارة المحطة\n" +
                "2. تحويل بنكي - أرسل 'تحويل'\n" +
                "3. تقسيط - أرسل 'تقسيط'")
        } else {
            replyManager.sendReply(customer.phone,
                "💳 ${customer.commercialName}،\n" +
                "الرصيد: ${customer.balance.toInt()} ريال\n\n" +
                "أرسل 'دفع [المبلغ]'\n" +
                "مثال: 'دفع 5000'")
        }
        return true
    }

    private suspend fun handleBankTransfer(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        replyManager.sendReply(customer.phone,
            "🏦 ${customer.commercialName}،\n" +
            "معلومات التحويل:\n" +
            "═══════════════════\n" +
            "البنك: بنك اليمن الدولي\n" +
            "الحساب: 1234567890\n" +
            "اسم: محطة أبو أحمد\n" +
            "═══════════════════\n\n" +
            "⚠️ بعد التحويل أرسل 'تم التحويل'")
        return true
    }

    private suspend fun handleOffersQuery(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        val vip = customer.vipLevel
        val vipOffer = when (vip) {
            3 -> "👑 ذهبي: خصم 15% + توصيل مجاني"
            2 -> "🥈 فضي: خصم 10% + توصيل نصف السعر"
            1 -> "🥉 برونزي: خصم 7%"
            else -> "💎 عادي: خصم 5%"
        }
        val dieselPrice = customerResolver.getDieselPrice()
        val gasolinePrice = customerResolver.getGasolinePrice()
        replyManager.sendReply(customer.phone,
            "🎁 ${customer.commercialName}،\n" +
            "═══════════════════\n" +
            "⛽ ديزل: ${dieselPrice.toInt()} ريال/لتر\n" +
            "⛽ بنزين: ${gasolinePrice.toInt()} ريال/لتر\n" +
            "═══════════════════\n" +
            "$vipOffer\n" +
            "═══════════════════")
        return true
    }

    private suspend fun handlePriceQuery(customer: SmsCustomerResolver.CustomerInfo, msgBody: String): Boolean {
        val product = when {
            msgBody.contains("ديزل") -> "diesel"
            msgBody.contains("بنزين") -> "gasoline"
            else -> "all"
        }
        val dieselPrice = customerResolver.getDieselPrice()
        val gasolinePrice = customerResolver.getGasolinePrice()
        val message = when (product) {
            "diesel" -> "⛽ سعر الديزل: ${dieselPrice.toInt()} ريال/لتر"
            "gasoline" -> "⛽ سعر البنزين: ${gasolinePrice.toInt()} ريال/لتر"
            else -> "📊 الأسعار:\n" +
                    "⛽ ديزل: ${dieselPrice.toInt()} ريال/لتر\n" +
                    "⛽ بنزين: ${gasolinePrice.toInt()} ريال/لتر"
        }
        replyManager.sendReply(customer.phone, message)
        return true
    }

    private suspend fun handleLoyaltyQuery(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        replyManager.sendReply(customer.phone,
            "🏆 ${customer.commercialName}،\n" +
            "═══════════════════\n" +
            "النقاط: ${customer.points}\n" +
            "الفئة: ${customerResolver.getVipText(customer.vipLevel)}\n" +
            "═══════════════════\n\n" +
            "💰 الاستبدال:\n" +
            "500 ➜ 25 ريال\n" +
            "1000 ➜ 60 ريال\n" +
            "2000 ➜ 150 ريال\n\n" +
            "أرسل 'استبدال [النقاط]'")
        return true
    }

    private suspend fun handleRedeemPoints(customer: SmsCustomerResolver.CustomerInfo, msgBody: String): Boolean {
        val points = intentDetector.extractAmount(msgBody).toInt()
        if (points <= 0) {
            replyManager.sendReply(customer.phone, "أرسل 'استبدال [النقاط]'")
            return false
        }
        if (customer.points < points) {
            replyManager.sendReply(customer.phone,
                "❌ نقاطك غير كافية!\n" +
                "المطلوب: $points\n" +
                "متاح: ${customer.points}")
            return false
        }
        val value = when {
            points >= 5000 -> points * 0.1
            points >= 2000 -> points * 0.075
            points >= 1000 -> points * 0.06
            points >= 500 -> points * 0.05
            else -> 0.0
        }
        replyManager.sendReply(customer.phone,
            "🎉 تم استبدال $points نقطة!\n" +
            "القيمة: ${value.toInt()} ريال\n" +
            "تم الإضافة لرصيدك.")
        return true
    }

    private suspend fun handleTrackOrder(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        val lastOrder = customerResolver.getLastOrderByPhone(customer.phone)
        if (lastOrder != null) {
            val status = lastOrder.optString("status", "unknown")
            val statusText = when (status) {
                "pending" -> "⏳ قيد الانتظار"
                "confirmed" -> "✅ مؤكد"
                "delivered" -> "🚚 تم التوصيل"
                else -> "⏳ قيد المعالجة"
            }
            replyManager.sendReply(customer.phone,
                "📦 ${customer.commercialName}،\n" +
                "آخر طلب:\n" +
                "═══════════════════\n" +
                "الرقم: ${lastOrder.optString("sale_code", "N/A")}\n" +
                "الكمية: ${lastOrder.optDouble("liters", 0.0).toInt()} لتر\n" +
                "الموقع: ${lastOrder.optString("delivery_location", "")}\n" +
                "الحالة: $statusText\n" +
                "═══════════════════")
        } else {
            replyManager.sendReply(customer.phone,
                "📦 ${customer.commercialName}،\n" +
                "لا توجد طلبات سابقة.\n" +
                "أرسل 'اريد ديزل' للطلب")
        }
        return true
    }

    private suspend fun handleOrderHistory(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        val history = customerResolver.getOrderHistoryByPhone(customer.phone, 5)
        if (history.length() > 0) {
            val sb = StringBuilder()
            sb.append("📊 ${customer.commercialName}، سجل الطلبات:\n")
            sb.append("═══════════════════\n")
            for (i in 0 until history.length()) {
                val order = history.getJSONObject(i)
                sb.append("🛢️ ${order.optString("sale_type", "diesel")} ")
                sb.append("${order.optDouble("liters", 0.0).toInt()} لتر ")
                sb.append("- ${order.optString("created_at", "")}\n")
            }
            sb.append("═══════════════════")
            replyManager.sendReply(customer.phone, sb.toString())
        } else {
            replyManager.sendReply(customer.phone, "لا يوجد سجل طلبات.")
        }
        return true
    }

    private suspend fun handleHelp(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        replyManager.sendReply(customer.phone,
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
            "📅 حجز - جدولة طلب\n" +
            "═══════════════════")
        return true
    }

    private suspend fun handleComplaint(customer: SmsCustomerResolver.CustomerInfo, msgBody: String): Boolean {
        val ticketId = System.currentTimeMillis() % 10000
        val managerPhone = customerResolver.getManagerPhone()
        replyManager.sendReply(customer.phone,
            "📝 ${customer.commercialName}،\n" +
            "تم استلام شكواك.\n" +
            "رقم التذكرة: #$ticketId\n" +
            "الرد خلال 24 ساعة.\n" +
            "📞 للعاجل: ${managerPhone ?: "غير متوفر"}")
        if (managerPhone != null) {
            replyManager.notifyManager(managerPhone,
                "🚨 شكوى\n" +
                "العميل: ${customer.commercialName}\n" +
                "الرسالة: ${msgBody.take(200)}")
        }
        return true
    }

    private suspend fun handleEmergency(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        val managerPhone = customerResolver.getManagerPhone() ?: "غير متوفر"
        replyManager.sendReply(customer.phone,
            "🚨 ${customer.commercialName}،\n" +
            "تم تفعيل الطوارئ!\n" +
            "═══════════════════\n" +
            "📞 الاتصال: $managerPhone\n" +
            "═══════════════════\n" +
            "سيتم الاتصال بك خلال 2 دقيقة!")
        if (managerPhone != "غير متوفر") {
            replyManager.notifyManager(managerPhone,
                "🚨 طوارئ!\n" +
                "العميل: ${customer.commercialName}\n" +
                "الرقم: ${customer.phone}\n" +
                "اتصل فوراً!")
        }
        return true
    }

    private suspend fun handleCallbackRequest(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        val managerPhone = customerResolver.getManagerPhone() ?: "غير متوفر"
        replyManager.sendReply(customer.phone,
            "📞 ${customer.commercialName}،\n" +
            "تم طلب الاتصال.\n" +
            "سيتم الاتصال خلال 15 دقيقة.")
        if (managerPhone != "غير متوفر") {
            replyManager.notifyManager(managerPhone,
                "📞 طلب اتصال\n" +
                "العميل: ${customer.commercialName}\n" +
                "الرقم: ${customer.phone}")
        }
        return true
    }

    private suspend fun handleLocationQuery(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        replyManager.sendReply(customer.phone,
            "📍 ${customer.commercialName}،\n" +
            "محطة أبو أحمد:\n" +
            "═══════════════════\n" +
            "بجانب مدرسة الاتحاد\n" +
            "الحميدة - العرش\n" +
            "═══════════════════\n" +
            "🕐 24 ساعة طوال أيام الأسبوع\n" +
            "🚨 طوارئ: 24 ساعة")
        return true
    }

    private suspend fun handleWorkingHours(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        replyManager.sendReply(customer.phone,
            "🕐 ${customer.commercialName}،\n" +
            "المحطة مفتوحة 24 ساعة\n" +
            "طوال أيام الأسبوع بما في ذلك الجمعة\n" +
            "🚨 طوارئ: 24 ساعة")
        return true
    }

    private suspend fun handleInvoiceRequest(customer: SmsCustomerResolver.CustomerInfo, msgBody: String): Boolean {
        when {
            msgBody.contains("آخر شهر") || msgBody.contains("last month") -> {
                replyManager.sendReply(customer.phone,
                    "📄 ${customer.commercialName}،\n" +
                    "فاتورة يونيو 2026:\n" +
                    "═══════════════════\n" +
                    "إجمالي: 78,000 ريال\n" +
                    "مدفوع: 50,000 ريال\n" +
                    "متبقي: 28,000 ريال\n" +
                    "══════════════════")
            }
            else -> {
                replyManager.sendReply(customer.phone,
                    "📄 ${customer.commercialName}،\n" +
                    "أرسل 'فاتورة آخر شهر'\n" +
                    "أو 'فاتورة [الشهر]'")
            }
        }
        return true
    }

    private suspend fun handleWeeklyReport(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        val history = customerResolver.getOrderHistoryByPhone(customer.phone, 100)
        var totalLiters = 0.0
        var totalCost = 0.0
        for (i in 0 until history.length()) {
            val order = history.getJSONObject(i)
            totalLiters += order.optDouble("liters", 0.0)
            totalCost += order.optDouble("net_amount", 0.0)
        }
        replyManager.sendReply(customer.phone,
            "📊 ${customer.commercialName}،\n" +
            "التقرير الأسبوعي:\n" +
            "═══════════════════\n" +
            "الطلبات: ${history.length()}\n" +
            "اللترات: ${totalLiters.toInt()}\n" +
            "الإنفاق: ${totalCost.toInt()} ريال\n" +
            "النقاط: ${customer.points}\n" +
            "══════════════════")
        return true
    }

    private suspend fun handleSchedule(customer: SmsCustomerResolver.CustomerInfo, msgBody: String): Boolean {
        if (msgBody.contains("كل") && (msgBody.contains("يوم") || msgBody.contains("أسبوع") || msgBody.contains("شهر"))) {
            return handleRecurringOrder(customer, msgBody)
        }

        val timeInfo = intentDetector.parseDeliveryTime(msgBody)
        if (timeInfo != null) {
            replyManager.sendReply(customer.phone,
                "📅 ${customer.commercialName}،\n" +
                "تم حجز موعد:\n" +
                "الوقت: ${timeInfo.displayTime}\n" +
                "سنرسل تذكير قبل ساعة.")
        } else {
            replyManager.sendReply(customer.phone,
                "📅 ${customer.commercialName}،\n" +
                "أرسل 'حجز [الوقت]'\n" +
                "مثال: 'حجز 10:00 ص'\n" +
                "أو 'كل يوم 10:00 ص' للجدولة المتكررة")
        }
        return true
    }

    private suspend fun handleRecurringOrder(customer: SmsCustomerResolver.CustomerInfo, msgBody: String): Boolean {
        val parsed = intentDetector.parseRecurringSchedule(msgBody)
        if (parsed != null) {
            val (period, day) = parsed
            val nextDate = calculateNextDate(period, day)
            if (nextDate != null) {
                val prefs = conversationManager.getOrCreatePreferences(PhoneUtils.normalize(customer.phone))
                val recurring = SmsConversationManager.RecurringOrder(
                    customerId = customer.phone,
                    quantity = prefs.preferredQuantity,
                    location = prefs.preferredLocation,
                    schedule = "${period}_$day",
                    nextDelivery = nextDate
                )
                conversationManager.saveRecurringOrder(recurring)
                replyManager.sendReply(customer.phone,
                    "📅 ${customer.commercialName}،\n" +
                    "تم جدولة طلبك:\n" +
                    "الكمية: ${recurring.quantity.toInt()} لتر\n" +
                    "الموقع: ${recurring.location}\n" +
                    "التاريخ القادم: ${dateFormat.format(Date(nextDate))}")
                security.logSecurityEvent("RECURRING_ORDER", customer.phone, "Period: $period, Day: $day")
                return true
            }
        }
        return false
    }

    private fun calculateNextDate(period: String, day: String): Long? {
        val cal = Calendar.getInstance()
        when (period) {
            "يوم" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }
            "أسبوع" -> {
                val targetDay = when (day) {
                    "السبت" -> Calendar.SATURDAY
                    "الأحد" -> Calendar.SUNDAY
                    "الاثنين" -> Calendar.MONDAY
                    "الثلاثاء" -> Calendar.TUESDAY
                    "الأربعاء" -> Calendar.WEDNESDAY
                    "الخميس" -> Calendar.THURSDAY
                    "الجمعة" -> Calendar.FRIDAY
                    else -> return null
                }
                cal.set(Calendar.DAY_OF_WEEK, targetDay)
                if (cal.timeInMillis < System.currentTimeMillis()) {
                    cal.add(Calendar.WEEK_OF_YEAR, 1)
                }
                return cal.timeInMillis
            }
            "شهر" -> {
                val dayOfMonth = day.toIntOrNull() ?: return null
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                if (cal.timeInMillis < System.currentTimeMillis()) {
                    cal.add(Calendar.MONTH, 1)
                }
                return cal.timeInMillis
            }
        }
        return null
    }

    private suspend fun handleRating(customer: SmsCustomerResolver.CustomerInfo, msgBody: String): Boolean {
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
            replyManager.sendReply(customer.phone,
                "⭐ ${customer.commercialName}،\n" +
                "تقييمك: $rating/5\n" +
                "$response")
            val managerPhone = customerResolver.getManagerPhone()
            if (managerPhone != null) {
                replyManager.notifyManager(managerPhone,
                    "📊 تقييم\n" +
                    "العميل: ${customer.commercialName}\n" +
                    "التقييم: $rating/5")
            }
            return true
        }
        return false
    }

    private suspend fun handleGreeting(
        customer: SmsCustomerResolver.CustomerInfo,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {
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
        replyManager.sendReply(customer.phone,
            "$greeting ${customer.commercialName}! 🌟\n" +
            "أهلاً بك في محطة أبو أحمد." +
            personalized +
            "\n\nأرسل 'استعلام' للخدمات")
        return true
    }

    private suspend fun handleThanks(customer: SmsCustomerResolver.CustomerInfo): Boolean {
        replyManager.sendReply(customer.phone,
            "🙏 ${customer.commercialName}،\n" +
            "شكراً لك! نسعد بخدمتك دائماً.\n\n" +
            "💡 للطلب السريع: 'اريد ديزل'")
        return true
    }

    private suspend fun handleUnknown(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        ctx: SmsConversationManager.ConversationContext
    ): Boolean {
        val sender = customer.phone
        val normalizedPhone = PhoneUtils.normalize(sender)

        if (ctx.awaitingResponse && ctx.pendingAction.isNotEmpty()) {
            when (ctx.pendingAction) {
                "awaiting_quantity", "awaiting_quantity_gasoline" -> {
                    if (Regex(""".*\d+.*""").matches(msgBody)) {
                        val prefs = conversationManager.getOrCreatePreferences(normalizedPhone)
                        return handleQuantityResponse(customer, msgBody, ctx, prefs)
                    }
                }
                "awaiting_location" -> {
                    val prefs = conversationManager.getOrCreatePreferences(normalizedPhone)
                    return handleLocationResponse(customer, msgBody, ctx, prefs)
                }
                "awaiting_time" -> {
                    val prefs = conversationManager.getOrCreatePreferences(normalizedPhone)
                    return handleTimeResponse(customer, msgBody, ctx, prefs)
                }
                "awaiting_confirmation" -> {
                    if (msgBody.contains("تأكيد") || msgBody.contains("نعم")) {
                        val prefs = conversationManager.getOrCreatePreferences(normalizedPhone)
                        return handleOrderConfirmation(customer, ctx, prefs)
                    }
                    if (msgBody.contains("إلغاء") || msgBody.contains("لا")) {
                        return handleOrderCancel(customer)
                    }
                }
            }
        }

        val managerPhone = customerResolver.getManagerPhone()
        replyManager.sendReply(sender,
            "🤔 ${customer.commercialName}،\n" +
            "لم أفهم طلبك.\n\n" +
            "هل تقصد:\n" +
            "1. طلب ديزل - 'اريد ديزل'\n" +
            "2. استعلام - 'رصيد'\n" +
            "3. العروض - 'عروض'\n" +
            "4. المساعدة - 'استعلام'\n" +
            "5. جدولة - 'حجز [الوقت]'\n\n" +
            "📞 أو اتصل: ${managerPhone ?: "غير متوفر"}")
        return true
    }

    private suspend fun cleanupOldData() {
        val retentionDays = getRetentionDays()
        try {
            val cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60 * 60 * 1000)
            val cutoffDate = dateFormat.format(Date(cutoff))

            db.execSQL("DELETE FROM user_activity_log WHERE created_at < ?", arrayOf(cutoffDate))
            db.execSQL("DELETE FROM sms_logs WHERE created_at < ?", arrayOf(cutoffDate))
            db.execSQL("DELETE FROM customer_ledger WHERE transaction_date < ?", arrayOf(cutoffDate))
            db.execSQL("UPDATE sales_transactions SET archived = 1 WHERE created_at < ? AND status = 'delivered'", arrayOf(cutoffDate))

            metrics.cleanupOldMetrics(retentionDays)

            Log.d(TAG, "Cleanup completed, retention days: $retentionDays")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}")
        }
    }

    private fun getRetentionDays(): Int {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT setting_value FROM system_settings WHERE setting_key = 'retention_days' LIMIT 1",
            null
        )
        val days = cursor.use {
            if (it.moveToFirst()) it.getInt(0) else DEFAULT_RETENTION_DAYS
        }
        return days.coerceIn(7, 365)
    }
}
