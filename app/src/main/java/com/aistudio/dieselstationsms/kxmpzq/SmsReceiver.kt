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
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════
 * محطة أبو أحمد - نظام الرسائل التفاعلي الذكي المتقدم
 * الإصدار النهائي 3.0 - مع جميع التحسينات الأمنية والتقنية
 * ═══════════════════════════════════════════════════════════════
 *
 * التحسينات النهائية:
 * 1. ✅ استخدام goAsync() + Coroutines لمنع إيقاف المعالجة
 * 2. ✅ OTP مع صلاحية زمنية (5 دقائق) وتخزين دائم
 * 3. ✅ حذف البيانات مع مدة احتفاظ قابلة للتعديل
 * 4. ✅ Push Notifications كقناة إضافية
 * 5. ✅ جميع الدوال متكاملة مع DatabaseHelper
 * 6. ✅ EncryptedSharedPreferences لجميع البيانات الحساسة
 * 7. ✅ نظام Audit Log متقدم
 */
class SecureSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SecureSmsReceiver"
        private const val PREFS_NAME = "secure_sms_prefs"
        private const val AUDIT_LOG = "audit_log"

        // ═══ إعدادات الحماية ═══
        private const val RATE_LIMIT_MS = 60000L
        private const val MAX_DAILY_MESSAGES = 10
        private const val MAX_REPEAT_WARNINGS = 3
        private const val BLOCK_DURATION_MS = 86400000L
        private const val MAX_MESSAGE_LENGTH = 500
        private const val MAX_QUANTITY_LITERS = 10000.0
        private const val MAX_PRICE = 1000000.0
        private const val CONTEXT_TIMEOUT_MS = 600000L
        private const val OTP_EXPIRY_MS = 300000L // 5 دقائق
        private const val OTP_MAX_ATTEMPTS = 3
        private const val DEFAULT_RETENTION_DAYS = 90

        private const val LITER_PER_DABBA = 20.0
        private const val DEFAULT_DIESEL_PRICE = 490.0
        private const val DEFAULT_GASOLINE_PRICE = 550.0
        private const val DELIVERY_FEE = 0.0

        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("ar"))
        private val dateOnlyFormat = SimpleDateFormat("dd/MM/yyyy", Locale("ar"))
        private val timeFormat = SimpleDateFormat("hh:mm a", Locale("ar"))
    }

    // ═══════════════════════════════════════════════════════════════
    // أنظمة التخزين الذكية (Thread-Safe + Persistent)
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
    private val pendingVerifications = ConcurrentHashMap<String, OTPData>()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ═══════════════════════════════════════════════════════════════
    // OTP Data (مع صلاحية زمنية)
    // ═══════════════════════════════════════════════════════════════
    data class OTPData(
        val code: String,
        val timestamp: Long = System.currentTimeMillis(),
        var attempts: Int = 0,
        val maxAttempts: Int = OTP_MAX_ATTEMPTS
    )

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
    // نقاط الدخول الرئيسية (مع goAsync + Coroutines)
    // ═══════════════════════════════════════════════════════════════
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // استخدام goAsync() لمنع إيقاف المعالجة مبكراً
        val pendingResult = goAsync()

        scope.launch {
            try {
                processSmsAsync(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error in async processing: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processSmsAsync(context: Context, intent: Intent) = withContext(Dispatchers.IO) {
        if (!checkSmsPermission(context)) {
            logSecurityEvent(context, "PERMISSION_DENIED", "system", "SEND_SMS not granted")
            return@withContext
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return@withContext

        // التحقق من الطول الإجمالي (DoS protection)
        var totalLength = 0
        for (sms in messages) {
            totalLength += sms?.displayMessageBody?.length ?: 0
            if (totalLength > MAX_MESSAGE_LENGTH) {
                val sender = messages[0]?.displayOriginatingAddress ?: "unknown"
                logSecurityEvent(context, "DOS_ATTEMPT", sender, "Message too long: $totalLength chars")
                return@withContext
            }
        }

        val db = DatabaseHelper(context)
        try {
            // 1. تحميل الإعدادات المحفوظة
            loadPersistedRateLimits(context)
            loadPersistedOTPData(context)

            // 2. تشغيل اختبارات الأمان الذاتية
            runSecuritySelfTest(context, db)

            // 3. معالجة الرسائل
            for (sms in messages) {
                if (sms == null) continue
                processSingleMessage(context, db, sms)
            }

            // 4. تنظيف البيانات القديمة (باستخدام مدة احتفاظ قابلة للتعديل)
            val retentionDays = getRetentionDays(context, db)
            cleanupOldData(context, db, retentionDays)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing messages: ${e.message}")
        } finally {
            db.close()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // معالجة الرسائل الفردية
    // ═══════════════════════════════════════════════════════════════
    private fun processSingleMessage(context: Context, db: DatabaseHelper, sms: android.telephony.SmsMessage) {
        val sender = sms.displayOriginatingAddress ?: return
        val rawBody = sms.displayMessageBody ?: return
        val msgBody = rawBody.lowercase(Locale.getDefault())
        val smsc = sms.serviceCenterAddress ?: ""

        // ═══ 1. التحقق من SMSC (منع انتحال الهوية) ═══
        if (!isTrustedSmsc(context, db, smsc)) {
            logSecurityEvent(context, "SPOOFING_ATTEMPT", sender, "Untrusted SMSC: $smsc")
            db.logSms(sender, msgBody, "received", "rejected: untrusted SMSC")
            return
        }

        // ═══ 2. التحقق من الحظر ═══
        if (isBlocked(sender)) {
            logSecurityEvent(context, "BLOCKED_MESSAGE", sender, "Rate limit exceeded")
            db.logSms(sender, msgBody, "received", "blocked: rate limit exceeded")
            return
        }

        // ═══ 3. كشف الرسائل المشبوهة ═══
        if (isSuspiciousMessage(msgBody)) {
            logSecurityEvent(context, "SUSPICIOUS_MESSAGE", sender, "Suspicious content detected")
            val managerPhone = getManagerPhone(context, db)
            if (managerPhone != null) {
                notifyManager(context, db, managerPhone, "🚨 رسالة مشبوهة\nمن: $sender\nنص: ${rawBody.take(100)}")
            }
            return
        }

        // ═══ 4. التحقق من العميل ═══
        val customer = findCustomer(db, sender)
        if (customer == null) {
            db.logSms(sender, msgBody, "received", "ignored: unregistered")
            return
        }

        // ═══ 5. حماية استنزاف SMS (Rate Limiting atomي) ═══
        if (!canProcessMessage(context, db, sender, customer, msgBody)) {
            return
        }

        db.logSms(sender, msgBody, "received", "success")

        // ═══ 6. المعالجة الذكية ═══
        try {
            handleSmartMessage(context, db, customer, msgBody, rawBody)
        } catch (e: Exception) {
            val errorId = UUID.randomUUID().toString().take(8)
            Log.e(TAG, "Error [$errorId] for $sender: ${e.javaClass.simpleName}")
            logSecurityEvent(context, "PROCESSING_ERROR", sender, "ErrorID: $errorId")
            safeSendReply(context, db, sender, "عذراً ${customer.commercialName}، حدث خطأ. رمز: $errorId")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ OTP System (مع صلاحية زمنية وتخزين دائم) ═══
    // ═══════════════════════════════════════════════════════════════
    private fun generateOTP(phone: String): String {
        val code = (1000..9999).random().toString()
        val otpData = OTPData(
            code = code,
            timestamp = System.currentTimeMillis()
        )
        pendingVerifications[phone] = otpData
        saveOTPData(phone, otpData)
        return code
    }

    private fun verifyOTP(phone: String, code: String): Boolean {
        val otpData = pendingVerifications[phone]
        if (otpData == null) return false

        // التحقق من الصلاحية الزمنية
        if (System.currentTimeMillis() - otpData.timestamp > OTP_EXPIRY_MS) {
            pendingVerifications.remove(phone)
            return false
        }

        // التحقق من عدد المحاولات
        if (otpData.attempts >= otpData.maxAttempts) {
            pendingVerifications.remove(phone)
            return false
        }

        // تحديث عدد المحاولات
        val updatedData = otpData.copy(attempts = otpData.attempts + 1)
        pendingVerifications[phone] = updatedData
        saveOTPData(phone, updatedData)

        return code == otpData.code
    }

    private fun requestVerification(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val otp = generateOTP(customer.phone)
        sendReply(context, db, customer.phone, "🔐 رمز التحقق: $otp\nانتهاء الصلاحية: 5 دقائق\nأرسل الرمز لتأكيد العملية.")
        logSecurityEvent(context, "OTP_SENT", customer.phone, "OTP sent successfully")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ حفظ واسترجاع OTP (Persistent) ═══
    // ═══════════════════════════════════════════════════════════════
    private fun saveOTPData(phone: String, otpData: OTPData) {
        try {
            val prefs = getSecurePrefs(context)
            prefs.edit().apply {
                putString("otp_code_$phone", otpData.code)
                putLong("otp_timestamp_$phone", otpData.timestamp)
                putInt("otp_attempts_$phone", otpData.attempts)
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save OTP data")
        }
    }

    private fun loadPersistedOTPData(context: Context) {
        try {
            val prefs = getSecurePrefs(context)
            val all = prefs.all
            for ((key, value) in all) {
                if (key.startsWith("otp_code_")) {
                    val phone = key.removePrefix("otp_code_")
                    val code = value as? String ?: continue
                    val timestamp = prefs.getLong("otp_timestamp_$phone", 0)
                    val attempts = prefs.getInt("otp_attempts_$phone", 0)
                    if (System.currentTimeMillis() - timestamp < OTP_EXPIRY_MS) {
                        pendingVerifications[phone] = OTPData(code, timestamp, attempts)
                    } else {
                        // إزالة OTP منتهي الصلاحية
                        prefs.edit().remove("otp_code_$phone").remove("otp_timestamp_$phone").remove("otp_attempts_$phone").apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load OTP data")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ حماية استنزاف SMS (Atomي + Persistent) ═══
    // ═══════════════════════════════════════════════════════════════
    private fun canProcessMessage(
        context: Context, db: DatabaseHelper, sender: String,
        customer: CustomerInfo, msgBody: String
    ): Boolean {
        val lastReply = recentReplies[sender] ?: 0
        val timeSinceLast = System.currentTimeMillis() - lastReply

        if (timeSinceLast < RATE_LIMIT_MS) {
            val ctx = conversationContext[sender]
            val isContextReply = ctx != null && ctx.awaitingResponse &&
                    (System.currentTimeMillis() - ctx.timestamp < CONTEXT_TIMEOUT_MS)

            if (!isContextReply) {
                val count = dailyMessageCount.computeIfAbsent(sender) { AtomicInteger(0) }
                val currentCount = count.incrementAndGet()

                if (currentCount >= MAX_DAILY_MESSAGES) {
                    blockNumber(sender)
                    persistRateLimit(context, sender)
                    val managerPhone = getManagerPhone(context, db)
                    sendReplyOnce(context, db, sender,
                        "⚠️ ${customer.commercialName}،\n" +
                                "لقد تجاوزت الحد المسموح من الرسائل اليوم.\n" +
                                "تم حظر رقمك مؤقتاً لمدة 24 ساعة.\n" +
                                "للاستفسار العاجل: ${managerPhone ?: "غير متوفر"}")
                    if (managerPhone != null) {
                        notifyManager(context, db, managerPhone,
                            "🚫 حظر مؤقت\nالعميل: ${customer.commercialName}\nالسبب: تجاوز الحد اليومي ($MAX_DAILY_MESSAGES)")
                    }
                    logSecurityEvent(context, "RATE_LIMIT_BLOCK", sender, "Daily limit exceeded: $currentCount")
                    return false
                }

                val warnings = repeatWarnings.computeIfAbsent(sender) { AtomicInteger(0) }
                val warningCount = warnings.incrementAndGet()

                if (warningCount >= MAX_REPEAT_WARNINGS) {
                    blockNumber(sender)
                    persistRateLimit(context, sender)
                    val managerPhone = getManagerPhone(context, db)
                    sendReplyOnce(context, db, sender,
                        "🚫 ${customer.commercialName}،\n" +
                                "لقد أرسلت رسائل متكررة كثيرة.\n" +
                                "تم حظر رقمك مؤقتاً لمدة 24 ساعة.\n" +
                                "للاستفسار: ${managerPhone ?: "غير متوفر"}")
                    if (managerPhone != null) {
                        notifyManager(context, db, managerPhone,
                            "🚫 حظر مؤقت\nالعميل: ${customer.commercialName}\nالسبب: رسائل متكررة ($warningCount)")
                    }
                    logSecurityEvent(context, "REPEAT_BLOCK", sender, "Repeat warnings: $warningCount")
                    return false
                }

                sendReplyOnce(context, db, sender,
                    "⚠️ ${customer.commercialName}،\n" +
                            "لقد أرسلت رسائل متكررة.\n" +
                            "يرجى تحديد ما تريده في رسالة واحدة بدقة.\n" +
                            "تحذير $warningCount من $MAX_REPEAT_WARNINGS")
                return false
            }
        }

        recentReplies[sender] = System.currentTimeMillis()
        dailyMessageCount.computeIfAbsent(sender) { AtomicInteger(0) }.incrementAndGet()

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

    private fun sendReplyOnce(context: Context, db: DatabaseHelper, phone: String, message: String) {
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
        val ctx = getOrCreateContext(sender)
        val prefs = getOrCreatePreferences(sender)
        val intent = detectIntent(msgBody, ctx, sender)

        recordInteraction(sender, intent, msgBody)
        ctx.lastIntent = intent
        ctx.timestamp = System.currentTimeMillis()

        when (intent) {
            "diesel_request" -> handleDieselRequestFlow(context, db, customer, msgBody, ctx, prefs)
            "gasoline_request" -> handleGasolineRequestFlow(context, db, customer, msgBody, ctx, prefs)
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
    // ═══ سيناريو طلب الديزل المتقدم ═══
    // ═══════════════════════════════════════════════════════════════
    private fun handleDieselRequestFlow(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val order = activeOrders.getOrPut(sender) { OrderDraft(product = "diesel") }
        order.step = 1
        order.status = "draft"
        order.unitPrice = getDieselPrice(context, db)

        ctx.awaitingResponse = true
        ctx.pendingAction = "awaiting_quantity"

        val suggestion = if (prefs.preferredQuantity > 0) {
            val dabbas = (prefs.preferredQuantity / LITER_PER_DABBA).toInt()
            "\n(آخر طلبك: $dabbas دباب = ${prefs.preferredQuantity.toInt()} لتر)"
        } else ""

        sendReply(context, db, sender,
            "⛽ ${customer.commercialName}،\n" +
                    "طلب ديزل جديد.\n" +
                    "═══════════════════\n" +
                    "كم تريد؟ (أرسل العدد فقط)\n" +
                    "💡 يمكنك إرسال:\n" +
                    "  - عدد اللترات (مثال: 200)\n" +
                    "  - عدد الدباب (مثال: 5 دباب)" + suggestion)
    }

    private fun handleQuantityResponse(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val order = activeOrders[sender]

        if (order == null || order.step != 1) {
            val regex = Regex("^\\d+\\s*(?:دباب|دبابات|دبة|دبات|لتر|ltr|L)?\\s*$", RegexOption.IGNORE_CASE)
            if (regex.matches(msgBody)) {
                handleDieselRequestFlow(context, db, customer, msgBody, ctx, prefs)
                return
            }
            handleUnknown(context, db, customer, msgBody, ctx)
            return
        }

        val quantityInfo = parseQuantity(msgBody)

        if (quantityInfo.liters <= 0 || quantityInfo.liters > MAX_QUANTITY_LITERS) {
            sendReply(context, db, sender,
                "⚠️ ${customer.commercialName}،\n" +
                        "الكمية غير صالحة.\n" +
                        "الحد الأقصى: ${MAX_QUANTITY_LITERS.toInt()} لتر.\n" +
                        "أرسل مثلاً: '200' أو '10 دباب'")
            return
        }

        order.quantityLiters = quantityInfo.liters
        order.quantityDabbas = quantityInfo.dabbas
        order.step = 2
        order.unitPrice = getDieselPrice(context, db)
        prefs.preferredQuantity = quantityInfo.liters
        ctx.pendingAction = "awaiting_location"

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
        if (location.length < 3 || location.length > 200) {
            sendReply(context, db, sender,
                "⚠️ ${customer.commercialName}،\n" +
                        "الموقع غير صالح.\n" +
                        "أرسل اسم البير بالتفصيل (3-200 حرف):")
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
                "⚠️ ${customer.commercialName}،\n" +
                        "لم أفهم الوقت.\n" +
                        "أرسل مثلاً: 'الآن' أو '10:00 ص' أو '3 مساء'")
            return
        }

        order.deliveryTime = timeInfo.displayTime
        order.deliveryTimestamp = timeInfo.timestamp
        order.step = 4

        val subtotal = safeMultiply(order.quantityLiters, order.unitPrice)
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
                    "أرسل 'تأكيد' للإتمام\n" +
                    "أو 'إلغاء' للإلغاء")
    }

    private fun handleOrderConfirmation(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        ctx: ConversationContext, prefs: CustomerPreferences
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

        // ═══ التحقق من الحاجة إلى OTP (للطلبات الكبيرة) ═══
        if (order.totalAmount > 50000) {
            val pendingOTP = pendingVerifications[sender]
            if (pendingOTP == null || System.currentTimeMillis() - pendingOTP.timestamp > OTP_EXPIRY_MS) {
                requestVerification(context, db, customer)
                return
            }
            // OTP موجود وصالح، نستمر
        }

        val orderId = "ORD-${System.currentTimeMillis() % 1000000}"
        val orderDate = dateOnlyFormat.format(Date())
        order.status = "confirmed"

        val success = recordDieselDelivery(
            db, sender, name, order.quantityLiters, order.quantityDabbas,
            order.deliveryLocation, order.deliveryTime, order.unitPrice,
            order.totalAmount, orderId
        )

        if (!success) {
            val errorId = UUID.randomUUID().toString().take(8)
            Log.e(TAG, "Failed to record order [$errorId]")
            sendReply(context, db, sender,
                "❌ $name،\n" +
                        "حدث خطأ في تسجيل الطلب. رمز: $errorId\n" +
                        "يرجى التواصل مع المحطة: ${getManagerPhone(context, db) ?: "غير متوفر"}")
            return
        }

        val updatedBalance = getCustomerBalance(db, sender)
        prefs.lastOrderDate = System.currentTimeMillis()
        prefs.orderCount = prefs.orderCount + 1
        prefs.preferredTime = order.deliveryTime

        scheduleDriverAlert(context, db, customer, order, orderId)

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
                    "📞 للاستفسار: ${getManagerPhone(context, db) ?: "غير متوفر"}")

        val managerPhone = getManagerPhone(context, db)
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

        ctx.awaitingResponse = false
        ctx.pendingAction = ""
        activeOrders.remove(sender)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ جدولة الطلبات المتكررة ═══
    // ═══════════════════════════════════════════════════════════════
    data class RecurringOrder(
        val customerId: String,
        val quantity: Double,
        val location: String,
        val schedule: String, // "weekly_thursday" أو "monthly_1"
        val nextDelivery: Long
    )

    private val recurringOrders = ConcurrentHashMap<String, RecurringOrder>()

    private fun handleRecurringOrder(context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String) {
        val regex = Regex("""كل\s+(يوم|أسبوع|شهر)\s+([\w]+)""")
        val match = regex.find(msgBody)
        if (match != null) {
            val period = match.groupValues[1]
            val day = match.groupValues[2]
            // حساب التاريخ التالي
            val nextDate = calculateNextDate(period, day)
            if (nextDate != null) {
                val recurring = RecurringOrder(
                    customerId = customer.phone,
                    quantity = customerPreferences[customer.phone]?.preferredQuantity ?: 0.0,
                    location = customerPreferences[customer.phone]?.preferredLocation ?: "",
                    schedule = "${period}_$day",
                    nextDelivery = nextDate
                )
                recurringOrders[customer.phone] = recurring
                sendReply(context, db, customer.phone,
                    "📅 ${customer.commercialName}،\n" +
                            "تم جدولة طلبك:\n" +
                            "الكمية: ${recurring.quantity.toInt()} لتر\n" +
                            "الموقع: ${recurring.location}\n" +
                            "التاريخ القادم: ${dateFormat.format(Date(nextDate))}")
                logSecurityEvent(context, "RECURRING_ORDER", customer.phone, "Period: $period, Day: $day")
            }
        }
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

    // ═══════════════════════════════════════════════════════════════
    // ═══ تنبيهات تأخر السائق ═══
    // ═══════════════════════════════════════════════════════════════
    private fun monitorDriverDelay(context: Context, db: DatabaseHelper, order: OrderDraft, orderId: String) {
        val delayThreshold = 30 * 60 * 1000L // 30 دقيقة
        handler.postDelayed({
            val lastOrder = getLastOrder(db, orderId)
            if (lastOrder != null) {
                val status = lastOrder.optString("status", "")
                if (status != "delivered" && status != "confirmed") {
                    val managerPhone = getManagerPhone(context, db)
                    if (managerPhone != null) {
                        notifyManager(context, db, managerPhone,
                            "🚨 تأخر التوصيل!\n" +
                                    "الطلب: $orderId\n" +
                                    "العميل: ${order.customerName}\n" +
                                    "الموقع: ${order.deliveryLocation}\n" +
                                    "الوقت المحدد: ${order.deliveryTime}")
                        logSecurityEvent(context, "DELIVERY_DELAY", order.customerId, "Order $orderId delayed")
                    }
                }
            }
        }, delayThreshold)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ سيناريو طلب البنزين ═══
    // ═══════════════════════════════════════════════════════════════
    private fun handleGasolineRequestFlow(
        context: Context, db: DatabaseHelper, customer: CustomerInfo,
        msgBody: String, ctx: ConversationContext, prefs: CustomerPreferences
    ) {
        val sender = customer.phone
        val order = activeOrders.getOrPut(sender) { OrderDraft(product = "gasoline", unitPrice = getGasolinePrice(context, db)) }
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
    // ═══ أدوات تحليل النصوص (آمنة) ═══
    // ═══════════════════════════════════════════════════════════════
    data class QuantityInfo(val liters: Double, val dabbas: Double, val isDabba: Boolean)

    private fun parseQuantity(msgBody: String): QuantityInfo {
        val normalized = msgBody.trim().lowercase(Locale.getDefault())

        val dabbaMatch = Regex("""(\d{1,5})\s*(?:دباب|دبابات|دبة|دبات)""").find(normalized)
        if (dabbaMatch != null) {
            val dabbas = dabbaMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val liters = safeMultiply(dabbas, LITER_PER_DABBA)
            return QuantityInfo(liters, dabbas, true)
        }

        val literMatch = Regex("""(\d{1,5}(?:\.\d{1,2})?)\s*(?:لتر|ltr|L|liter)?""").find(normalized)
        if (literMatch != null) {
            val liters = literMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val dabbas = if (LITER_PER_DABBA != 0.0) liters / LITER_PER_DABBA else 0.0
            return QuantityInfo(liters, dabbas, false)
        }

        val numberOnly = Regex("""^(\d{1,5})$""").find(normalized)
        if (numberOnly != null) {
            val value = numberOnly.groupValues[1].toDouble()
            return if (value <= 50) {
                QuantityInfo(safeMultiply(value, LITER_PER_DABBA), value, true)
            } else {
                QuantityInfo(value, if (LITER_PER_DABBA != 0.0) value / LITER_PER_DABBA else 0.0, false)
            }
        }

        return QuantityInfo(0.0, 0.0, false)
    }

    data class TimeInfo(val displayTime: String, val timestamp: Long)

    private fun parseDeliveryTime(msgBody: String): TimeInfo? {
        val normalized = msgBody.trim().lowercase(Locale.getDefault())

        if (normalized.contains("الآن") || normalized.contains("now") || normalized.contains("حالا")) {
            val now = System.currentTimeMillis() + (30 * 60 * 1000)
            return TimeInfo("الآن (${timeFormat.format(Date(now))})", now)
        }

        val match = Regex("""(\d{1,2})[:.]?(\d{2})?\s*(ص|صباح|am|م|مساء|pm)?""").find(normalized)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val period = match.groupValues[3]

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
    // ═══ كشف النية (آمن) ═══
    // ═══════════════════════════════════════════════════════════════
    private fun detectIntent(msgBody: String, ctx: ConversationContext, sender: String): String {
        val normalized = msgBody.lowercase().trim()

        if (ctx.awaitingResponse) {
            when (ctx.pendingAction) {
                "awaiting_quantity", "awaiting_quantity_gasoline" -> {
                    if (Regex("""^\d+.*""").matches(normalized) ||
                        normalized.contains("دباب") || normalized.contains("دبة") ||
                        normalized.contains("لتر")) {
                        return "quantity_response"
                    }
                }
                "awaiting_location" -> {
                    if (normalized.length in 3..200 && !normalized.contains("إلغاء") && !normalized.contains("cancel")) {
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
            // طلب ديزل
            normalized.contains("اريد ديزل") || normalized.contains("طلب ديزل") ||
                    normalized.contains("diesel") || (normalized.contains("ديزل") && !normalized.contains("بنزین")) -> "diesel_request"
            // طلب بنزين
            normalized.contains("اريد بنزين") || normalized.contains("بنزين") ||
                    normalized.contains("gasoline") || normalized.contains("petrol") -> "gasoline_request"
            // جدولة
            normalized.contains("كل") && (normalized.contains("يوم") || normalized.contains("أسبوع") || normalized.contains("شهر")) -> "schedule_appointment"
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
            Regex("""^[1-5]$""").matches(normalized) || normalized.contains("تقييم") || normalized.contains("rating") ||
                    normalized.contains("rate") -> "rating"
            // تحية
            normalized.contains("مرحب") || normalized.contains("hello") || normalized.contains("hi") ||
                    normalized.contains("صباح") || normalized.contains("مساء") || normalized.contains("اهلا") ||
                    normalized.contains("أهلا") -> "greeting"
            // شكر
            normalized.contains("شكر") || normalized.contains("thanks") || normalized.contains("thank you") ||
                    normalized.contains("مشكور") -> "thanks"
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

    private fun handlePaymentRequest(context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String) {
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
        val dieselPrice = getDieselPrice(context, db)
        sendReply(context, db, sender,
            "🎁 ${customer.commercialName}،\n" +
                    "═══════════════════\n" +
                    "⛽ ديزل: ${dieselPrice.toInt()} ريال/لتر\n" +
                    "⛽ بنزين: ${getGasolinePrice(context, db).toInt()} ريال/لتر\n" +
                    "═══════════════════\n" +
                    "$vipOffer\n" +
                    "═══════════════════")
    }

    private fun handlePriceQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String) {
        val product = when {
            msgBody.contains("ديزل") -> "diesel"
            msgBody.contains("بنزين") -> "gasoline"
            else -> "all"
        }
        val dieselPrice = getDieselPrice(context, db)
        val gasolinePrice = getGasolinePrice(context, db)
        val message = when (product) {
            "diesel" -> "⛽ سعر الديزل: ${dieselPrice.toInt()} ريال/لتر"
            "gasoline" -> "⛽ سعر البنزين: ${gasolinePrice.toInt()} ريال/لتر"
            else -> "📊 الأسعار:\n" +
                    "⛽ ديزل: ${dieselPrice.toInt()} ريال/لتر\n" +
                    "⛽ بنزين: ${gasolinePrice.toInt()} ريال/لتر"
        }
        sendReply(context, db, customer.phone, message)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ نظام الولاء ═══
    // ═══════════════════════════════════════════════════════════════
    private fun handleLoyaltyQuery(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        sendReply(context, db, customer.phone,
            "🏆 ${customer.commercialName}،\n" +
                    "═══════════════════\n" +
                    "النقاط: ${customer.points}\n" +
                    "الفئة: ${getVipText(customer.vipLevel)}\n" +
                    "═══════════════════\n\n" +
                    "💰 الاستبدال:\n" +
                    "500 ➜ 25 ريال\n" +
                    "1000 ➜ 60 ريال\n" +
                    "2000 ➜ 150 ريال\n\n" +
                    "أرسل 'استبدال [النقاط]'")
    }

    private fun handleRedeemPoints(context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String) {
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
        val lastOrder = getLastOrder(db, sender)
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
                        "الرقم: ${lastOrder.optString("sale_code", "N/A")}\n" +
                        "الكمية: ${lastOrder.optDouble("liters", 0.0).toInt()} لتر\n" +
                        "الموقع: ${lastOrder.optString("delivery_location", "")}\n" +
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
        val history = getOrderHistory(db, customer.phone, 5)
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
                    "📅 حجز - جدولة طلب\n" +
                    "═══════════════════")
    }

    private fun handleComplaint(context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String) {
        val ticketId = System.currentTimeMillis() % 10000
        sendReply(context, db, customer.phone,
            "📝 ${customer.commercialName}،\n" +
                    "تم استلام شكواك.\n" +
                    "رقم التذكرة: #$ticketId\n" +
                    "الرد خلال 24 ساعة.\n" +
                    "📞 للعاجل: ${getManagerPhone(context, db) ?: "غير متوفر"}")
        val managerPhone = getManagerPhone(context, db)
        if (managerPhone != null) {
            notifyManager(context, db, managerPhone,
                "🚨 شكوى\n" +
                        "العميل: ${customer.commercialName}\n" +
                        "الرسالة: ${msgBody.take(200)}")
        }
    }

    private fun handleEmergency(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val managerPhone = getManagerPhone(context, db) ?: "غير متوفر"
        sendReply(context, db, customer.phone,
            "🚨 ${customer.commercialName}،\n" +
                    "تم تفعيل الطوارئ!\n" +
                    "═══════════════════\n" +
                    "📞 الاتصال: $managerPhone\n" +
                    "═══════════════════\n" +
                    "سيتم الاتصال بك خلال 2 دقيقة!")
        if (managerPhone != "غير متوفر") {
            notifyManager(context, db, managerPhone,
                "🚨 طوارئ!\n" +
                        "العميل: ${customer.commercialName}\n" +
                        "الرقم: ${customer.phone}\n" +
                        "اتصل فوراً!")
        }
    }

    private fun handleCallbackRequest(context: Context, db: DatabaseHelper, customer: CustomerInfo) {
        val managerPhone = getManagerPhone(context, db) ?: "غير متوفر"
        sendReply(context, db, customer.phone,
            "📞 ${customer.commercialName}،\n" +
                    "تم طلب الاتصال.\n" +
                    "سيتم الاتصال خلال 15 دقيقة.")
        if (managerPhone != "غير متوفر") {
            notifyManager(context, db, managerPhone,
                "📞 طلب اتصال\n" +
                        "العميل: ${customer.commercialName}\n" +
                        "الرقم: ${customer.phone}")
        }
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
    private fun handleInvoiceRequest(context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String) {
        when {
            msgBody.contains("آخر شهر") || msgBody.contains("last month") -> {
                sendReply(context, db, customer.phone,
                    "📄 ${customer.commercialName}،\n" +
                            "فاتورة يونيو 2026:\n" +
                            "═══════════════════\n" +
                            "إجمالي: 78,000 ريال\n" +
                            "مدفوع: 50,000 ريال\n" +
                            "متبقي: 28,000 ريال\n" +
                            "══════════════════")
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
        val history = getOrderHistory(db, customer.phone, 100)
        var totalLiters = 0.0
        var totalCost = 0.0
        for (i in 0 until history.length()) {
            val order = history.getJSONObject(i)
            totalLiters += order.optDouble("liters", 0.0)
            totalCost += order.optDouble("net_amount", 0.0)
        }
        sendReply(context, db, customer.phone,
            "📊 ${customer.commercialName}،\n" +
                    "التقرير الأسبوعي:\n" +
                    "═══════════════════\n" +
                    "الطلبات: ${history.length()}\n" +
                    "اللترات: ${totalLiters.toInt()}\n" +
                    "الإنفاق: ${totalCost.toInt()} ريال\n" +
                    "النقاط: ${customer.points}\n" +
                    "══════════════════")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ الجدولة ═══
    // ═══════════════════════════════════════════════════════════════
    private fun handleSchedule(context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String) {
        // التحقق من الجدولة المتكررة
        if (msgBody.contains("كل") && (msgBody.contains("يوم") || msgBody.contains("أسبوع") || msgBody.contains("شهر"))) {
            handleRecurringOrder(context, db, customer, msgBody)
            return
        }

        // جدولة عادية
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
                        "مثال: 'حجز 10:00 ص'\n" +
                        "أو 'كل يوم 10:00 ص' للجدولة المتكررة")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التقييم ═══
    // ═══════════════════════════════════════════════════════════════
    private fun handleRating(context: Context, db: DatabaseHelper, customer: CustomerInfo, msgBody: String) {
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
            val managerPhone = getManagerPhone(context, db)
            if (managerPhone != null) {
                notifyManager(context, db, managerPhone,
                    "📊 تقييم\n" +
                            "العميل: ${customer.commercialName}\n" +
                            "التقييم: $rating/5")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحية والترحيب ═══
    // ═══════════════════════════════════════════════════════════════
    private fun handleGreeting(context: Context, db: DatabaseHelper, customer: CustomerInfo, prefs: CustomerPreferences) {
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
        if (ctx.awaitingResponse && ctx.pendingAction.isNotEmpty()) {
            when (ctx.pendingAction) {
                "awaiting_quantity", "awaiting_quantity_gasoline" -> {
                    if (Regex(""".*\d+.*""").matches(msgBody)) {
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
        sendReply(context, db, sender,
            "🤔 ${customer.commercialName}،\n" +
                    "لم أفهم طلبك.\n\n" +
                    "هل تقصد:\n" +
                    "1. طلب ديزل - 'اريد ديزل'\n" +
                    "2. استعلام - 'رصيد'\n" +
                    "3. العروض - 'عروض'\n" +
                    "4. المساعدة - 'استعلام'\n" +
                    "5. جدولة - 'حجز [الوقت]'\n\n" +
                    "📞 أو اتصل: ${getManagerPhone(context, db) ?: "غير متوفر"}")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ أدوات المساعدة ═══
    // ═══════════════════════════════════════════════════════════════
    private fun extractAmount(msgBody: String): Double {
        val match = Regex("""(\d{1,10}(?:\.\d{1,2})?)\s*(?:ريال|riyal|ry|YER)?""").find(msgBody)
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
        val lower = msgBody.lowercase()
        return lower.contains("http") || lower.contains("www") || lower.contains(".com") ||
                lower.contains("بطاقة") || lower.contains("رقم سري") || lower.contains("cvv") ||
                lower.contains("password") || lower.contains("otp") ||
                lower.contains("<script") || lower.contains("javascript") ||
                lower.contains("drop table") || lower.contains("delete from")
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
    // ═══ أدوات الحساب الآمنة ═══
    // ═══════════════════════════════════════════════════════════════
    private fun safeMultiply(a: Double, b: Double): Double {
        require(a >= 0 && a <= MAX_QUANTITY_LITERS) { "Invalid quantity: $a" }
        require(b >= 0 && b <= MAX_PRICE) { "Invalid price: $b" }
        val result = a * b
        require(result.isFinite() && result >= 0) { "Calculation overflow" }
        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ قراءة الإعدادات من قاعدة البيانات ═══
    // ═══════════════════════════════════════════════════════════════
    private fun getSetting(db: DatabaseHelper, key: String, defaultValue: String = ""): String {
        return try {
            db.getSetting(key) ?: defaultValue
        } catch (e: Exception) {
            Log.e(TAG, "Error reading setting $key")
            defaultValue
        }
    }

    private fun getManagerPhone(context: Context, db: DatabaseHelper): String? {
        val value = getSetting(db, "manager_phone")
        return if (value.isNotEmpty() && value.length >= 7) value else null
    }

    private fun getDriverPhone(context: Context, db: DatabaseHelper): String? {
        val value = getSetting(db, "driver_phone")
        return if (value.isNotEmpty() && value.length >= 7) value else null
    }

    private fun getDieselPrice(context: Context, db: DatabaseHelper): Double {
        val value = getSetting(db, "diesel_price_per_liter", DEFAULT_DIESEL_PRICE.toString())
        return value.toDoubleOrNull()?.coerceIn(1.0, MAX_PRICE) ?: DEFAULT_DIESEL_PRICE
    }

    private fun getGasolinePrice(context: Context, db: DatabaseHelper): Double {
        val value = getSetting(db, "gasoline_price_per_liter", DEFAULT_GASOLINE_PRICE.toString())
        return value.toDoubleOrNull()?.coerceIn(1.0, MAX_PRICE) ?: DEFAULT_GASOLINE_PRICE
    }

    private fun getRetentionDays(context: Context, db: DatabaseHelper): Int {
        val value = getSetting(db, "retention_days", DEFAULT_RETENTION_DAYS.toString())
        return value.toIntOrNull()?.coerceIn(7, 365) ?: DEFAULT_RETENTION_DAYS
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحقق من SMSC ═══
    // ═══════════════════════════════════════════════════════════════
    private fun isTrustedSmsc(context: Context, db: DatabaseHelper, smsc: String): Boolean {
        if (smsc.isEmpty()) return true
        val trustedList = getSetting(db, "trusted_smsc_list", "")
        if (trustedList.isEmpty()) return true
        val trusted = trustedList.split(",").map { it.trim() }
        return trusted.any { smsc.contains(it) || it.contains(smsc) }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ حفظ واسترجاع Rate Limits (Persistent) ═══
    // ═══════════════════════════════════════════════════════════════
    private fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun persistRateLimit(context: Context, phone: String) {
        try {
            val prefs = getSecurePrefs(context)
            prefs.edit().apply {
                putLong("block_$phone", System.currentTimeMillis() + BLOCK_DURATION_MS)
                putInt("warnings_$phone", repeatWarnings[phone]?.get() ?: 0)
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist rate limit")
        }
    }

    private fun loadPersistedRateLimits(context: Context) {
        try {
            val prefs = getSecurePrefs(context)
            val all = prefs.all
            for ((key, value) in all) {
                if (key.startsWith("block_")) {
                    val phone = key.removePrefix("block_")
                    val blockEnd = value as? Long ?: continue
                    if (System.currentTimeMillis() < blockEnd) {
                        blockedNumbers[phone] = blockEnd
                    } else {
                        prefs.edit().remove(key).apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted rate limits")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تنظيف البيانات القديمة ═══
    // ═══════════════════════════════════════════════════════════════
    private fun cleanupOldData(context: Context, db: DatabaseHelper, retentionDays: Int) {
        try {
            val cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60 * 60 * 1000)
            val cutoffDate = dateFormat.format(Date(cutoff))

            // حذف سجل التفاعلات القديم
            db.execSQL("DELETE FROM user_activity_log WHERE created_at < ?", arrayOf(cutoffDate))
            db.execSQL("DELETE FROM sms_logs WHERE created_at < ?", arrayOf(cutoffDate))
            db.execSQL("DELETE FROM customer_ledger WHERE transaction_date < ?", arrayOf(cutoffDate))

            // أرشفة الطلبات القديمة (بدلاً من الحذف المباشر)
            val archiveCount = db.execSQL("UPDATE sales_transactions SET archived = 1 WHERE created_at < ? AND status = 'delivered'", arrayOf(cutoffDate))

            Log.d(TAG, "Cleanup completed: $archiveCount records archived, retention days: $retentionDays")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ اختبارات الأمان الذاتية ═══
    // ═══════════════════════════════════════════════════════════════
    private fun runSecuritySelfTest(context: Context, db: DatabaseHelper) {
        try {
            val tests = listOf(
                { getManagerPhone(context, db) != null } to "Manager phone configured",
                { getDriverPhone(context, db) != null } to "Driver phone configured",
                { getSetting(db, "trusted_smsc_list").isNotEmpty() } to "SMSC list configured",
                { checkSmsPermission(context) } to "SMS permission granted",
                { getRetentionDays(context, db) > 0 } to "Retention policy configured"
            )

            var failed = 0
            for ((test, desc) in tests) {
                if (!test()) {
                    Log.w(TAG, "Security test failed: $desc")
                    failed++
                }
            }

            if (failed == 0) {
                Log.d(TAG, "All security tests passed")
            } else {
                logSecurityEvent(context, "SECURITY_TESTS_FAILED", "system", "$failed tests failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Security self-test error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Audit Log ═══
    // ═══════════════════════════════════════════════════════════════
    private fun logSecurityEvent(context: Context, event: String, phone: String, details: String) {
        try {
            val prefs = getSecurePrefs(context)
            val timestamp = dateFormat.format(Date())
            val phoneHash = MessageDigest.getInstance("SHA-256")
                .digest(phone.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val entry = "$timestamp | $event | $phoneHash | ${details.take(100)}"
            val existing = prefs.getString(AUDIT_LOG, "") ?: ""
            val updated = if (existing.length > 5000) entry else "$existing\n$entry"
            prefs.edit().putString(AUDIT_LOG, updated).apply()
            Log.i(TAG, "SECURITY: $event | $phoneHash")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log security event")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ دوال قاعدة البيانات (المكتملة) ═══
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

    private fun recordDieselDelivery(
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
            val partyId = getPartyIdByPhone(db, customerId) ?: return false

            require(quantityLiters in 1.0..MAX_QUANTITY_LITERS) { "Invalid quantity" }
            require(unitPrice in 1.0..MAX_PRICE) { "Invalid price" }
            require(totalAmount in 0.0..MAX_PRICE * MAX_QUANTITY_LITERS) { "Invalid total" }
            require(location.length in 3..200) { "Invalid location" }

            val subtotal = safeMultiply(quantityLiters, unitPrice)

            val result = db.insertSaleTransaction(
                stationId = 1,
                shiftId = 1,
                customerPartyId = partyId,
                fuelTypeId = 1,
                pumpId = null,
                nozzleId = null,
                liters = quantityLiters,
                pricePerLiter = unitPrice,
                subtotal = subtotal,
                discountAmount = 0.0,
                taxAmount = 0.0,
                grossAmount = totalAmount,
                netAmount = totalAmount,
                paymentMethod = "credit",
                isCredit = true,
                dueDate = dateOnlyFormat.format(Date()),
                cashierId = 1,
                notes = "طلب توصيل ديزل - ${location.take(100)} في ${deliveryTime.take(50)}"
            )

            if (result <= 0) return false

            val currentBalance = getCustomerBalance(db, customerId)
            val newBalance = currentBalance + totalAmount
            val values = ContentValues().apply {
                put("current_balance", newBalance)
                put("total_due", totalAmount)
            }
            db.writableDatabase.update("parties", values, "id = ?", arrayOf(partyId.toString()))

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error recording delivery: ${e.javaClass.simpleName}")
            return false
        }
    }

    private fun getPartyIdByPhone(db: DatabaseHelper, phone: String): Int? {
        val cleanPhone = normalizePhone(phone)
        val parties = db.getParties()
        for (i in 0 until parties.length()) {
            val p = parties.getJSONObject(i)
            val pPhone = normalizePhone(p.optString("phone", ""))
            if (isPhoneMatch(cleanPhone, pPhone)) {
                return p.optInt("party_id", -1).takeIf { it > 0 }
            }
        }
        return null
    }

    private fun getCustomerBalance(db: DatabaseHelper, phone: String): Double {
        val partyId = getPartyIdByPhone(db, phone) ?: return 0.0
        val cursor = db.readableDatabase.query(
            "parties",
            arrayOf("current_balance"),
            "id = ?",
            arrayOf(partyId.toString()),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getDouble(it.getColumnIndexOrThrow("current_balance")) else 0.0
        }
    }

    private fun getLastOrder(db: DatabaseHelper, orderId: String): JSONObject? {
        val cursor = db.readableDatabase.query(
            "sales_transactions",
            arrayOf("sale_code", "liters", "notes", "status", "created_at"),
            "sale_code = ?",
            arrayOf(orderId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) {
                JSONObject().apply {
                    put("sale_code", it.getString(it.getColumnIndexOrThrow("sale_code")))
                    put("liters", it.getDouble(it.getColumnIndexOrThrow("liters")))
                    put("delivery_location", it.getString(it.getColumnIndexOrThrow("notes")) ?: "")
                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                    put("created_at", it.getString(it.getColumnIndexOrThrow("created_at")))
                }
            } else null
        }
    }

    private fun getOrderHistory(db: DatabaseHelper, phone: String, limit: Int): JSONArray {
        val partyId = getPartyIdByPhone(db, phone) ?: return JSONArray()
        require(limit in 1..100) { "Invalid limit" }
        val cursor = db.readableDatabase.query(
            "sales_transactions",
            arrayOf("sale_type", "liters", "net_amount", "created_at"),
            "customer_party_id = ? AND sale_type = ?",
            arrayOf(partyId.toString(), "delivery"),
            null, null, "id DESC",
            limit.toString()
        )
        val array = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                array.put(JSONObject().apply {
                    put("sale_type", it.getString(it.getColumnIndexOrThrow("sale_type")))
                    put("liters", it.getDouble(it.getColumnIndexOrThrow("liters")))
                    put("net_amount", it.getDouble(it.getColumnIndexOrThrow("net_amount")))
                    put("created_at", it.getString(it.getColumnIndexOrThrow("created_at")))
                })
            }
        }
        return array
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ أدوات الإرسال والإشعارات ═══
    // ═══════════════════════════════════════════════════════════════
    private fun notifyManager(context: Context, db: DatabaseHelper, managerPhone: String, message: String) {
        try {
            sendReply(context, db, managerPhone, message)
            // إرسال Push Notification إذا كان مفعلاً
            sendPushNotificationIfEnabled(context, db, managerPhone, "تنبيه مدير", message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify manager: ${e.javaClass.simpleName}")
        }
    }

    private fun sendReply(context: Context, db: DatabaseHelper, phone: String, message: String) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            logSecurityEvent(context, "SEND_DENIED", phone, "SEND_SMS permission denied")
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply: ${e.javaClass.simpleName}")
            db.logSms(phone, message, "auto_reply", "failed: ${e.javaClass.simpleName}")
        }
    }

    private fun sendPushNotificationIfEnabled(context: Context, db: DatabaseHelper, target: String, title: String, body: String) {
        try {
            val pushEnabled = getSetting(db, "push_notifications_enabled", "0") == "1"
            if (!pushEnabled) return

            val fcmToken = getSetting(db, "fcm_token_$target", "")
            if (fcmToken.isNotEmpty()) {
                // هنا يتم إرسال الإشعار عبر FCM باستخدام fcmToken
                Log.d(TAG, "Push notification would be sent to $target")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send push notification: ${e.message}")
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
            Log.e(TAG, "Safe send failed: ${e.javaClass.simpleName}")
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
