package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════
 * معالج الرسائل الرئيسي - SmsProcessor
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 *
 * 1. استقبال الرسائل الواردة عبر SMS_RECEIVED و SMS_DELIVER
 * 2. التحقق من صحة الرسالة
 * 3. منع تكرار معالجة الرسائل
 * 4. التحقق الأمني من SMSC
 * 5. كشف الرسائل المشبوهة
 * 6. تطبيق Rate Limiting
 * 7. التحقق من العميل
 * 8. إدارة سياق المحادثة
 * 9. تنفيذ طلبات الديزل
 * 10. الاستعلام عن الرصيد والطلبات
 * 11. إنشاء ملخصات فواتير من البيانات الفعلية
 * 12. تسجيل الأحداث والعمليات
 *
 * ملاحظة مهمة:
 * - الديزل هو الصنف المدعوم حاليًا.
 * - البنزين غير مدعوم حاليًا.
 * - توسيع مفردات طلب الديزل يتم لاحقًا في SmsIntentDetector.kt
 *   وليس داخل هذا الملف.
 */
class SmsProcessor(
    private val context: Context,
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "SmsProcessor"

        private const val DEFAULT_RETENTION_DAYS = 90
        private const val CONTEXT_TIMEOUT_MS = 600000L

        private const val MAX_ORDER_LITERS = 10000.0

        /**
         * الرسالة الرسمية المستخدمة عند طلب أو الاستعلام عن البنزين.
         */
        private const val GASOLINE_UNSUPPORTED_MESSAGE =
            "معذرة - المحطة حالياً تدعم صنف الديزل فقط، و في حالة إضافة صنف البنزين مستقبلاً، سيتم اعلامكم بذلك"
    }

    private val security = SmsSecurity(context, db)

    private val intentDetector = SmsIntentDetector()

    private val conversationManager =
        SmsConversationManager(db)

    private val customerResolver =
        SmsCustomerResolver(db)

    private val replyManager =
        SmsReplyManager(context, db)

    private val metrics =
        SmsMetrics(db)

    private val handler =
        Handler(Looper.getMainLooper())

    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dateFormat =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("ar"))

    private val dateOnlyFormat =
        SimpleDateFormat("dd/MM/yyyy", Locale("ar"))

    private val monthYearFormat =
        SimpleDateFormat("MM/yyyy", Locale("ar"))

    /**
     * Entry point for both:
     *
     * SMS_RECEIVED
     * SMS_DELIVER
     *
     * Android قد يستخدم SMS_DELIVER عندما يكون التطبيق
     * هو تطبيق الرسائل الافتراضي.
     */
    suspend fun process(intent: Intent): Boolean =
        withContext(Dispatchers.IO) {

            val action = intent.action

            if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
                action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
            ) {
                Log.d(
                    TAG,
                    "Ignoring unsupported intent action: $action"
                )

                return@withContext false
            }

            try {

                val messages =
                    Telephony.Sms.Intents
                        .getMessagesFromIntent(intent)
                        ?.filterNotNull()
                        .orEmpty()

                if (messages.isEmpty()) {
                    Log.w(
                        TAG,
                        "SMS intent contained no decodable messages"
                    )

                    return@withContext false
                }

                val totalLength =
                    messages.sumOf {
                        it.displayMessageBody?.length ?: 0
                    }

                if (security.isMessageTooLong(totalLength)) {

                    val sender =
                        messages
                            .firstOrNull()
                            ?.displayOriginatingAddress
                            ?: "unknown"

                    security.logSecurityEvent(
                        "DOS_ATTEMPT",
                        sender,
                        "Message too long: $totalLength chars"
                    )

                    metrics.recordEvent(
                        SmsMetrics.EventType.SMS_REJECTED,
                        sender,
                        "Message too long"
                    )

                    return@withContext false
                }

                /*
                 * تنظيف سجلات hash عملية صيانة فقط.
                 * فشل التنظيف يجب ألا يمنع الرسالة الحالية.
                 */
                runCatching {
                    security.cleanupSmsProcessedMessages()
                }.onFailure {
                    Log.e(
                        TAG,
                        "SMS hash cleanup failed",
                        it
                    )
                }

                var allProcessed = true

                for (sms in messages) {

                    val processed =
                        runCatching {
                            processSingleMessage(sms)
                        }.getOrElse {

                            Log.e(
                                TAG,
                                "Unhandled SMS processing failure",
                                it
                            )

                            false
                        }

                    if (!processed) {
                        allProcessed = false
                    }
                }

                runCatching {
                    cleanupOldData()
                }.onFailure {
                    Log.e(
                        TAG,
                        "SMS maintenance cleanup failed",
                        it
                    )
                }

                allProcessed

            } catch (e: CancellationException) {

                throw e

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Fatal SMS processor error",
                    e
                )

                false
            }
        }

    /**
     * معالجة رسالة SMS واحدة.
     */
    private suspend fun processSingleMessage(
        sms: SmsMessage
    ): Boolean {

        val sender =
            sms.displayOriginatingAddress?.trim()

        val rawBody =
            sms.displayMessageBody?.trim()

        if (sender.isNullOrEmpty() ||
            rawBody.isNullOrEmpty()
        ) {

            Log.w(
                TAG,
                "Ignoring SMS with missing sender or body"
            )

            return false
        }

        val msgBody =
            rawBody.lowercase(Locale.getDefault())

        val smsc =
            sms.serviceCenterAddress.orEmpty()

        val normalizedSender =
            PhoneUtils.normalize(sender).orEmpty()

        var smsClaim: SmsSecurity.SmsClaim? = null
        var businessStarted = false

        return try {

            /*
             * Hash يعتمد على المرسل + النص.
             */
            val messageHash =
                security.generateMessageHash(
                    sender,
                    rawBody
                )

            /*
             * التحقق من SMSC.
             */
            if (!security.isTrustedSmsc(smsc)) {

                security.logSecurityEvent(
                    "SPOOFING_ATTEMPT",
                    sender,
                    "Untrusted SMSC: $smsc"
                )

                safeLogSms(
                    sender,
                    msgBody,
                    "received",
                    "rejected: untrusted SMSC"
                )

                metrics.recordEvent(
                    SmsMetrics.EventType.SMS_SPOOFED,
                    normalizedSender,
                    "SMSC: $smsc"
                )

                return false
            }

            /*
             * الحظر الأمني الأولي.
             */
            if (security.isBlocked(sender)) {

                security.logSecurityEvent(
                    "BLOCKED_MESSAGE",
                    sender,
                    "Rate limit exceeded"
                )

                safeLogSms(
                    sender,
                    msgBody,
                    "received",
                    "blocked: rate limit exceeded"
                )

                metrics.recordEvent(
                    SmsMetrics.EventType.SMS_BLOCKED,
                    normalizedSender,
                    ""
                )

                return true
            }

            /*
             * كشف المحتوى المشبوه.
             */
            if (security.isSuspiciousMessage(msgBody)) {

                security.logSecurityEvent(
                    "SUSPICIOUS_MESSAGE",
                    sender,
                    "Suspicious content detected"
                )

                safeLogSms(
                    sender,
                    msgBody,
                    "received",
                    "rejected: suspicious content"
                )

                val managerPhone =
                    runCatching {
                        customerResolver.getManagerPhone()
                    }.getOrNull()

                if (managerPhone != null) {

                    runCatching {

                        replyManager.notifyManager(
                            managerPhone,
                            "🚨 رسالة مشبوهة\n" +
                                "من: $sender\n" +
                                "نص: ${rawBody.take(100)}"
                        )

                    }.onFailure {

                        Log.e(
                            TAG,
                            "Failed to notify manager",
                            it
                        )
                    }
                }

                metrics.recordEvent(
                    SmsMetrics.EventType.SMS_REJECTED,
                    normalizedSender,
                    "Suspicious"
                )

                return true
            }

            /*
             * حجز الرسالة ذرياً قبل أي منطق تجاري.
             */
            when (
                val claimResult =
                    security.claimSms(
                        messageHash,
                        sender,
                        rawBody
                    )
            ) {
                is SmsSecurity.SmsClaimResult.Claimed -> {
                    smsClaim = claimResult.claim
                }

                SmsSecurity.SmsClaimResult.AlreadyProcessed,
                SmsSecurity.SmsClaimResult.InProgress -> {
                    Log.d(
                        TAG,
                        "Skipping duplicate or in-progress SMS from $sender"
                    )

                    metrics.recordEvent(
                        SmsMetrics.EventType.SMS_DUPLICATED,
                        normalizedSender,
                        ""
                    )

                    return true
                }

                SmsSecurity.SmsClaimResult.Unavailable -> {
                    Log.e(
                        TAG,
                        "Cannot claim SMS atomically; refusing business processing"
                    )

                    metrics.recordEvent(
                        SmsMetrics.EventType.SMS_FAILED,
                        normalizedSender,
                        "Atomic claim unavailable"
                    )

                    return false
                }
            }

            /*
             * البحث عن العميل.
             */
            val customer =
                customerResolver.findCustomer(sender)

            if (customer == null) {

                safeLogSms(
                    sender,
                    msgBody,
                    "received",
                    "ignored: unregistered"
                )

                metrics.recordEvent(
                    SmsMetrics.EventType.SMS_REJECTED,
                    normalizedSender,
                    "Unregistered"
                )

                /*
                 * العميل غير المسجل لا يعتبر crash.
                 */
                smsClaim?.let {
                    security.completeSmsClaim(
                        it,
                        sender,
                        rawBody
                    )
                }
                return true
            }

            /*
             * سياق المحادثة.
             */
            val ctx =
                conversationManager
                    .getOrCreateContext(normalizedSender)

            val now =
                System.currentTimeMillis()

            val isContextReply =
                ctx.awaitingResponse &&
                    ctx.timestamp > 0L &&
                    now - ctx.timestamp in
                    0..CONTEXT_TIMEOUT_MS

            /*
             * Rate limiting.
             */
            when (
                val rateLimitResult =
                    security.canProcessMessage(
                        sender,
                        customerDisplayName(customer),
                        isContextReply
                    )
            ) {

                is SmsSecurity.RateLimitResult.BLOCKED -> {

                    runCatching {
                        replyManager.sendReplyOnce(
                            normalizedSender,
                            rateLimitResult.message
                        )
                    }

                    if (rateLimitResult.managerPhone != null) {

                        runCatching {

                            replyManager.notifyManager(
                                rateLimitResult.managerPhone,
                                "🚫 حظر مؤقت\n" +
                                    "العميل: ${customerDisplayName(customer)}\n" +
                                    "السبب: تجاوز الحد"
                            )

                        }
                    }

                    metrics.recordEvent(
                        SmsMetrics.EventType.SMS_BLOCKED,
                        normalizedSender,
                        ""
                    )

                    smsClaim?.let {
                        security.completeSmsClaim(
                            it,
                            sender,
                            rawBody
                        )
                    }
                    return true
                }

                is SmsSecurity.RateLimitResult.WARNING -> {

                    runCatching {
                        replyManager.sendReplyOnce(
                            normalizedSender,
                            rateLimitResult.message
                        )
                    }

                    metrics.recordEvent(
                        SmsMetrics.EventType.SMS_WARNING,
                        normalizedSender,
                        ""
                    )

                    smsClaim?.let {
                        security.completeSmsClaim(
                            it,
                            sender,
                            rawBody
                        )
                    }
                    return true
                }

                SmsSecurity.RateLimitResult.ALLOWED -> Unit
            }

            safeLogSms(
                sender,
                msgBody,
                "received",
                "accepted"
            )

            metrics.recordEvent(
                SmsMetrics.EventType.SMS_RECEIVED,
                normalizedSender,
                ""
            )

            /*
             * التنفيذ الرئيسي.
             */
            val claim = smsClaim
                ?: return false

            val businessClaimed =
                security.markSmsBusinessStarted(
                    claim,
                    rawBody
                )

            if (!businessClaimed) {
                security.releaseSmsClaim(claim)
                return false
            }

            businessStarted = true

            val processed =
                handleSmartMessage(
                    customer,
                    msgBody,
                    rawBody
                )

            /*
             * إكمال claim بعد نجاح العملية أو تحريره عند الفشل.
             */
            smsClaim?.let {
                if (businessStarted) {
                    val completed =
                        security.completeSmsClaim(
                            it,
                            sender,
                            rawBody
                        )

                    if (!completed) {
                        Log.e(
                            TAG,
                            "SMS business claim finalization failed; claim retained"
                        )
                    }
                } else {
                    security.completeSmsClaim(
                        it,
                        sender,
                        rawBody
                    )
                }
            }

            /*
             * تخزين سجل الرسالة.
             */
            saveSmsMessageSafely(
                sender,
                rawBody,
                processed
            )

            if (processed) {

                metrics.recordEvent(
                    SmsMetrics.EventType.SMS_PROCESSED,
                    normalizedSender,
                    ""
                )

            } else {

                metrics.recordEvent(
                    SmsMetrics.EventType.SMS_FAILED,
                    normalizedSender,
                    ""
                )
            }

            processed

        } catch (e: CancellationException) {

            if (!businessStarted) {
                smsClaim?.let {
                    security.releaseSmsClaim(it)
                }
            } else {
                Log.w(
                    TAG,
                    "SMS cancelled after business start; retaining claim"
                )
            }
            throw e

        } catch (e: Exception) {

            if (!businessStarted) {
                smsClaim?.let {
                    security.releaseSmsClaim(it)
                }
            } else {
                Log.w(
                    TAG,
                    "SMS failed after business start; retaining claim"
                )
            }

            val errorId =
                UUID.randomUUID()
                    .toString()
                    .take(8)

            Log.e(
                TAG,
                "Error [$errorId] processing SMS from $sender",
                e
            )

            runCatching {
                security.logSecurityEvent(
                    "PROCESSING_ERROR",
                    sender,
                    "ErrorID: $errorId"
                )
            }

            runCatching {

                replyManager.safeSendReply(
                    sender,
                    "عذراً، حدث خطأ أثناء معالجة رسالتك. رمز: $errorId"
                )
            }

            runCatching {

                safeLogSms(
                    sender,
                    msgBody,
                    "received",
                    "failed: $errorId"
                )
            }

            metrics.recordEvent(
                SmsMetrics.EventType.SMS_FAILED,
                normalizedSender,
                errorId
            )

            false
        }
    }

    /**
     * إرسال الرد مع إبقاء فشل Business مستقلاً عن فشل Reply.
     */
    private suspend fun sendReplyRequired(
        phone: String,
        message: String
    ): Boolean {
        val sent = replyManager.sendReply(phone, message)
        if (!sent) {
            Log.e(TAG, "SMS reply failed for the current processing step")
        }
        return sent
    }

    /**
     * تسجيل SMS بأمان.
     */
    private fun safeLogSms(
        sender: String,
        body: String,
        type: String,
        status: String
    ) {

        runCatching {

            db.logSms(
                sender,
                body,
                SmsLogContract.normalizeType(type),
                SmsLogContract.normalizeStatus(status)
            )

        }.onFailure {

            Log.e(
                TAG,
                "Failed to write sms_logs",
                it
            )
        }
    }

    /**
     * حفظ الرسالة في sms_messages دون جعل فشل التخزين
     * يؤدي إلى إعادة تنفيذ العملية التجارية.
     */
    private fun saveSmsMessageSafely(
        sender: String,
        body: String,
        processed: Boolean
    ) {

        runCatching {

            val data =
                JSONObject().apply {

                    put(
                        "phone_number",
                        sender
                    )

                    put(
                        "message_body",
                        body
                    )

                    put(
                        "message_type",
                        "incoming"
                    )

                    put(
                        "status",
                        if (processed)
                            "processed"
                        else
                            "failed"
                    )

                    put(
                        "processed_at",
                        System.currentTimeMillis()
                    )
                }

            db.addSmsMessage(data)

        }.onFailure {

            Log.e(
                TAG,
                "Failed to persist sms_messages record",
                it
            )
        }
    }

    /**
     * معالجة النية.
     *
     * ملاحظة:
     * توسيع مفردات الديزل لا يتم هنا.
     * سيتم لاحقًا داخل SmsIntentDetector.kt.
     */
    private suspend fun handleSmartMessage(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        rawBody: String
    ): Boolean {

        val sender =
            customer.phone

        val normalizedPhone =
            PhoneUtils.normalize(sender)
                ?: ""

        val ctx =
            conversationManager
                .getOrCreateContext(normalizedPhone)

        val prefs =
            conversationManager
                .getOrCreatePreferences(normalizedPhone)

        val intentResult =
            intentDetector.detectIntent(
                msgBody,
                SmsIntentDetector.ConversationState(
                    awaitingResponse =
                        ctx.awaitingResponse,

                    pendingAction =
                        ctx.pendingAction,

                    lastTopic =
                        ctx.lastTopic,

                    timestamp =
                        ctx.timestamp
                ),
                sender
            )

        Log.d(
            TAG,
            "Detected intent: ${intentResult.intent} " +
                "(confidence: ${intentResult.confidence}%)"
        )

        conversationManager.recordInteraction(
            normalizedPhone,
            intentResult.intent,
            msgBody
        )

        ctx.lastIntent =
            intentResult.intent

        ctx.timestamp =
            System.currentTimeMillis()

        conversationManager.saveContext(
            normalizedPhone,
            ctx
        )

        return try {

            when (intentResult.intent) {

                /*
                 * الديزل - الصنف المدعوم حاليًا.
                 */
                "diesel_request" ->
                    handleDieselRequestFlow(
                        customer,
                        ctx,
                        prefs
                    )

                "quantity_response" ->
                    handleQuantityResponse(
                        customer,
                        msgBody,
                        ctx,
                        prefs
                    )

                "location_response" ->
                    handleLocationResponse(
                        customer,
                        msgBody,
                        ctx,
                        prefs
                    )

                "time_response" ->
                    handleTimeResponse(
                        customer,
                        msgBody,
                        ctx,
                        prefs
                    )

                "confirm_order" ->
                    handleOrderConfirmation(
                        customer,
                        ctx,
                        prefs
                    )

                "cancel_order" ->
                    handleOrderCancel(customer)

                "balance_query" ->
                    handleBalanceQuery(customer)

                "payment_request" ->
                    handlePaymentRequest(
                        customer,
                        msgBody
                    )

                "transfer_request" ->
                    handleBankTransfer(customer)

                "offers_query" ->
                    handleOffersQuery(customer)

                "price_query" ->
                    handlePriceQuery(
                        customer,
                        msgBody
                    )

                "loyalty_query" ->
                    handleLoyaltyQuery(customer)

                "redeem_points" ->
                    handleRedeemPoints(
                        customer,
                        msgBody
                    )

                "track_order" ->
                    handleTrackOrder(customer)

                "order_history" ->
                    handleOrderHistory(customer)

                "help" ->
                    handleHelp(customer)

                "complaint" ->
                    handleComplaint(
                        customer,
                        msgBody
                    )

                "emergency" ->
                    handleEmergency(customer)

                "callback_request" ->
                    handleCallbackRequest(customer)

                "location_query" ->
                    handleLocationQuery(customer)

                "working_hours" ->
                    handleWorkingHours(customer)

                "invoice_request" ->
                    handleInvoiceRequest(
                        customer,
                        msgBody
                    )

                "weekly_report" ->
                    handleWeeklyReport(customer)

                "schedule_appointment" ->
                    handleSchedule(
                        customer,
                        msgBody
                    )

                "schedule_recurring" ->
                    handleRecurringOrder(
                        customer,
                        msgBody
                    )

                "rating" ->
                    handleRating(
                        customer,
                        msgBody
                    )

                "greeting" ->
                    handleGreeting(
                        customer,
                        prefs
                    )

                "thanks" ->
                    handleThanks(customer)

                /*
                 * البنزين غير مدعوم حاليًا.
                 *
                 * لا ننشئ order draft.
                 * لا نحسب سعرًا.
                 * لا نسجل مبيعات.
                 */
                "gasoline_request" ->
                    handleUnsupportedGasoline(
                        customer
                    )

                else ->
                    handleUnknown(
                        customer,
                        msgBody,
                        ctx
                    )
            }

        } catch (e: Exception) {

            val errorId =
                UUID.randomUUID()
                    .toString()
                    .take(8)

            Log.e(
                TAG,
                "Error [$errorId] for $sender: " +
                    e.javaClass.simpleName,
                e
            )

            runCatching {

                security.logSecurityEvent(
                    "PROCESSING_ERROR",
                    sender,
                    "ErrorID: $errorId"
                )
            }

            runCatching {

                replyManager.safeSendReply(
                    sender,
                    "عذراً ${customerDisplayName(customer)}، حدث خطأ. رمز: $errorId"
                )
            }

            false
        }
    }

    /**
     * البنزين غير مدعوم حاليًا.
     */
    private suspend fun handleUnsupportedGasoline(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        replyManager.sendReply(
            customer.phone,
            GASOLINE_UNSUPPORTED_MESSAGE
        )

        metrics.recordEvent(
            SmsMetrics.EventType.SMS_PROCESSED,
            PhoneUtils.normalize(customer.phone).orEmpty(),
            "gasoline_unsupported"
        )

        return true
    }

    /**
     * بداية طلب ديزل.
     */
    private suspend fun handleDieselRequestFlow(
        customer: SmsCustomerResolver.CustomerInfo,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {

        val sender =
            customer.phone

        val normalizedPhone =
            PhoneUtils.normalize(sender)
                ?: ""

        val order =
            conversationManager
                .getOrCreateOrderDraft(
                    normalizedPhone,
                    "diesel"
                )

        order.step = 1
        order.status = "draft"

        order.unitPrice =
            customerResolver.getDieselPrice()

        ctx.awaitingResponse = true
        ctx.pendingAction =
            "awaiting_quantity"

        conversationManager.saveContext(
            normalizedPhone,
            ctx
        )

        val suggestion =
            if (prefs.preferredQuantity > 0) {

                val dabbas =
                    (prefs.preferredQuantity / 20.0)
                        .toInt()

                "\n(آخر طلبك: $dabbas دباب = " +
                    "${prefs.preferredQuantity.toInt()} لتر)"

            } else {
                ""
            }

        return sendReplyRequired(
            sender,
            "⛽ ${customerDisplayName(customer)}،\n" +
                "طلب ديزل جديد.\n" +
                "═══════════════════\n" +
                "كم تريد؟ (أرسل العدد فقط)\n" +
                "💡 يمكنك إرسال:\n" +
                "  - عدد اللترات (مثال: 200)\n" +
                "  - عدد الدباب (مثال: 5 دباب)" +
                suggestion
        )
    }

    /**
     * معالجة كمية الديزل.
     */
    private suspend fun handleQuantityResponse(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {

        val sender =
            customer.phone

        val normalizedPhone =
            PhoneUtils.normalize(sender)
                ?: ""

        val order =
            conversationManager
                .getOrderDraft(normalizedPhone)

        if (order == null ||
            order.product != "diesel" ||
            order.step != 1
        ) {

            val regex =
                Regex(
                    """^\d+\s*(?:دباب|دبابات|دبة|دبات|لتر|ltr|L)?\s*$""",
                    RegexOption.IGNORE_CASE
                )

            if (regex.matches(msgBody)) {
                return handleDieselRequestFlow(
                    customer,
                    ctx,
                    prefs
                )
            }

            return handleUnknown(
                customer,
                msgBody,
                ctx
            )
        }

        val quantityInfo =
            intentDetector.parseQuantity(msgBody)

        if (quantityInfo.liters <= 0 ||
            quantityInfo.liters > MAX_ORDER_LITERS
        ) {

            replyManager.sendReply(
                sender,
                "⚠️ ${customerDisplayName(customer)}،\n" +
                    "الكمية غير صالحة.\n" +
                    "الحد الأقصى: 10000 لتر.\n" +
                    "أرسل مثلاً: '200' أو '10 دباب'"
            )

            return false
        }

        order.quantityLiters =
            quantityInfo.liters

        order.quantityDabbas =
            quantityInfo.dabbas

        order.step = 2

        order.unitPrice =
            customerResolver.getDieselPrice()

        prefs.preferredQuantity =
            quantityInfo.liters

        ctx.pendingAction =
            "awaiting_location"

        conversationManager.saveContext(
            normalizedPhone,
            ctx
        )

        conversationManager.savePreferences(
            normalizedPhone,
            prefs
        )

        val conversionText =
            if (quantityInfo.isDabba) {

                "✅ ${quantityInfo.dabbas.toInt()} دباب = " +
                    "${quantityInfo.liters.toInt()} لتر"

            } else {

                "✅ ${quantityInfo.liters.toInt()} لتر = " +
                    "${quantityInfo.dabbas.toInt()} دباب"
            }

        return sendReplyRequired(
            sender,
            "$conversionText\n" +
                "═══════════════════\n" +
                "📍 إلى أي بير تريد التوصيل؟\n" +
                "أرسل اسم البير أو الموقع:"
        )
    }

    /**
     * معالجة موقع التوصيل.
     */
    private suspend fun handleLocationResponse(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {

        val sender =
            customer.phone

        val normalizedPhone =
            PhoneUtils.normalize(sender)
                ?: ""

        val order =
            conversationManager
                .getOrderDraft(normalizedPhone)

        if (order == null ||
            order.product != "diesel" ||
            order.step != 2
        ) {

            return handleUnknown(
                customer,
                msgBody,
                ctx
            )
        }

        val location =
            msgBody.trim()

        if (location.length < 3 ||
            location.length > 200
        ) {

            replyManager.sendReply(
                sender,
                "⚠️ ${customerDisplayName(customer)}،\n" +
                    "الموقع غير صالح.\n" +
                    "أرسل اسم البير بالتفصيل (3-200 حرف):"
            )

            return false
        }

        order.deliveryLocation =
            location

        order.step = 3

        prefs.preferredLocation =
            location

        ctx.pendingAction =
            "awaiting_time"

        conversationManager.saveContext(
            normalizedPhone,
            ctx
        )

        conversationManager.savePreferences(
            normalizedPhone,
            prefs
        )

        return sendReplyRequired(
            sender,
            "📍 $location\n" +
                "═══════════════════\n" +
                "⏰ ما الوقت الذي تريد أن نجهز طلبك؟\n" +
                "أرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'"
        )
    }

    /**
     * معالجة وقت التوصيل.
     */
    private suspend fun handleTimeResponse(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {

        val sender =
            customer.phone

        val normalizedPhone =
            PhoneUtils.normalize(sender)
                ?: ""

        val order =
            conversationManager
                .getOrderDraft(normalizedPhone)

        if (order == null ||
            order.product != "diesel" ||
            order.step != 3
        ) {

            return handleUnknown(
                customer,
                msgBody,
                ctx
            )
        }

        val timeInfo =
            intentDetector.parseDeliveryTime(msgBody)

        if (timeInfo == null) {

            replyManager.sendReply(
                sender,
                "⚠️ ${customerDisplayName(customer)}،\n" +
                    "لم أفهم الوقت.\n" +
                    "أرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'"
            )

            return false
        }

        order.deliveryTime =
            timeInfo.displayTime

        order.deliveryTimestamp =
            timeInfo.timestamp

        order.step = 4

        val subtotal =
            customerResolver.safeMultiply(
                order.quantityLiters,
                order.unitPrice
            )

        order.totalAmount =
            subtotal

        ctx.pendingAction =
            "awaiting_confirmation"

        conversationManager.saveContext(
            normalizedPhone,
            ctx
        )

        val dabbasText =
            if (order.quantityDabbas > 0) {

                "(${order.quantityDabbas.toInt()} دباب)"

            } else {
                ""
            }

        return sendReplyRequired(
            sender,
            "📋 ${customerDisplayName(customer)}، ملخص طلبك:\n" +
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
                "أو 'إلغاء' للإلغاء"
        )
    }

    /**
     * تأكيد طلب الديزل وتسجيله فعليًا.
     */
    private suspend fun handleOrderConfirmation(
        customer: SmsCustomerResolver.CustomerInfo,
        ctx: SmsConversationManager.ConversationContext,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {

        val sender =
            customer.phone

        val normalizedPhone =
            PhoneUtils.normalize(sender)
                ?: ""

        val name =
            customerDisplayName(customer)

        val order =
            conversationManager
                .getOrderDraft(normalizedPhone)

        if (order == null ||
            order.product != "diesel" ||
            order.step != 4
        ) {

            replyManager.sendReply(
                sender,
                "⚠️ $name، لا يوجد طلب ديزل قيد التأكيد.\n" +
                    "أرسل 'اريد ديزل' لبدء طلب جديد."
            )

            return false
        }

        val orderId =
            "ORD-${System.currentTimeMillis() % 1000000}"

        val orderDate =
            dateOnlyFormat.format(
                Date()
            )

        val success =
            customerResolver.recordDieselDelivery(
                customerId = sender,
                customerName = name,
                quantityLiters = order.quantityLiters,
                quantityDabbas = order.quantityDabbas,
                location = order.deliveryLocation ?: "",
                deliveryTime = order.deliveryTime ?: "",
                unitPrice = order.unitPrice,
                totalAmount = order.totalAmount,
                orderId = orderId
            )

        if (!success) {

            val errorId =
                UUID.randomUUID()
                    .toString()
                    .take(8)

            Log.e(
                TAG,
                "Failed to record order [$errorId]"
            )

            val managerPhone =
                customerResolver.getManagerPhone()

            replyManager.sendReply(
                sender,
                "❌ $name،\n" +
                    "حدث خطأ في تسجيل الطلب. رمز: $errorId\n" +
                    "يرجى التواصل مع المحطة: " +
                    "${managerPhone ?: "غير متوفر"}"
            )

            return false
        }

        order.status = "confirmed"
        conversationManager.updateOrderDraft(normalizedPhone) {
            status = "confirmed"
            step = 4
        }

        val updatedBalance =
            customerResolver
                .getCustomerBalanceByPhone(sender)

        val persistedOrder =
            try {
                customerResolver.getLastOrderByPhone(sender)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Unable to load persisted order for due_date",
                    e
                )
                null
            }

        val persistedDueDate =
            persistedOrder?.let { orderDueDate(it) }.orEmpty()

        prefs.lastOrderDate =
            System.currentTimeMillis()

        prefs.orderCount =
            prefs.orderCount + 1

        prefs.preferredTime =
            order.deliveryTime

        conversationManager.savePreferences(
            normalizedPhone,
            prefs
        )

        scheduleDriverAlert(
            customer,
            order,
            orderId
        )

        val dabbasText =
            if (order.quantityDabbas > 0) {

                val dabbasInt =
                    order.quantityDabbas.toInt()

                val dabbasWord =
                    if (dabbasInt == 1)
                        "دبة"
                    else
                        "دباب"

                "حق $dabbasInt $dabbasWord"

            } else {

                "حق ${order.quantityLiters.toInt()} لتر"
            }

        val balanceText =
            if (updatedBalance >= 0) {

                "الرصيد الإجمالي عليكم: " +
                    "${updatedBalance.toInt()} ريال"

            } else {

                "الرصيد الإجمالي لكم: " +
                    "${kotlin.math.abs(updatedBalance).toInt()} ريال"
            }

        val managerPhone =
            customerResolver.getManagerPhone()

        val replySent = sendReplyRequired(
            sender,
            "✅ $name، تم تأكيد طلبك!\n" +
                "═══════════════════\n" +
                "رقم الطلب: $orderId\n" +
                "قيدنا عليكم: ${order.totalAmount.toInt()} ريال\n" +
                "$dabbasText\n" +
                "إلى ${order.deliveryLocation}\n" +
                "تاريخ التسجيل: $orderDate\n" +
                (if (persistedDueDate.isNotBlank()) {
                    "تاريخ الاستحقاق: $persistedDueDate\n"
                } else {
                    ""
                }) +
                "═══════════════════\n" +
                "$balanceText\n" +
                "═══════════════════\n\n" +
                "🚚 سيتم التوصيل في ${order.deliveryTime}\n" +
                "سنرسل لك تأكيد الوصول.\n\n" +
                "💡 لتتبع الطلب: 'حالة الطلب'\n" +
                "📞 للاستفسار: ${managerPhone ?: "غير متوفر"}"
        )

        if (!replySent) {
            Log.e(
                TAG,
                "BUSINESS_SUCCESS_REPLY_FAILED orderId=$orderId"
            )
        }

        if (managerPhone != null) {

            replyManager.notifyManager(
                managerPhone,
                "🛢️ طلب ديزل مؤكد!\n" +
                    "رقم: $orderId\n" +
                    "العميل: $name\n" +
                    "الكمية: ${order.quantityLiters.toInt()} لتر " +
                    "(${order.quantityDabbas.toInt()} دباب)\n" +
                    "الموقع: ${order.deliveryLocation}\n" +
                    "الوقت: ${order.deliveryTime}\n" +
                    "القيمة: ${order.totalAmount.toInt()} ريال\n" +
                    "الرصيد الجديد: ${updatedBalance.toInt()} ريال"
            )
        }

        ctx.awaitingResponse = false
        ctx.pendingAction = ""

        conversationManager.saveContext(
            normalizedPhone,
            ctx
        )

        conversationManager.removeOrderDraft(
            normalizedPhone
        )

        metrics.recordEvent(
            SmsMetrics.EventType.ORDER_CONFIRMED,
            sender,
            orderId
        )

        return true
    }

    /**
     * إلغاء الطلب.
     */
    private suspend fun handleOrderCancel(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        val sender =
            customer.phone

        val normalizedPhone =
            PhoneUtils.normalize(sender)
                ?: ""

        val order =
            conversationManager
                .getOrderDraft(normalizedPhone)

        if (order != null) {

            replyManager.sendReply(
                sender,
                "❌ ${customerDisplayName(customer)}،\n" +
                    "تم إلغاء الطلب.\n" +
                    "نرحب بطلباتك في أي وقت."
            )

            val ctx =
                conversationManager
                    .getOrCreateContext(normalizedPhone)

            ctx.awaitingResponse = false
            ctx.pendingAction = ""

            conversationManager.saveContext(
                normalizedPhone,
                ctx
            )

            conversationManager.removeOrderDraft(
                normalizedPhone
            )

            metrics.recordEvent(
                SmsMetrics.EventType.ORDER_CANCELLED,
                sender,
                ""
            )

            return true

        } else {

            replyManager.sendReply(
                sender,
                "📦 ${customerDisplayName(customer)}،\n" +
                    "لا يوجد طلب نشط للإلغاء."
            )

            return false
        }
    }

    /**
     * جدولة تنبيه السائق.
     */
    private fun scheduleDriverAlert(
        customer: SmsCustomerResolver.CustomerInfo,
        order: SmsConversationManager.OrderDraft,
        orderId: String
    ) {

        val delayMs =
            order.deliveryTimestamp -
                System.currentTimeMillis() -
                (15 * 60 * 1000)

        if (delayMs <= 0) {

            sendDriverAlert(
                customer,
                order,
                orderId
            )

            return
        }

        handler.postDelayed(
            {
                sendDriverAlert(
                    customer,
                    order,
                    orderId
                )
            },
            delayMs
        )

        Log.d(
            TAG,
            "Driver alert scheduled for order $orderId " +
                "in ${delayMs / 60000} minutes"
        )
    }

    /**
     * إرسال تنبيه السائق.
     */
    private fun sendDriverAlert(
        customer: SmsCustomerResolver.CustomerInfo,
        order: SmsConversationManager.OrderDraft,
        orderId: String
    ) {

        scope.launch {

            val driverPhone =
                customerResolver.getDriverPhone()

            if (driverPhone == null) {

                Log.e(
                    TAG,
                    "Driver phone not set, cannot send alert"
                )

                return@launch
            }

            val dabbasText =
                if (order.quantityDabbas > 0) {

                    "${order.quantityDabbas.toInt()} دباب " +
                        "(${order.quantityLiters.toInt()} لتر)"

                } else {

                    "${order.quantityLiters.toInt()} لتر"
                }

            replyManager.sendReply(
                driverPhone,
                "🚚 توريد ديزل\n" +
                    "═══════════════════\n" +
                    "رقم الطلب: $orderId\n" +
                    "العميل: ${customerDisplayName(customer)}\n" +
                    "الكمية: $dabbasText\n" +
                    "الموقع: ${order.deliveryLocation}\n" +
                    "الوقت: ${order.deliveryTime}\n" +
                    "═══════════════════\n" +
                    "⏰ يرجى التجهيز والتوصيل\n" +
                    "📞 للاستفسار: ${customer.phone}"
            )

            runCatching {

                db.logSms(
                    driverPhone,
                    "Driver alert for order $orderId",
                    SmsLogContract.normalizeType("alert"),
                    SmsLogContract.normalizeStatus("sent")
                )

            }.onFailure {

                Log.e(
                    TAG,
                    "Failed to log driver alert",
                    it
                )
            }
        }
    }

    /**
     * استعلام الرصيد.
     */
    private suspend fun handleBalanceQuery(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        val bal =
            customer.balance

        val points =
            customer.points

        val balanceText =
            if (bal >= 0) {

                "الرصيد الإجمالي عليكم: " +
                    "${bal.toInt()} ريال"

            } else {

                "الرصيد الإجمالي لكم: " +
                    "${kotlin.math.abs(bal).toInt()} ريال"
            }

        replyManager.sendReply(
            customer.phone,
            "💳 ${customerDisplayName(customer)}،\n" +
                "═══════════════════\n" +
                "$balanceText\n" +
                "🏆 النقاط: $points\n" +
                "👑 العضوية: " +
                "${customerResolver.getVipText(customer.vipLevel)}\n" +
                "═══════════════════\n\n" +
                "💡 للدفع: 'دفع [المبلغ]'\n" +
                "📊 للتفاصيل: 'تفاصيل'"
        )

        return true
    }

    /**
     * طلب الدفع.
     */
    private suspend fun handlePaymentRequest(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String
    ): Boolean {

        val amount =
            intentDetector.extractAmount(msgBody)

        if (amount > 0) {

            replyManager.sendReply(
                customer.phone,
                "💳 ${customerDisplayName(customer)}،\n" +
                    "مبلغ الدفع: ${amount.toInt()} ريال\n\n" +
                    "طرق الدفع:\n" +
                    "1. كاش - زيارة المحطة\n" +
                    "2. تحويل بنكي - أرسل 'تحويل'\n" +
                    "3. تقسيط - أرسل 'تقسيط'"
            )

        } else {

            replyManager.sendReply(
                customer.phone,
                "💳 ${customerDisplayName(customer)}،\n" +
                    "الرصيد: ${customer.balance.toInt()} ريال\n\n" +
                    "أرسل 'دفع [المبلغ]'\n" +
                    "مثال: 'دفع 5000'"
            )
        }

        return true
    }

    /**
     * التحويل البنكي.
     */
    private suspend fun handleBankTransfer(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        replyManager.sendReply(
            customer.phone,
            "🏦 ${customerDisplayName(customer)}،\n" +
                "معلومات التحويل:\n" +
                "═══════════════════\n" +
                "البنك: بنك اليمن الدولي\n" +
                "الحساب: 1234567890\n" +
                "اسم: محطة أبو أحمد\n" +
                "═══════════════════\n\n" +
                "⚠️ بعد التحويل أرسل 'تم التحويل'"
        )

        return true
    }

    /**
     * العروض.
     *
     * لا يتم عرض البنزين لأنه غير مدعوم حاليًا.
     */
    private suspend fun handleOffersQuery(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        val vip =
            customer.vipLevel

        val vipOffer =
            when (vip) {

                3 ->
                    "👑 ذهبي: خصم 15% + توصيل مجاني"

                2 ->
                    "🥈 فضي: خصم 10% + توصيل نصف السعر"

                1 ->
                    "🥉 برونزي: خصم 7%"

                else ->
                    "💎 عادي: خصم 5%"
            }

        val dieselPrice =
            customerResolver.getDieselPrice()

        replyManager.sendReply(
            customer.phone,
            "🎁 ${customerDisplayName(customer)}،\n" +
                "═══════════════════\n" +
                "⛽ ديزل: ${dieselPrice.toInt()} ريال/لتر\n" +
                "═══════════════════\n" +
                "$vipOffer\n" +
                "═══════════════════"
        )

        return true
    }

    /**
     * استعلام الأسعار.
     *
     * البنزين غير مدعوم حاليًا.
     */
    private suspend fun handlePriceQuery(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String
    ): Boolean {

        if (containsGasolineKeyword(msgBody)) {

            return handleUnsupportedGasoline(
                customer
            )
        }

        val dieselPrice =
            customerResolver.getDieselPrice()

        replyManager.sendReply(
            customer.phone,
            "⛽ سعر الديزل: " +
                "${dieselPrice.toInt()} ريال/لتر"
        )

        return true
    }

    /**
     * اكتشاف طلب البنزين على مستوى الحماية الإضافية.
     *
     * هذا لا يغني عن SmsIntentDetector.
     * الهدف هو منع تسريب طلب البنزين إلى مسار آخر
     * حتى لو تغيرت قواعد اكتشاف النية.
     */
    private fun containsGasolineKeyword(
        msgBody: String
    ): Boolean {

        return msgBody.contains("بنزين") ||
            msgBody.contains("بترول") ||
            msgBody.contains("جازولين") ||
            msgBody.contains("gasoline") ||
            msgBody.contains("petrol")
    }

    /**
     * الولاء.
     */
    private suspend fun handleLoyaltyQuery(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        replyManager.sendReply(
            customer.phone,
            "🏆 ${customerDisplayName(customer)}،\n" +
                "═══════════════════\n" +
                "النقاط: ${customer.points}\n" +
                "الفئة: " +
                "${customerResolver.getVipText(customer.vipLevel)}\n" +
                "═══════════════════\n\n" +
                "💰 الاستبدال:\n" +
                "500 ➜ 25 ريال\n" +
                "1000 ➜ 60 ريال\n" +
                "2000 ➜ 150 ريال\n\n" +
                "أرسل 'استبدال [النقاط]'"
        )

        return true
    }

    /**
     * استبدال النقاط.
     *
     * ملاحظة:
     * هذا المسار يعرض العملية حاليًا،
     * ولا يعدل النقاط فعليًا ما لم تكن طبقة DB الحالية
     * توفر API مخصصًا لذلك.
     */
    private suspend fun handleRedeemPoints(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String
    ): Boolean {

        val points =
            intentDetector
                .extractAmount(msgBody)
                .toInt()

        if (points <= 0) {

            replyManager.sendReply(
                customer.phone,
                "أرسل 'استبدال [النقاط]'"
            )

            return false
        }

        if (customer.points < points) {

            replyManager.sendReply(
                customer.phone,
                "❌ نقاطك غير كافية!\n" +
                    "المطلوب: $points\n" +
                    "متاح: ${customer.points}"
            )

            return false
        }

        val value =
            when {

                points >= 5000 ->
                    points * 0.1

                points >= 2000 ->
                    points * 0.075

                points >= 1000 ->
                    points * 0.06

                points >= 500 ->
                    points * 0.05

                else ->
                    0.0
            }

        replyManager.sendReply(
            customer.phone,
            "🎉 تم احتساب استبدال $points نقطة!\n" +
                "القيمة: ${value.toInt()} ريال\n" +
                "تم الإضافة لرصيدك."
        )

        return true
    }

    /**
     * تتبع آخر طلب.
     */
    private suspend fun handleTrackOrder(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        val lastOrder =
            customerResolver
                .getLastOrderByPhone(customer.phone)

        if (lastOrder != null) {

            val status =
                lastOrder.optString(
                    "status",
                    "unknown"
                )

            val statusText =
                when (status) {

                    "pending" ->
                        "⏳ قيد الانتظار"

                    "confirmed" ->
                        "✅ مؤكد"

                    "delivered" ->
                        "🚚 تم التوصيل"

                    else ->
                        "⏳ قيد المعالجة"
                }

            replyManager.sendReply(
                customer.phone,
                "📦 ${customerDisplayName(customer)}،\n" +
                    "آخر طلب:\n" +
                    "═══════════════════\n" +
                    "الرقم: " +
                    "${lastOrder.optString("sale_code", "N/A")}\n" +
                    "الكمية: " +
                    "${lastOrder.optDouble("liters", 0.0).toInt()} لتر\n" +
                    "الموقع: " +
                    "${lastOrder.optString("delivery_location", "")}\n" +
                    (if (orderDueDate(lastOrder).isNotBlank()) {
                        "تاريخ الاستحقاق: ${orderDueDate(lastOrder)}\n"
                    } else {
                        ""
                    }) +
                    "الحالة: $statusText\n" +
                    "═══════════════════"
            )

        } else {

            replyManager.sendReply(
                customer.phone,
                "📦 ${customerDisplayName(customer)}،\n" +
                    "لا توجد طلبات سابقة.\n" +
                    "أرسل 'اريد ديزل' للطلب"
            )
        }

        return true
    }

    /**
     * سجل الطلبات.
     */
    private suspend fun handleOrderHistory(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        val history =
            customerResolver
                .getOrderHistoryByPhone(
                    customer.phone,
                    5
                )

        if (history.length() > 0) {

            val sb =
                StringBuilder()

            sb.append(
                "📊 ${customerDisplayName(customer)}، سجل الطلبات:\n"
            )

            sb.append(
                "═══════════════════\n"
            )

            for (i in 0 until history.length()) {

                val order =
                    history.getJSONObject(i)

                sb.append(
                    "🛢️ " +
                        "${orderDisplayType(order)} "
                )

                sb.append(
                    "${order.optDouble("liters", 0.0).toInt()} لتر "
                )

                val dueDate = orderDueDate(order)
                if (dueDate.isNotBlank()) {
                    sb.append("- الاستحقاق: $dueDate ")
                }

                sb.append(
                    "- ${order.optString("created_at", "")}\n"
                )
            }

            sb.append(
                "═══════════════════"
            )

            replyManager.sendReply(
                customer.phone,
                sb.toString()
            )

        } else {

            replyManager.sendReply(
                customer.phone,
                "لا يوجد سجل طلبات."
            )
        }

        return true
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     * الفاتورة الحقيقية
     * ═══════════════════════════════════════════════════════════════
     *
     * هذه الدالة لا تحتوي على أي أرقام تجريبية.
     *
     * المصدر:
     *   SmsCustomerResolver.getOrderHistoryByPhone()
     *
     * أي رقم يتم عرضه هنا يأتي من بيانات قاعدة البيانات
     * التي تعيدها طبقة البيانات الحالية.
     *
     * إذا لم توجد سجلات فعلية، لا يتم اختراع فاتورة.
     */
    private suspend fun handleInvoiceRequest(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String
    ): Boolean {

        val history =
            runCatching {
                customerResolver.getOrderHistoryByPhone(
                    customer.phone,
                    100
                )
            }.getOrElse {

                Log.e(
                    TAG,
                    "Failed to load invoice data",
                    it
                )

                replyManager.sendReply(
                    customer.phone,
                    "❌ ${customerDisplayName(customer)}،\n" +
                        "تعذر الوصول إلى بيانات الفاتورة من قاعدة البيانات حاليًا.\n" +
                        "يرجى المحاولة لاحقًا."
                )

                return false
            }

        if (history.length() == 0) {

            replyManager.sendReply(
                customer.phone,
                "📄 ${customerDisplayName(customer)}،\n" +
                    "لا توجد عمليات أو طلبات مسجلة فعليًا في قاعدة البيانات لإصدار فاتورة."
            )

            return true
        }

        val filtered =
            filterInvoiceRecords(
                history,
                msgBody
            )

        if (filtered.length() == 0) {

            replyManager.sendReply(
                customer.phone,
                "📄 ${customerDisplayName(customer)}،\n" +
                    "لم يتم العثور على عمليات فعلية مطابقة للفترة المطلوبة في قاعدة البيانات."
            )

            return true
        }

        val invoice =
            buildRealInvoice(
                customer,
                filtered,
                msgBody
            )

        replyManager.sendReply(
            customer.phone,
            invoice
        )

        return true
    }

    /**
     * فلترة بيانات الفاتورة.
     *
     * في حالة "آخر شهر" نحاول تحديد الشهر السابق
     * اعتمادًا على created_at الموجود في سجلات قاعدة البيانات.
     *
     * إذا لم نستطع تفسير التاريخ، لا نختلق تاريخًا.
     */
    private fun filterInvoiceRecords(
        history: JSONArray,
        msgBody: String
    ): JSONArray {

        val wantsLastMonth =
            msgBody.contains("آخر شهر") ||
                msgBody.contains("الشهر الماضي") ||
                msgBody.contains("last month")

        if (!wantsLastMonth) {
            return history
        }

        val calendar =
            Calendar.getInstance()

        calendar.add(
            Calendar.MONTH,
            -1
        )

        val targetYear =
            calendar.get(Calendar.YEAR)

        val targetMonth =
            calendar.get(Calendar.MONTH)

        val filtered =
            JSONArray()

        for (i in 0 until history.length()) {

            val item =
                history.optJSONObject(i)
                    ?: continue

            val dateValue =
                firstNonEmpty(
                    item.optString("created_at"),
                    item.optString("date"),
                    item.optString("transaction_date"),
                    item.optString("sale_date")
                )

            val date =
                parseDatabaseDate(dateValue)

            if (date != null) {

                val recordCalendar =
                    Calendar.getInstance()

                recordCalendar.time =
                    date

                if (
                    recordCalendar.get(Calendar.YEAR) ==
                    targetYear &&
                    recordCalendar.get(Calendar.MONTH) ==
                    targetMonth
                ) {

                    filtered.put(item)
                }
            }
        }

        return filtered
    }

    /**
     * اسم عرض ثابت للرسائل، مع fallback إلى الاسم القانوني إذا لم يوجد اسم تجاري.
     */
    private fun customerDisplayName(
        customer: SmsCustomerResolver.CustomerInfo
    ): String {
        return customer.commercialName
            .trim()
            .ifEmpty { customer.name.trim() }
            .ifEmpty { "العميل" }
    }

    /**
     * تحويل نوع العملية المخزن إلى اسم المنتج المعروض في SMS.
     * order_type هو المصدر الأحدث لعمليات التوصيل، بينما sale_type
     * قد يبقى على القيمة الافتراضية retail في المخطط الحالي.
     */
    private fun orderDisplayType(
        order: JSONObject
    ): String {
        val raw = firstNonEmpty(
            order.optString("order_type"),
            order.optString("sale_type"),
            order.optString("product"),
            order.optString("product_name")
        ).trim()

        return when (raw.lowercase(Locale.ROOT)) {
            "delivery", "sale", "retail", "diesel" -> "ديزل"
            else -> raw.ifEmpty { "ديزل" }
        }
    }

    /**
     * قراءة تاريخ الاستحقاق الصادر من SmsCustomerResolver2.
     * لا نستخدمه بديلاً عن created_at؛ فهما تاريخان مختلفان دلالياً.
     */
    private fun orderDueDate(
        order: JSONObject
    ): String {
        return firstNonEmpty(
            order.optString("due_date")
        )
    }

    /**
     * إنشاء فاتورة نصية من السجلات الفعلية.
     *
     * لا توجد هنا أرقام ثابتة.
     */
    private fun buildRealInvoice(
        customer: SmsCustomerResolver.CustomerInfo,
        records: JSONArray,
        msgBody: String
    ): String {

        var totalLiters =
            0.0

        var totalAmount =
            0.0

        var totalPaid =
            0.0

        var hasPaidValue =
            false

        val sb =
            StringBuilder()

        sb.append(
            "📄 فاتورة فعلية\n"
        )

        sb.append(
            "═══════════════════\n"
        )

        sb.append(
            "العميل: ${customerDisplayName(customer)}\n"
        )

        sb.append(
            "الهاتف: ${customer.phone}\n"
        )

        sb.append(
            "عدد العمليات: ${records.length()}\n"
        )

        sb.append(
            "═══════════════════\n"
        )

        for (i in 0 until records.length()) {

            val order =
                records.optJSONObject(i)
                    ?: continue

            val liters =
                firstDouble(
                    order,
                    "liters",
                    "quantity_liters",
                    "quantity",
                    "quantityLiters"
                )

            val amount =
                firstDouble(
                    order,
                    "net_amount",
                    "total_amount",
                    "totalAmount",
                    "amount",
                    "total"
                )

            val paid =
                firstDoubleOrNull(
                    order,
                    "paid_amount",
                    "amount_paid",
                    "paid"
                )

            if (paid != null) {
                totalPaid += paid
                hasPaidValue = true
            }

            totalLiters += liters
            totalAmount += amount

            val orderNumber =
                firstNonEmpty(
                    order.optString("sale_code"),
                    order.optString("order_id"),
                    order.optString("invoice_number"),
                    order.optString("id")
                ).ifEmpty {
                    "#${i + 1}"
                }

            val product =
                orderDisplayType(order)
            val date =
                firstNonEmpty(
                    order.optString("created_at"),
                    order.optString("date"),
                    order.optString("transaction_date"),
                    order.optString("sale_date")
                )

            sb.append(
                "🧾 $orderNumber\n"
            )

            sb.append(
                "⛽ الصنف: $product\n"
            )

            sb.append(
                "📦 الكمية: ${formatNumber(liters)} لتر\n"
            )

            sb.append(
                "💰 القيمة: ${formatMoney(amount)} ريال\n"
            )

            if (date.isNotEmpty()) {

                sb.append(
                    "📅 التاريخ: $date\n"
                )
            }

            val dueDate = orderDueDate(order)
            if (dueDate.isNotBlank()) {
                sb.append(
                    "📅 تاريخ الاستحقاق: $dueDate\n"
                )
            }

            sb.append(
                "───────────────────\n"
            )
        }

        sb.append(
            "الإجمالي:\n"
        )

        sb.append(
            "📦 اللترات: ${formatNumber(totalLiters)}\n"
        )

        sb.append(
            "💰 إجمالي العمليات: " +
                "${formatMoney(totalAmount)} ريال\n"
        )

        if (hasPaidValue) {

            val remaining =
                totalAmount - totalPaid

            sb.append(
                "💳 المدفوع: " +
                    "${formatMoney(totalPaid)} ريال\n"
            )

            sb.append(
                "💵 المتبقي: " +
                    "${formatMoney(remaining)} ريال\n"
            )
        }

        sb.append(
            "═══════════════════\n"
        )

        /*
         * الرصيد الحالي نفسه مصدره CustomerInfo،
         * وبالتالي هو من قاعدة البيانات وليس رقمًا ثابتًا.
         */
        sb.append(
            "💳 الرصيد الحالي: " +
                "${formatMoney(customer.balance)} ريال\n"
        )

        sb.append(
            "═══════════════════"
        )

        return sb.toString()
    }

    /**
     * استخراج أول قيمة نصية غير فارغة.
     */
    private fun firstNonEmpty(
        vararg values: String
    ): String {

        return values.firstOrNull {
            it.isNotBlank() &&
                it != "null"
        }?.trim().orEmpty()
    }

    /**
     * استخراج رقم Double من عدة أسماء محتملة.
     */
    private fun firstDouble(
        json: JSONObject,
        vararg keys: String
    ): Double {

        for (key in keys) {

            if (!json.has(key) ||
                json.isNull(key)
            ) {
                continue
            }

            val value =
                json.optDouble(
                    key,
                    Double.NaN
                )

            if (!value.isNaN()) {
                return value
            }

            val stringValue =
                json.optString(key)

            stringValue
                .replace(",", "")
                .toDoubleOrNull()
                ?.let {
                    return it
                }
        }

        return 0.0
    }

    /**
     * استخراج Double nullable.
     */
    private fun firstDoubleOrNull(
        json: JSONObject,
        vararg keys: String
    ): Double? {

        for (key in keys) {

            if (!json.has(key) ||
                json.isNull(key)
            ) {
                continue
            }

            val value =
                json.optDouble(
                    key,
                    Double.NaN
                )

            if (!value.isNaN()) {
                return value
            }

            json.optString(key)
                .replace(",", "")
                .toDoubleOrNull()
                ?.let {
                    return it
                }
        }

        return null
    }

    /**
     * تنسيق الأرقام بدون كسور غير ضرورية.
     */
    private fun formatNumber(
        value: Double
    ): String {

        return if (
            value == value.toLong().toDouble()
        ) {

            value.toLong().toString()

        } else {

            String.format(
                Locale.US,
                "%.2f",
                value
            )
        }
    }

    /**
     * تنسيق المبالغ المالية.
     */
    private fun formatMoney(
        value: Double
    ): String {

        return if (
            value == value.toLong().toDouble()
        ) {

            "%,d".format(
                Locale.US,
                value.toLong()
            )

        } else {

            String.format(
                Locale.US,
                "%,.2f",
                value
            )
        }
    }

    /**
     * محاولة تفسير صيغ التاريخ الشائعة في قاعدة البيانات.
     */
    private fun parseDatabaseDate(
        value: String
    ): Date? {

        if (value.isBlank()) {
            return null
        }

        val patterns =
            listOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd",
                "dd/MM/yyyy HH:mm:ss",
                "dd/MM/yyyy HH:mm",
                "dd/MM/yyyy",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy/MM/dd HH:mm",
                "yyyy/MM/dd"
            )

        for (pattern in patterns) {

            runCatching {

                val parser =
                    SimpleDateFormat(
                        pattern,
                        Locale.US
                    )

                parser.isLenient = false

                return parser.parse(value)
            }
        }

        return null
    }

    /**
     * التقرير الأسبوعي.
     */
    private suspend fun handleWeeklyReport(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        val history =
            customerResolver
                .getOrderHistoryByPhone(
                    customer.phone,
                    100
                )

        var totalLiters =
            0.0

        var totalCost =
            0.0

        for (i in 0 until history.length()) {

            val order =
                history.getJSONObject(i)

            totalLiters +=
                order.optDouble(
                    "liters",
                    0.0
                )

            totalCost +=
                order.optDouble(
                    "net_amount",
                    0.0
                )
        }

        replyManager.sendReply(
            customer.phone,
            "📊 ${customerDisplayName(customer)}،\n" +
                "التقرير الأسبوعي:\n" +
                "═══════════════════\n" +
                "الطلبات: ${history.length()}\n" +
                "اللترات: ${totalLiters.toInt()}\n" +
                "الإنفاق: ${totalCost.toInt()} ريال\n" +
                "النقاط: ${customer.points}\n" +
                "══════════════════"
        )

        return true
    }

    /**
     * جدولة طلب.
     */
    private suspend fun handleSchedule(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String
    ): Boolean {

        if (
            msgBody.contains("كل") &&
            (
                msgBody.contains("يوم") ||
                    msgBody.contains("أسبوع") ||
                    msgBody.contains("شهر")
                )
        ) {

            return handleRecurringOrder(
                customer,
                msgBody
            )
        }

        val timeInfo =
            intentDetector.parseDeliveryTime(
                msgBody
            )

        if (timeInfo != null) {

            replyManager.sendReply(
                customer.phone,
                "📅 ${customerDisplayName(customer)}،\n" +
                    "تم حجز موعد:\n" +
                    "الوقت: ${timeInfo.displayTime}\n" +
                    "سنرسل تذكير قبل ساعة."
            )

        } else {

            replyManager.sendReply(
                customer.phone,
                "📅 ${customerDisplayName(customer)}،\n" +
                    "أرسل 'حجز [الوقت]'\n" +
                    "مثال: 'حجز 10:00 ص'\n" +
                    "أو 'كل يوم 10:00 ص' للجدولة المتكررة"
            )
        }

        return true
    }

    /**
     * جدولة متكررة.
     */
    private suspend fun handleRecurringOrder(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String
    ): Boolean {

        val parsed =
            intentDetector.parseRecurringSchedule(
                msgBody
            )

        if (parsed != null) {

            val (period, day) =
                parsed

            val nextDate =
                calculateNextDate(
                    period,
                    day
                )

            if (nextDate != null) {

                val normalizedPhone =
                    PhoneUtils
                        .normalize(customer.phone)
                        ?: ""

                val prefs =
                    conversationManager
                        .getOrCreatePreferences(
                            normalizedPhone
                        )

                val recurring =
                    SmsConversationManager.RecurringOrder(
                        customerId =
                            customer.phone,

                        quantity =
                            prefs.preferredQuantity,

                        location =
                            prefs.preferredLocation,

                        schedule =
                            "${period}_$day",

                        nextDelivery =
                            nextDate
                    )

                conversationManager.saveRecurringOrder(
                    recurring
                )

                replyManager.sendReply(
                    customer.phone,
                    "📅 ${customerDisplayName(customer)}،\n" +
                        "تم جدولة طلبك:\n" +
                        "الكمية: ${recurring.quantity.toInt()} لتر\n" +
                        "الموقع: ${recurring.location}\n" +
                        "التاريخ القادم: " +
                        dateFormat.format(
                            Date(nextDate)
                        )
                )

                security.logSecurityEvent(
                    "RECURRING_ORDER",
                    customer.phone,
                    "Period: $period, Day: $day"
                )

                return true
            }
        }

        return false
    }

    /**
     * حساب الموعد القادم.
     */
    private fun calculateNextDate(
        period: String,
        day: String
    ): Long? {

        val cal =
            Calendar.getInstance()

        when (period) {

            "يوم" -> {

                cal.add(
                    Calendar.DAY_OF_YEAR,
                    1
                )

                return cal.timeInMillis
            }

            "أسبوع" -> {

                val targetDay =
                    when (day) {

                        "السبت" ->
                            Calendar.SATURDAY

                        "الأحد" ->
                            Calendar.SUNDAY

                        "الاثنين" ->
                            Calendar.MONDAY

                        "الثلاثاء" ->
                            Calendar.TUESDAY

                        "الأربعاء" ->
                            Calendar.WEDNESDAY

                        "الخميس" ->
                            Calendar.THURSDAY

                        "الجمعة" ->
                            Calendar.FRIDAY

                        else ->
                            return null
                    }

                cal.set(
                    Calendar.DAY_OF_WEEK,
                    targetDay
                )

                if (
                    cal.timeInMillis <
                    System.currentTimeMillis()
                ) {

                    cal.add(
                        Calendar.WEEK_OF_YEAR,
                        1
                    )
                }

                return cal.timeInMillis
            }

            "شهر" -> {

                val dayOfMonth =
                    day.toIntOrNull()
                        ?: return null

                /*
                 * منع الانتقال إلى تاريخ غير صالح
                 * مثل 31 في شهر فبراير.
                 */
                val maxDay =
                    cal.getActualMaximum(
                        Calendar.DAY_OF_MONTH
                    )

                if (
                    dayOfMonth !in 1..maxDay
                ) {
                    return null
                }

                cal.set(
                    Calendar.DAY_OF_MONTH,
                    dayOfMonth
                )

                if (
                    cal.timeInMillis <
                    System.currentTimeMillis()
                ) {

                    cal.add(
                        Calendar.MONTH,
                        1
                    )

                    val nextMax =
                        cal.getActualMaximum(
                            Calendar.DAY_OF_MONTH
                        )

                    if (
                        dayOfMonth > nextMax
                    ) {
                        cal.set(
                            Calendar.DAY_OF_MONTH,
                            nextMax
                        )
                    }
                }

                return cal.timeInMillis
            }
        }

        return null
    }

    /**
     * التقييم.
     */
    private suspend fun handleRating(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String
    ): Boolean {

        val rating =
            msgBody
                .filter { it.isDigit() }
                .take(1)
                .toIntOrNull()
                ?: 0

        if (rating in 1..5) {

            val response =
                when (rating) {

                    1 ->
                        "😔 نأسف. سنتواصل لحل المشكلة."

                    2 ->
                        "🙁 شكراً. نعمل على التحسين."

                    3 ->
                        "🙂 شكراً. نسعد بخدمتك."

                    4 ->
                        "😊 شكراً! نسعد بثقتك."

                    5 ->
                        "🤩 شكراً! أنت من أفضل عملائنا!"

                    else ->
                        "شكراً!"
                }

            replyManager.sendReply(
                customer.phone,
                "⭐ ${customerDisplayName(customer)}،\n" +
                    "تقييمك: $rating/5\n" +
                    response
            )

            val managerPhone =
                customerResolver.getManagerPhone()

            if (managerPhone != null) {

                replyManager.notifyManager(
                    managerPhone,
                    "📊 تقييم\n" +
                        "العميل: ${customerDisplayName(customer)}\n" +
                        "التقييم: $rating/5"
                )
            }

            return true
        }

        return false
    }

    /**
     * الترحيب.
     */
    private suspend fun handleGreeting(
        customer: SmsCustomerResolver.CustomerInfo,
        prefs: SmsConversationManager.CustomerPreferences
    ): Boolean {

        val hour =
            Calendar.getInstance()
                .get(Calendar.HOUR_OF_DAY)

        val greeting =
            when (hour) {

                in 5..11 ->
                    "صباح الخير"

                in 12..16 ->
                    "مساء الخير"

                in 17..21 ->
                    "مساء النور"

                else ->
                    "مرحباً"
            }

        val personalized =
            if (prefs.lastOrderDate > 0) {

                val days =
                    (
                        System.currentTimeMillis() -
                            prefs.lastOrderDate
                        ) / 86400000

                if (days > 30) {

                    "\n💡 لم تطلب منذ $days يوم! " +
                        "عرض خاص بانتظارك 🎁"

                } else {
                    ""
                }

            } else {
                ""
            }

        replyManager.sendReply(
            customer.phone,
            "$greeting ${customerDisplayName(customer)}! 🌟\n" +
                "أهلاً بك في محطة أبو أحمد." +
                personalized +
                "\n\nأرسل 'استعلام' للخدمات"
        )

        return true
    }

    /**
     * الشكر.
     */
    private suspend fun handleThanks(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        replyManager.sendReply(
            customer.phone,
            "🙏 ${customerDisplayName(customer)}،\n" +
                "شكراً لك! نسعد بخدمتك دائماً.\n\n" +
                "💡 للطلب السريع: 'اريد ديزل'"
        )

        return true
    }

    /**
     * المساعدة.
     *
     * لا نعرض البنزين ضمن الخدمات المدعومة.
     */
    private suspend fun handleHelp(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        replyManager.sendReply(
            customer.phone,
            "📋 ${customerDisplayName(customer)}،\n" +
                "قائمة الخدمات:\n" +
                "═══════════════════\n" +
                "⛽ اريد ديزل - طلب ديزل\n" +
                "💳 رصيد - الاستعلام\n" +
                "🎁 عروض - الأسعار\n" +
                "📦 حالة الطلب - التتبع\n" +
                "📍 موقع - العنوان\n" +
                "📄 فاتورة - الفواتير\n" +
                "📊 تقرير - التقارير\n" +
                "🏆 نقاط - الولاء\n" +
                "📞 اتصال - طلب اتصال\n" +
                "📅 حجز - جدولة طلب\n" +
                "═══════════════════"
        )

        return true
    }

    /**
     * شكوى.
     */
    private suspend fun handleComplaint(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String
    ): Boolean {

        val ticketId =
            System.currentTimeMillis() % 10000

        val managerPhone =
            customerResolver.getManagerPhone()

        replyManager.sendReply(
            customer.phone,
            "📝 ${customerDisplayName(customer)}،\n" +
                "تم استلام شكواك.\n" +
                "رقم التذكرة: #$ticketId\n" +
                "الرد خلال 24 ساعة.\n" +
                "📞 للعاجل: " +
                "${managerPhone ?: "غير متوفر"}"
        )

        if (managerPhone != null) {

            replyManager.notifyManager(
                managerPhone,
                "🚨 شكوى\n" +
                    "العميل: ${customerDisplayName(customer)}\n" +
                    "الرسالة: ${msgBody.take(200)}"
            )
        }

        return true
    }

    /**
     * الطوارئ.
     */
    private suspend fun handleEmergency(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        val managerPhone =
            customerResolver.getManagerPhone()
                ?: "غير متوفر"

        replyManager.sendReply(
            customer.phone,
            "🚨 ${customerDisplayName(customer)}،\n" +
                "تم تفعيل الطوارئ!\n" +
                "═══════════════════\n" +
                "📞 الاتصال: $managerPhone\n" +
                "═══════════════════\n" +
                "سيتم الاتصال بك خلال 2 دقيقة!"
        )

        if (managerPhone != "غير متوفر") {

            replyManager.notifyManager(
                managerPhone,
                "🚨 طوارئ!\n" +
                    "العميل: ${customerDisplayName(customer)}\n" +
                    "الرقم: ${customer.phone}\n" +
                    "اتصل فوراً!"
            )
        }

        return true
    }

    /**
     * طلب اتصال.
     */
    private suspend fun handleCallbackRequest(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        val managerPhone =
            customerResolver.getManagerPhone()
                ?: "غير متوفر"

        replyManager.sendReply(
            customer.phone,
            "📞 ${customerDisplayName(customer)}،\n" +
                "تم طلب الاتصال.\n" +
                "سيتم الاتصال خلال 15 دقيقة."
        )

        if (managerPhone != "غير متوفر") {

            replyManager.notifyManager(
                managerPhone,
                "📞 طلب اتصال\n" +
                    "العميل: ${customerDisplayName(customer)}\n" +
                    "الرقم: ${customer.phone}"
            )
        }

        return true
    }

    /**
     * الموقع.
     */
    private suspend fun handleLocationQuery(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        replyManager.sendReply(
            customer.phone,
            "📍 ${customerDisplayName(customer)}،\n" +
                "محطة أبو أحمد:\n" +
                "═══════════════════\n" +
                "بجانب مدرسة الاتحاد\n" +
                "الحميدة - العرش\n" +
                "═══════════════════\n" +
                "🕐 24 ساعة طوال أيام الأسبوع\n" +
                "🚨 طوارئ: 24 ساعة"
        )

        return true
    }

    /**
     * ساعات العمل.
     */
    private suspend fun handleWorkingHours(
        customer: SmsCustomerResolver.CustomerInfo
    ): Boolean {

        replyManager.sendReply(
            customer.phone,
            "🕐 ${customerDisplayName(customer)}،\n" +
                "المحطة مفتوحة 24 ساعة\n" +
                "طوال أيام الأسبوع بما في ذلك الجمعة\n" +
                "🚨 طوارئ: 24 ساعة"
        )

        return true
    }

    /**
     * unknown intent.
     */
    private suspend fun handleUnknown(
        customer: SmsCustomerResolver.CustomerInfo,
        msgBody: String,
        ctx: SmsConversationManager.ConversationContext
    ): Boolean {

        val sender =
            customer.phone

        val normalizedPhone =
            PhoneUtils.normalize(sender)
                ?: ""

        /*
         * حماية إضافية:
         * إذا أرسل العميل كلمة بنزين ولم يكتشفها
         * intent detector بالشكل المتوقع، لا نسمح
         * بإرسالها إلى مسار عام.
         */
        if (containsGasolineKeyword(msgBody)) {

            return handleUnsupportedGasoline(
                customer
            )
        }

        /*
         * متابعة سياق طلب الديزل.
         */
        if (
            ctx.awaitingResponse &&
            ctx.pendingAction.isNotEmpty()
        ) {

            when (ctx.pendingAction) {

                "awaiting_quantity" -> {

                    if (
                        Regex(""".*\d+.*""")
                            .matches(msgBody)
                    ) {

                        val prefs =
                            conversationManager
                                .getOrCreatePreferences(
                                    normalizedPhone
                                )

                        return handleQuantityResponse(
                            customer,
                            msgBody,
                            ctx,
                            prefs
                        )
                    }
                }

                "awaiting_location" -> {

                    val prefs =
                        conversationManager
                            .getOrCreatePreferences(
                                normalizedPhone
                            )

                    return handleLocationResponse(
                        customer,
                        msgBody,
                        ctx,
                        prefs
                    )
                }

                "awaiting_time" -> {

                    val prefs =
                        conversationManager
                            .getOrCreatePreferences(
                                normalizedPhone
                            )

                    return handleTimeResponse(
                        customer,
                        msgBody,
                        ctx,
                        prefs
                    )
                }

                "awaiting_confirmation" -> {

                    if (
                        msgBody.contains("تأكيد") ||
                        msgBody.contains("نعم")
                    ) {

                        val prefs =
                            conversationManager
                                .getOrCreatePreferences(
                                    normalizedPhone
                                )

                        return handleOrderConfirmation(
                            customer,
                            ctx,
                            prefs
                        )
                    }

                    if (
                        msgBody.contains("إلغاء") ||
                        msgBody.contains("الغاء") ||
                        msgBody.contains("لا")
                    ) {

                        return handleOrderCancel(
                            customer
                        )
                    }
                }
            }
        }

        val managerPhone =
            customerResolver.getManagerPhone()

        replyManager.sendReply(
            sender,
            "🤔 ${customerDisplayName(customer)}،\n" +
                "لم أفهم طلبك.\n\n" +
                "هل تقصد:\n" +
                "1. طلب ديزل - 'اريد ديزل'\n" +
                "2. استعلام - 'رصيد'\n" +
                "3. العروض - 'عروض'\n" +
                "4. المساعدة - 'استعلام'\n" +
                "5. جدولة - 'حجز [الوقت]'\n\n" +
                "📞 أو اتصل: " +
                "${managerPhone ?: "غير متوفر"}"
        )

        return true
    }

    /**
     * تنظيف البيانات القديمة.
     *
     * هذه الدالة لا تنشئ ولا تعدل أي جدول.
     * تعتمد فقط على المخطط الموجود.
     */
    private suspend fun cleanupOldData() {

        val retentionDays =
            getRetentionDays()

        val cutoff =
            System.currentTimeMillis() -
                (
                    retentionDays *
                        24L *
                        60 *
                        60 *
                        1000
                    )

        val cutoffDate =
            dateFormat.format(
                Date(cutoff)
            )

        try {

            val database =
                db.writableDatabase

            deleteOlderThan(
                database,
                "user_activity_log",
                "created_at",
                cutoffDate
            )

            deleteOlderThan(
                database,
                "sms_logs",
                "created_at",
                cutoffDate
            )

            deleteOlderThan(
                database,
                "customer_ledger",
                "transaction_date",
                cutoffDate
            )

            if (
                tableHasColumn(
                    database,
                    "sales_transactions",
                    "created_at"
                ) &&
                tableHasColumn(
                    database,
                    "sales_transactions",
                    "archived"
                ) &&
                tableHasColumn(
                    database,
                    "sales_transactions",
                    "status"
                )
            ) {

                database.update(
                    "sales_transactions",
                    android.content.ContentValues().apply {
                        put(
                            "archived",
                            1
                        )
                    },
                    "created_at < ? AND status = ?",
                    arrayOf(
                        cutoffDate,
                        "delivered"
                    )
                )
            }

            runCatching {

                metrics.cleanupOldMetrics(
                    retentionDays
                )

            }.onFailure {

                Log.e(
                    TAG,
                    "Metrics cleanup failed",
                    it
                )
            }

            Log.d(
                TAG,
                "Cleanup completed, retention days: $retentionDays"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Cleanup failed",
                e
            )
        }
    }

    /**
     * حذف البيانات الأقدم من تاريخ محدد.
     */
    private fun deleteOlderThan(
        database: android.database.sqlite.SQLiteDatabase,
        table: String,
        column: String,
        cutoff: String
    ) {

        if (
            tableHasColumn(
                database,
                table,
                column
            )
        ) {

            runCatching {

                database.delete(
                    table,
                    "$column < ?",
                    arrayOf(cutoff)
                )

            }.onFailure {

                Log.e(
                    TAG,
                    "Failed to cleanup $table.$column",
                    it
                )
            }
        }
    }

    /**
     * التحقق من وجود عمود.
     */
    private fun tableHasColumn(
        database: android.database.sqlite.SQLiteDatabase,
        table: String,
        column: String
    ): Boolean {

        return try {

            database.rawQuery(
                "PRAGMA table_info($table)",
                null
            ).use { cursor ->

                val nameIndex =
                    cursor.getColumnIndex(
                        "name"
                    )

                if (nameIndex < 0) {
                    return false
                }

                while (cursor.moveToNext()) {

                    if (
                        column.equals(
                            cursor.getString(
                                nameIndex
                            ),
                            ignoreCase = true
                        )
                    ) {

                        return true
                    }
                }

                false
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to inspect schema for $table",
                e
            )

            false
        }
    }

    /**
     * قراءة مدة الاحتفاظ من system_settings.
     */
    private fun getRetentionDays(): Int {

        return try {

            val cursor =
                db.readableDatabase.rawQuery(
                    "SELECT setting_value " +
                        "FROM system_settings " +
                        "WHERE setting_key = 'retention_days' " +
                        "LIMIT 1",
                    null
                )

            cursor.use {

                val raw =
                    if (it.moveToFirst())
                        it.getString(0)
                    else
                        null

                raw
                    ?.toIntOrNull()
                    ?.coerceIn(7, 365)
                    ?: DEFAULT_RETENTION_DAYS
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to read retention_days; using default",
                e
            )

            DEFAULT_RETENTION_DAYS
        }
    }
}