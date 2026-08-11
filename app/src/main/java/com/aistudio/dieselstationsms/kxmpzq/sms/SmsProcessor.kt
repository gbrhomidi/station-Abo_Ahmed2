package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * ═══════════════════════════════════════════════════════════════
 * معالج الرسائل - SmsProcessor
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. استخراج الأرقام والمبالغ والمواقع
 * 2. تصنيف الرسائل (طلب/استفسار/شكوى)
 * 3. تحديد نية المستخدم (Intent Detection)
 * 4. إدارة سياق المحادثة
 */
class SmsProcessor(
    private val context: Context,
    private val conversationManager: SmsConversationManager,
    private val customerResolver: SmsCustomerResolver,
    private val securityOTP: SmsSecurityOTP,
    private val gateway: SmsPartyGateway
) {

    companion object {
        private const val TAG = "SmsProcessor"
        private const val MAX_MESSAGE_LENGTH = 500
        private const val MIN_ORDER_QUANTITY = 10.0
        private const val MAX_ORDER_QUANTITY = 10000.0

        // ═══ Intent Detection Keywords ═══
        private val ORDER_KEYWORDS = listOf(
            "طلب", "أريد", "احب", "ابغى", "ابغي", "محتاج", "عايز", "حاب", "بدي",
            "اريد", "ابي", "order", "want", "need", "buy", "purchase"
        )
        private val PRICE_KEYWORDS = listOf("سعر", "كم", "بكام", "بكم", "price", "cost", "how much")
        private val AVAILABILITY_KEYWORDS = listOf("متاح", "موجود", "available", "in stock", "do you have")
        private val DELIVERY_KEYWORDS = listOf("توصيل", "وصل", "delivery", "ship", "when", "time")
        private val STATUS_KEYWORDS = listOf("حالة", "وين", "status", "where", "track", "follow")
        private val COMPLAINT_KEYWORDS = listOf("شكوى", "مشكلة", "عطل", "complaint", "issue", "problem")
        private val CONFIRM_KEYWORDS = listOf("تأكيد", "موافق", "نعم", "yes", "confirm", "ok", "okay")
        private val CANCEL_KEYWORDS = listOf("إلغاء", "الغاء", "cancel", "stop", "no")
        private val OTP_REQUEST_KEYWORDS = listOf("رمز", "تحقق", "otp", "code", "verify")
        private val OTP_VERIFY_KEYWORDS = listOf("تحقق", "verify", "check")
        private val HELP_KEYWORDS = listOf("مساعدة", "help", "commands", "menu")
        private val GREETING_KEYWORDS = listOf("مرحبا", "اهلا", "السلام", "hello", "hi", "hey")

        // ═══ Patterns ═══
        private val QUANTITY_PATTERN = Pattern.compile("(\d+[\.,]?\d*)\s*(لتر|دبة|ltr|liter|daba|gallon)")
        private val LOCATION_PATTERN = Pattern.compile("(في|على|إلى|to|at)\s+(.{3,50})")
        private val PRODUCT_PATTERN = Pattern.compile("(ديزل|بنزين|كيروسين|diesel|gasoline|kerosene)")
        private val ORDER_ID_PATTERN = Pattern.compile("(ORD-\d+|\d{4,})")
        private val COMPLAINT_TYPE_PATTERN = Pattern.compile("(جودة|تأخير|سعر|quantity|quality|delay|price)")
        private val OTP_CODE_PATTERN = Pattern.compile("\b\d{4}\b")
        private val TIME_PATTERN = Pattern.compile("(بعد|غدا|اليوم|الساعة\s*\d{1,2}(:\d{2})?)")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 1. المعالجة الرئيسية ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun processIncomingMessage(
        phone: String,
        message: String,
        timestamp: Long = System.currentTimeMillis()
    ): SmsProcessingResult = withContext(Dispatchers.Default) {
        try {
            val normalizedPhone = PhoneUtils.normalize(phone) ?: phone
            val maskedPhone = PhoneUtils.mask(normalizedPhone)

            Log.d(TAG, "Processing message from $maskedPhone: ${message.take(50)}...")

            if (message.length > MAX_MESSAGE_LENGTH) {
                return@withContext SmsProcessingResult.Error(
                    "الرسالة طويلة جداً. يرجى إرسال رسالة أقصر."
                )
            }

            val conversationContext = conversationManager.getOrCreateContext(normalizedPhone)
            val intent = detectIntent(message, conversationContext)

            conversationContext.lastIntent = intent.type
            conversationContext.timestamp = timestamp

            val result = when (intent.type) {
                "ORDER" -> handleOrderIntent(normalizedPhone, message, intent, conversationContext)
                "PRICE_INQUIRY" -> handlePriceInquiry(normalizedPhone, intent)
                "AVAILABILITY_INQUIRY" -> handleAvailabilityInquiry(normalizedPhone, intent)
                "DELIVERY_INQUIRY" -> handleDeliveryInquiry(normalizedPhone, intent)
                "STATUS_INQUIRY" -> handleStatusInquiry(normalizedPhone, intent)
                "COMPLAINT" -> handleComplaint(normalizedPhone, message, intent)
                "CONFIRMATION" -> handleConfirmation(normalizedPhone, message, conversationContext)
                "CANCELLATION" -> handleCancellation(normalizedPhone, conversationContext)
                "OTP_REQUEST" -> handleOTPRequest(normalizedPhone)
                "OTP_VERIFY" -> handleOTPVerify(normalizedPhone, message)
                "HELP" -> handleHelp(normalizedPhone)
                "GREETING" -> handleGreeting(normalizedPhone, conversationContext)
                "UNKNOWN" -> handleUnknown(normalizedPhone, message, conversationContext)
                else -> handleUnknown(normalizedPhone, message, conversationContext)
            }

            conversationManager.recordInteraction(normalizedPhone, intent.type, message)
            conversationManager.saveContext(normalizedPhone, conversationContext)

            result

        } catch (e: Exception) {
            Log.e(TAG, "Error processing message: ${e.message}", e)
            SmsProcessingResult.Error("عذراً، حدث خطأ في معالجة رسالتك. يرجى المحاولة لاحقاً.")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 2. Intent Detection (مضمنة داخل الكلاس) ═══
    // ═══════════════════════════════════════════════════════════════

    private fun detectIntent(
        message: String,
        context: SmsConversationManager.ConversationContext
    ): IntentResult {
        val normalized = message.lowercase().trim()

        if (matchesAny(normalized, ORDER_KEYWORDS)) {
            return IntentResult(
                type = "ORDER",
                confidence = 0.9,
                entities = mapOf(
                    "quantity" to (extractEntity(normalized, QUANTITY_PATTERN) ?: ""),
                    "location" to (extractEntity(normalized, LOCATION_PATTERN) ?: ""),
                    "product" to (extractEntity(normalized, PRODUCT_PATTERN) ?: "diesel")
                )
            )
        }

        if (matchesAny(normalized, PRICE_KEYWORDS)) {
            return IntentResult(
                type = "PRICE_INQUIRY",
                confidence = 0.85,
                entities = mapOf("product" to (extractEntity(normalized, PRODUCT_PATTERN) ?: "diesel"))
            )
        }

        if (matchesAny(normalized, AVAILABILITY_KEYWORDS)) {
            return IntentResult(
                type = "AVAILABILITY_INQUIRY",
                confidence = 0.85,
                entities = mapOf("product" to (extractEntity(normalized, PRODUCT_PATTERN) ?: "diesel"))
            )
        }

        if (matchesAny(normalized, DELIVERY_KEYWORDS)) {
            return IntentResult(
                type = "DELIVERY_INQUIRY",
                confidence = 0.8,
                entities = mapOf("location" to (extractEntity(normalized, LOCATION_PATTERN) ?: ""))
            )
        }

        if (matchesAny(normalized, STATUS_KEYWORDS)) {
            return IntentResult(
                type = "STATUS_INQUIRY",
                confidence = 0.8,
                entities = mapOf("order_id" to (extractEntity(normalized, ORDER_ID_PATTERN) ?: ""))
            )
        }

        if (matchesAny(normalized, COMPLAINT_KEYWORDS)) {
            return IntentResult(
                type = "COMPLAINT",
                confidence = 0.85,
                entities = mapOf("complaint_type" to (extractEntity(normalized, COMPLAINT_TYPE_PATTERN) ?: "general"))
            )
        }

        if (matchesAny(normalized, CONFIRM_KEYWORDS)) return IntentResult("CONFIRMATION", 0.9)
        if (matchesAny(normalized, CANCEL_KEYWORDS)) return IntentResult("CANCELLATION", 0.9)
        if (matchesAny(normalized, OTP_REQUEST_KEYWORDS)) return IntentResult("OTP_REQUEST", 0.9)
        if (matchesAny(normalized, OTP_VERIFY_KEYWORDS) || OTP_CODE_PATTERN.matcher(normalized).find()) {
            return IntentResult("OTP_VERIFY", 0.9)
        }
        if (matchesAny(normalized, HELP_KEYWORDS)) return IntentResult("HELP", 0.95)
        if (matchesAny(normalized, GREETING_KEYWORDS)) return IntentResult("GREETING", 0.9)

        if (context.awaitingResponse && context.pendingAction.isNotEmpty()) {
            return IntentResult("CONFIRMATION", 0.6, mapOf("context_action" to context.pendingAction))
        }

        return IntentResult("UNKNOWN", 0.5)
    }

    private fun matchesAny(text: String, keywords: List<String>): Boolean =
        keywords.any { text.contains(it) }

    private fun extractEntity(text: String, pattern: Pattern): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    data class IntentResult(
        val type: String,
        val confidence: Double,
        val entities: Map<String, String> = emptyMap()
    )

    // ═══════════════════════════════════════════════════════════════
    // ═══ 3. معالجة النوايا المختلفة ═══
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleOrderIntent(
        phone: String,
        message: String,
        intent: IntentResult,
        context: SmsConversationManager.ConversationContext
    ): SmsProcessingResult {
        val quantity = extractQuantity(message)
        if (quantity == null || quantity < MIN_ORDER_QUANTITY || quantity > MAX_ORDER_QUANTITY) {
            return SmsProcessingResult.NeedMoreInfo(
                "يرجى تحديد الكمية المطلوبة (باللتر أو الدبة). مثال: "أريد 500 لتر" أو "أريد 2 دبة""
            )
        }

        val location = extractLocation(message)
        if (location == null) {
            context.pendingAction = "AWAITING_LOCATION"
            context.awaitingResponse = true
            return SmsProcessingResult.NeedMoreInfo(
                "يرجى تحديد موقع التوصيل. مثال: "شارع الملك فهد، حي الروضة""
            )
        }

        val deliveryTime = extractDeliveryTime(message)
        val customer = customerResolver.resolveCustomer(phone)

        val draft = conversationManager.getOrCreateOrderDraft(phone)
        draft.apply {
            this.quantityLiters = quantity
            this.deliveryLocation = location
            this.deliveryTime = deliveryTime ?: ""
            this.status = "draft"
            this.step = 1
        }

        return SmsProcessingResult.OrderDraft(
            quantity = quantity,
            location = location,
            deliveryTime = deliveryTime,
            customerName = customer?.name,
            nextStep = "CONFIRM_ORDER"
        )
    }

    private suspend fun handlePriceInquiry(
        phone: String,
        intent: IntentResult
    ): SmsProcessingResult {
        val product = intent.entities["product"] ?: "diesel"
        val price = gateway.getCurrentPrice(product)
        return SmsProcessingResult.PriceInfo(product = product, price = price, unit = "liter")
    }

    private suspend fun handleAvailabilityInquiry(
        phone: String,
        intent: IntentResult
    ): SmsProcessingResult {
        val product = intent.entities["product"] ?: "diesel"
        val isAvailable = gateway.checkAvailability(product)
        return SmsProcessingResult.AvailabilityInfo(product = product, available = isAvailable)
    }

    private suspend fun handleDeliveryInquiry(
        phone: String,
        intent: IntentResult
    ): SmsProcessingResult {
        val location = intent.entities["location"] ?: ""
        val estimatedTime = gateway.getEstimatedDeliveryTime(location)
        return SmsProcessingResult.DeliveryInfo(location = location, estimatedTime = estimatedTime)
    }

    private suspend fun handleStatusInquiry(
        phone: String,
        intent: IntentResult
    ): SmsProcessingResult {
        val orderId = intent.entities["order_id"]
        val status = if (orderId != null) {
            gateway.getOrderStatus(orderId)
        } else {
            gateway.getCustomerLatestOrderStatus(phone)
        }
        return SmsProcessingResult.OrderStatus(orderId = orderId, status = status)
    }

    private suspend fun handleComplaint(
        phone: String,
        message: String,
        intent: IntentResult
    ): SmsProcessingResult {
        val complaintType = intent.entities["complaint_type"] ?: "general"
        val ticketId = gateway.createComplaintTicket(phone, message, complaintType)
        return SmsProcessingResult.ComplaintCreated(ticketId = ticketId, complaintType = complaintType)
    }

    private suspend fun handleConfirmation(
        phone: String,
        message: String,
        context: SmsConversationManager.ConversationContext
    ): SmsProcessingResult {
        val draft = conversationManager.getOrderDraft(phone)
            ?: return SmsProcessingResult.Error("لا يوجد طلب مسودة للتأكيد.")

        if (context.pendingAction == "AWAITING_LOCATION") {
            val location = extractLocation(message)
            if (location != null) {
                draft.deliveryLocation = location
                draft.step = 1
                context.pendingAction = ""
                context.awaitingResponse = false
                return SmsProcessingResult.OrderDraft(
                    quantity = draft.quantityLiters,
                    location = location,
                    deliveryTime = draft.deliveryTime,
                    nextStep = "CONFIRM_ORDER"
                )
            }
        }

        if (draft.step >= 1) {
            val orderId = gateway.submitOrder(phone, draft)
            conversationManager.removeOrderDraft(phone)
            return SmsProcessingResult.OrderConfirmed(
                orderId = orderId,
                quantity = draft.quantityLiters,
                location = draft.deliveryLocation,
                estimatedDelivery = gateway.getEstimatedDeliveryTime(draft.deliveryLocation)
            )
        }

        return SmsProcessingResult.Error("لا يوجد طلب جاهز للتأكيد.")
    }

    private suspend fun handleCancellation(
        phone: String,
        context: SmsConversationManager.ConversationContext
    ): SmsProcessingResult {
        val draft = conversationManager.getOrderDraft(phone)
        if (draft != null) {
            conversationManager.removeOrderDraft(phone)
            return SmsProcessingResult.OrderCancelled(message = "تم إلغاء المسودة بنجاح.")
        }

        val latestOrder = gateway.getCustomerLatestOrder(phone)
        if (latestOrder != null && gateway.canCancelOrder(latestOrder.id)) {
            gateway.cancelOrder(latestOrder.id)
            return SmsProcessingResult.OrderCancelled(
                orderId = latestOrder.id,
                message = "تم إلغاء الطلب ${latestOrder.id} بنجاح."
            )
        }

        return SmsProcessingResult.Error("لا يوجد طلب قابل للإلغاء.")
    }

    private suspend fun handleOTPRequest(phone: String): SmsProcessingResult {
        return try {
            val code = securityOTP.generateOTP(phone)
            SmsProcessingResult.OTPSent(maskedPhone = PhoneUtils.mask(phone), expiryMinutes = 5)
        } catch (e: IllegalStateException) {
            SmsProcessingResult.Error(e.message ?: "يرجى الانتظار قبل طلب OTP جديد.")
        } catch (e: Exception) {
            SmsProcessingResult.Error("حدث خطأ في إرسال OTP. يرجى المحاولة لاحقاً.")
        }
    }

    private suspend fun handleOTPVerify(phone: String, message: String): SmsProcessingResult {
        val code = extractOTPCode(message)
            ?: return SmsProcessingResult.NeedMoreInfo("يرجى إرسال رمز OTP المكون من 4 أرقام.")

        val isValid = securityOTP.verifyOTP(phone, code)
        return if (isValid) {
            SmsProcessingResult.OTPVerified(message = "تم التحقق بنجاح.")
        } else {
            SmsProcessingResult.Error("رمز OTP غير صحيح أو منتهي الصلاحية.")
        }
    }

    private suspend fun handleHelp(phone: String): SmsProcessingResult {
        return SmsProcessingResult.HelpInfo()
    }

    private suspend fun handleGreeting(
        phone: String,
        context: SmsConversationManager.ConversationContext
    ): SmsProcessingResult {
        val customer = customerResolver.resolveCustomer(phone)
        val name = customer?.name ?: "عزيزي العميل"
        return SmsProcessingResult.Greeting(customerName = name, isReturning = customer != null)
    }

    private suspend fun handleUnknown(
        phone: String,
        message: String,
        context: SmsConversationManager.ConversationContext
    ): SmsProcessingResult {
        if (context.awaitingResponse && context.pendingAction == "AWAITING_LOCATION") {
            return handleConfirmation(phone, message, context)
        }
        return SmsProcessingResult.Unknown(
            message = "لم أفهم رسالتك. يرجى كتابة 'مساعدة' لعرض الخيارات المتاحة."
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 4. Extraction Helpers (مضمنة داخل الكلاس) ═══
    // ═══════════════════════════════════════════════════════════════

    private fun extractQuantity(message: String): Double? {
        val matcher = Pattern.compile("(\d+[\.,]?\d*)").matcher(message)
        return if (matcher.find()) {
            matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
        } else null
    }

    private fun extractLocation(message: String): String? {
        val matcher = LOCATION_PATTERN.matcher(message)
        return if (matcher.find()) matcher.group(2)?.trim() else null
    }

    private fun extractDeliveryTime(message: String): String? {
        val matcher = TIME_PATTERN.matcher(message)
        return if (matcher.find()) matcher.group() else null
    }

    private fun extractOTPCode(message: String): String? {
        val matcher = OTP_CODE_PATTERN.matcher(message)
        return if (matcher.find()) matcher.group() else null
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 5. نتائج المعالجة ═══
    // ═══════════════════════════════════════════════════════════════

    sealed class SmsProcessingResult {
        data class OrderDraft(
            val quantity: Double,
            val location: String,
            val deliveryTime: String? = null,
            val customerName: String? = null,
            val nextStep: String = ""
        ) : SmsProcessingResult()

        data class OrderConfirmed(
            val orderId: String,
            val quantity: Double,
            val location: String,
            val estimatedDelivery: String
        ) : SmsProcessingResult()

        data class OrderCancelled(
            val orderId: String? = null,
            val message: String
        ) : SmsProcessingResult()

        data class PriceInfo(
            val product: String,
            val price: Double,
            val unit: String
        ) : SmsProcessingResult()

        data class AvailabilityInfo(
            val product: String,
            val available: Boolean
        ) : SmsProcessingResult()

        data class DeliveryInfo(
            val location: String,
            val estimatedTime: String
        ) : SmsProcessingResult()

        data class OrderStatus(
            val orderId: String? = null,
            val status: String
        ) : SmsProcessingResult()

        data class ComplaintCreated(
            val ticketId: String,
            val complaintType: String
        ) : SmsProcessingResult()

        data class OTPSent(
            val maskedPhone: String,
            val expiryMinutes: Int
        ) : SmsProcessingResult()

        data class OTPVerified(
            val message: String
        ) : SmsProcessingResult()

        data class NeedMoreInfo(
            val message: String
        ) : SmsProcessingResult()

        data class Error(
            val message: String
        ) : SmsProcessingResult()

        data class HelpInfo(
            val commands: List<String> = listOf(
                "طلب [كمية] - طلب وقود",
                "سعر - معرفة السعر الحالي",
                "توصيل [موقع] - معرفة وقت التوصيل",
                "حالة [رقم الطلب] - متابعة الطلب",
                "إلغاء - إلغاء الطلب",
                "مساعدة - عرض الأوامر"
            )
        ) : SmsProcessingResult()

        data class Greeting(
            val customerName: String,
            val isReturning: Boolean
        ) : SmsProcessingResult()

        data class Unknown(
            val message: String
        ) : SmsProcessingResult()
    }
}
