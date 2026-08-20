package com.aistudio.dieselstationsms.kxmpzq.sms

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 * كاشف النية (Intent Detector) - Production Version
 * ═══════════════════════════════════════════════════════════════
 *
 * مسؤول عن:
 * 1. اكتشاف نية رسالة SMS.
 * 2. حساب درجة الثقة.
 * 3. احترام سياق المحادثة الحالية.
 * 4. تحليل الكميات والأوقات والمبالغ والجدولة.
 * 5. تطبيع النص العربي والأرقام العربية.
 *
 * قواعد الكميات:
 *
 * 1 دبة = 20 لتر.
 *
 * البرميل العادي:
 * 1 برميل عادي = 10 دباب = 200 لتر.
 *
 * البرميل الكبير:
 * 1 برميل كبير = 12 دبة = 240 لتر.
 *
 * ملاحظة:
 * لا يتم تغيير أسماء الـ intents لأنها جزء من عقد التكامل
 * مع SmsProcessor.kt.
 */
class SmsIntentDetector {

    companion object {

        // ═══════════════════════════════════════════════════════
        // Quantity Constants
        // ═══════════════════════════════════════════════════════

        private const val LITERS_PER_DABBA = 20.0

        private const val ORDINARY_BARREL_DABBAS = 10.0

        private const val LARGE_BARREL_DABBAS = 12.0

        private const val ORDINARY_BARREL_LITERS =
            LITERS_PER_DABBA * ORDINARY_BARREL_DABBAS

        private const val LARGE_BARREL_LITERS =
            LITERS_PER_DABBA * LARGE_BARREL_DABBAS

        private const val MAX_QUANTITY_LITERS = 10_000.0

        // ═══════════════════════════════════════════════════════
        // Location
        // ═══════════════════════════════════════════════════════

        private const val MIN_LOCATION_LENGTH = 3
        private const val MAX_LOCATION_LENGTH = 200

        // ═══════════════════════════════════════════════════════
        // Confidence
        // ═══════════════════════════════════════════════════════

        private const val UNKNOWN_CONFIDENCE_THRESHOLD = 30

        // ═══════════════════════════════════════════════════════
        // Arabic Digits
        // ═══════════════════════════════════════════════════════

        private val ARABIC_INDIC_DIGITS = charArrayOf(
            '٠', '١', '٢', '٣', '٤',
            '٥', '٦', '٧', '٨', '٩'
        )

        private val ARABIC_EASTERN_DIGITS = charArrayOf(
            '۰', '۱', '۲', '۳', '۴',
            '۵', '۶', '۷', '۸', '۹'
        )

        // ═══════════════════════════════════════════════════════
        // Number Regex
        // ═══════════════════════════════════════════════════════

        private val NUMBER_ONLY_REGEX =
            Regex(
                """^\d{1,7}(?:[.,]\d{1,3})?$"""
            )

        private val DABBA_REGEX =
            Regex(
                """(\d{1,7}(?:[.,]\d{1,3})?)\s*(?:دبة|دبه|دبات|دباب|دبابات)"""
            )

        private val LITER_REGEX =
            Regex(
                """(\d{1,8}(?:[.,]\d{1,3})?)\s*(?:لتر|لترات|ltr|liter|liters)""",
                RegexOption.IGNORE_CASE
            )

        /*
         * مهم:
         *
         * البرميل الكبير يجب أن يُفحص قبل البرميل العادي.
         *
         * أمثلة:
         * 1 برميل كبير = 240 لتر
         * 2 برميل كبير = 480 لتر
         * برميل كبير = 240 لتر
         */
        private val LARGE_BARREL_REGEX =
            Regex(
                """(\d{1,5}(?:[.,]\d{1,3})?)?\s*(?:برميل|براميل)\s*(?:كبير|الكبير)"""
            )

        /*
         * البرميل العادي:
         *
         * برميل
         * برميل عادي
         * البرميل
         * البرميل العادي
         * 2 برميل
         * 2 برميل عادي
         */
        private val ORDINARY_BARREL_REGEX =
            Regex(
                """(\d{1,5}(?:[.,]\d{1,3})?)?\s*(?:برميل|براميل)(?:\s*(?:عادي|العادي))?"""
            )

        // ═══════════════════════════════════════════════════════
        // Time
        // ═══════════════════════════════════════════════════════

        private val TIME_REGEX =
            Regex(
                """(?:^|\s)(\d{1,2})(?:\s*[:.]\s*(\d{1,2}))?\s*(صباح(?:اً|ا)?|ص|مساء(?:ً|ا)?|م|am|pm)?(?=\s|$)""",
                RegexOption.IGNORE_CASE
            )

        // ═══════════════════════════════════════════════════════
        // Rating
        // ═══════════════════════════════════════════════════════

        private val RATING_REGEX =
            Regex("""^[1-5]$""")

        // ═══════════════════════════════════════════════════════
        // Amount
        // ═══════════════════════════════════════════════════════

        private val AMOUNT_REGEX =
            Regex(
                """(?:^|[^\d])(\d{1,12}(?:[.,]\d{1,3})?)\s*(?:ريال|ريالاً|ريالات|رياليات|yer|riyal|ry)(?:[^\d]|$)""",
                RegexOption.IGNORE_CASE
            )

        private val GENERIC_NUMBER_REGEX =
            Regex(
                """\d{1,12}(?:[.,]\d{1,3})?"""
            )

        // ═══════════════════════════════════════════════════════
        // Recurring Schedule
        // ═══════════════════════════════════════════════════════

        private val RECURRING_REGEX =
            Regex(
                """كل\s+(يوم|أسبوع|اسبوع|شهر)\s*(.*)"""
            )

        // ═══════════════════════════════════════════════════════
        // Quantity Response
        // ═══════════════════════════════════════════════════════

        private val QUANTITY_RESPONSE_REGEX =
            Regex(
                """^\s*\d{1,7}(?:[.,]\d{1,3})?\s*(?:دبة|دبه|دبات|دباب|دبابات|لتر|لترات|ltr|liter|liters)?\s*$""",
                RegexOption.IGNORE_CASE
            )

        // ═══════════════════════════════════════════════════════
        // Text Normalization
        // ═══════════════════════════════════════════════════════

        private val ARABIC_DIACRITICS_REGEX =
            Regex("[\\u064B-\\u065F\\u0670]")

        private val MULTIPLE_SPACES_REGEX =
            Regex("""\s+""")

        /*
         * هذه النسخة لا تحذف النقطة العشرية أو النقطتين الخاصة
         * بالوقت.
         *
         * normalizeText() تستخدم نسخة أخرى أكثر صرامة.
         */
        private val TEXT_PUNCTUATION_REGEX =
            Regex("""[،,:;؛!?؟.!]+""")

        /*
         * خاصة بالنصوص الرقمية.
         *
         * نحتفظ بـ:
         * .
         * ,
         * :
         *
         * لأنها قد تكون:
         *
         * 12.5
         * 12,5
         * 5:30
         */
        private val NUMBER_TEXT_PUNCTUATION_REGEX =
            Regex("""[،؛!?؟]+""")

        // ═══════════════════════════════════════════════════════
        // Intent Detection
        // ═══════════════════════════════════════════════════════

    }

    // ═══════════════════════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════════════════════

    data class IntentResult(
        val intent: String,
        val confidence: Int,
        val allScores: Map<String, Int> = emptyMap()
    )

    data class ConversationState(
        var awaitingResponse: Boolean = false,
        var pendingAction: String = "",
        var lastTopic: String = "",
        var timestamp: Long = System.currentTimeMillis()
    )

    data class QuantityInfo(
        val liters: Double,
        val dabbas: Double,
        val isDabba: Boolean
    )

    data class TimeInfo(
        val displayTime: String,
        val timestamp: Long
    )

    // ═══════════════════════════════════════════════════════════════
    // Intent Detection
    // ═══════════════════════════════════════════════════════════════

    fun detectIntent(
        msgBody: String,
        ctx: ConversationState,
        sender: String
    ): IntentResult {

        val normalized = normalizeText(msgBody)

        if (normalized.isEmpty()) {
            return IntentResult(
                intent = "unknown",
                confidence = 0
            )
        }

        /*
         * النوايا الحساسة جدًا:
         *
         * الإلغاء والتأكيد والطوارئ يجب أن تسبق بقية
         * التصنيفات حتى لا يتم تفسير الرسالة بشكل خاطئ.
         */
        detectHighPriorityIntent(normalized)?.let {
            return it
        }

        /*
         * إذا كانت هناك محادثة معلقة، نعطي السياق الأولوية.
         */
        if (ctx.awaitingResponse) {

            val contextResult =
                detectContextIntent(
                    normalized,
                    ctx
                )

            if (contextResult != null) {
                return contextResult
            }
        }

        val scores =
            linkedMapOf<String, Int>()

        scores["diesel_request"] =
            scoreDieselRequest(normalized)

        scores["gasoline_request"] =
            scoreGasolineRequest(normalized)

        scores["quantity_response"] =
            scoreQuantityResponse(normalized)

        scores["quantity_ambiguous"] =
            scoreAmbiguousQuantity(normalized)

        scores["confirm_order"] =
            scoreConfirmOrder(normalized)

        scores["cancel_order"] =
            scoreCancelOrder(normalized)

        scores["balance_query"] =
            scoreBalanceQuery(normalized)

        scores["payment_request"] =
            scorePaymentRequest(normalized)

        scores["transfer_request"] =
            scoreTransferRequest(normalized)

        scores["offers_query"] =
            scoreOffersQuery(normalized)

        scores["price_query"] =
            scorePriceQuery(normalized)

        scores["loyalty_query"] =
            scoreLoyaltyQuery(normalized)

        scores["redeem_points"] =
            scoreRedeemPoints(normalized)

        scores["track_order"] =
            scoreTrackOrder(normalized)

        scores["order_history"] =
            scoreOrderHistory(normalized)

        scores["help"] =
            scoreHelp(normalized)

        scores["complaint"] =
            scoreComplaint(normalized)

        scores["emergency"] =
            scoreEmergency(normalized)

        scores["callback_request"] =
            scoreCallbackRequest(normalized)

        scores["location_query"] =
            scoreLocationQuery(normalized)

        scores["working_hours"] =
            scoreWorkingHours(normalized)

        scores["invoice_request"] =
            scoreInvoiceRequest(normalized)

        scores["weekly_report"] =
            scoreWeeklyReport(normalized)

        scores["schedule_appointment"] =
            scoreScheduleAppointment(normalized)

        scores["schedule_recurring"] =
            scoreRecurringSchedule(normalized)

        scores["rating"] =
            scoreRating(normalized)

        scores["greeting"] =
            scoreGreeting(normalized)

        scores["thanks"] =
            scoreThanks(normalized)

        /*
         * طلب الوقود مع كمية يجب أن يكون أقوى من
         * quantity_response.
         *
         * أمثلة:
         *
         * 5 دباب ديزل
         * 20 دبة بنزين
         * 200 لتر ديزل
         */
        val hasQuantity =
            containsQuantityExpression(normalized)

        val hasDiesel =
            scoreDieselRequest(normalized) > 0

        val hasGasoline =
            scoreGasolineRequest(normalized) > 0

        if (hasQuantity && hasDiesel) {
            scores["diesel_request"] =
                maxOf(
                    scores["diesel_request"] ?: 0,
                    100
                )
        }

        if (hasQuantity && hasGasoline) {
            scores["gasoline_request"] =
                maxOf(
                    scores["gasoline_request"] ?: 0,
                    100
                )
        }

        /*
         * إذا كانت الرسالة طلب وقود واضحًا، لا نسمح
         * لبعض النوايا الثانوية بتجاوزها.
         */
        if (hasDiesel && hasGasoline) {

            /*
             * إذا احتوت الرسالة على النوعين معًا،
             * لا نخمن نوع الوقود.
             */
            val dieselScore =
                scores["diesel_request"] ?: 0

            val gasolineScore =
                scores["gasoline_request"] ?: 0

            if (
                dieselScore >= 80 &&
                gasolineScore >= 80
            ) {
                return IntentResult(
                    intent = "unknown",
                    confidence = maxOf(
                        dieselScore,
                        gasolineScore
                    ),
                    allScores = scores
                )
            }
        }

        val bestIntent =
            scores
                .filterValues { it > 0 }
                .maxByOrNull { it.value }

                ?: return IntentResult(
                    intent = "unknown",
                    confidence = 0,
                    allScores = scores
                )

        if (
            bestIntent.value <
            UNKNOWN_CONFIDENCE_THRESHOLD
        ) {
            return IntentResult(
                intent = "unknown",
                confidence = bestIntent.value,
                allScores = scores
            )
        }

        return IntentResult(
            intent = bestIntent.key,
            confidence = bestIntent.value.coerceIn(
                0,
                100
            ),
            allScores = scores
        )
    }

    private fun detectHighPriorityIntent(
        msg: String
    ): IntentResult? {

        if (isCancelMessage(msg)) {
            return IntentResult(
                intent = "cancel_order",
                confidence = 100
            )
        }

        if (isConfirmMessage(msg)) {
            return IntentResult(
                intent = "confirm_order",
                confidence = 100
            )
        }

        val emergencyScore =
            scoreEmergency(msg)

        if (emergencyScore >= 95) {
            return IntentResult(
                intent = "emergency",
                confidence = emergencyScore
            )
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // Context Detection
    // ═══════════════════════════════════════════════════════════════

    private fun detectContextIntent(
        normalized: String,
        ctx: ConversationState
    ): IntentResult? {

        return when (
            ctx.pendingAction.trim().lowercase(Locale.ROOT)
        ) {

            "awaiting_quantity",
            "awaiting_quantity_gasoline" -> {

                when {

                    QUANTITY_RESPONSE_REGEX.matches(
                        normalized
                    ) -> {
                        IntentResult(
                            intent = "quantity_response",
                            confidence = 98
                        )
                    }

                    containsQuantityExpression(
                        normalized
                    ) -> {
                        IntentResult(
                            intent = "quantity_response",
                            confidence = 92
                        )
                    }

                    else -> null
                }
            }

            "awaiting_location" -> {

                if (
                    normalized.length in
                    MIN_LOCATION_LENGTH..MAX_LOCATION_LENGTH &&
                    !isCancelMessage(normalized) &&
                    !isConfirmMessage(normalized) &&
                    !containsObviousCommand(normalized)
                ) {

                    IntentResult(
                        intent = "location_response",
                        confidence = 92
                    )

                } else {
                    null
                }
            }

            "awaiting_time" -> {

                if (
                    parseDeliveryTime(
                        normalized
                    ) != null
                ) {

                    IntentResult(
                        intent = "time_response",
                        confidence = 96
                    )

                } else {
                    null
                }
            }

            "awaiting_confirmation" -> {

                when {

                    isConfirmMessage(normalized) ->
                        IntentResult(
                            intent = "confirm_order",
                            confidence = 98
                        )

                    isCancelMessage(normalized) ->
                        IntentResult(
                            intent = "cancel_order",
                            confidence = 98
                        )

                    else -> null
                }
            }

            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Diesel
    // ═══════════════════════════════════════════════════════════════

    private fun scoreDieselRequest(
        msg: String
    ): Int {

        if (
            msg.contains("اريد ديزل") ||
            msg.contains("اريد لي ديزل") ||
            msg.contains("طلب ديزل") ||
            msg.contains("اشتي ديزل") ||
            msg.contains("ابغى ديزل") ||
            msg.contains("عايز ديزل") ||
            msg.contains("نشتي ديزل") ||
            msg.contains("صبو لي ديزل") ||
            msg.contains("هاتو ديزل")
        ) {
            return 100
        }

        var score = 0

        if (msg.contains("diesel")) {
            score += 90
        }

        if (
            msg.contains("ديزل") &&
            !msg.contains("بنزين")
        ) {
            score += 85
        }

        if (
            msg.contains("توريد ديزل")
        ) {
            score += 80
        }

        if (
            msg.contains("محروقات") &&
            msg.contains("ثقيل")
        ) {
            score += 65
        }

        if (
            containsQuantityExpression(msg) &&
            msg.contains("ديزل")
        ) {
            score += 20
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Gasoline
    // ═══════════════════════════════════════════════════════════════

    private fun scoreGasolineRequest(
        msg: String
    ): Int {

        if (
            msg.contains("اريد بنزين") ||
            msg.contains("اريد لي بنزين") ||
            msg.contains("طلب بنزين") ||
            msg.contains("اشتي بنزين") ||
            msg.contains("ابغى بنزين") ||
            msg.contains("عايز بنزين") ||
            msg.contains("نشتي بنزين") ||
            msg.contains("صبو لي بنزين") ||
            msg.contains("هاتو بنزين")
        ) {
            return 100
        }

        var score = 0

        if (msg.contains("بنزين")) {
            score += 90
        }

        if (msg.contains("gasoline")) {
            score += 90
        }

        if (msg.contains("petrol")) {
            score += 88
        }

        if (
            msg.contains("توريد بنزين")
        ) {
            score += 85
        }

        if (
            containsQuantityExpression(msg) &&
            msg.contains("بنزين")
        ) {
            score += 20
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Quantity
    // ═══════════════════════════════════════════════════════════════

    private fun scoreAmbiguousQuantity(msg: String): Int {
        return if (NUMBER_ONLY_REGEX.matches(msg)) 96 else 0
    }

    private fun scoreQuantityResponse(
        msg: String
    ): Int {

        if (
            NUMBER_ONLY_REGEX.matches(msg)
        ) {
            return 0
        }

        var score = 0

        if (
            DABBA_REGEX.containsMatchIn(msg)
        ) {
            score += 90
        }

        if (
            LITER_REGEX.containsMatchIn(msg)
        ) {
            score += 90
        }

        if (
            LARGE_BARREL_REGEX.containsMatchIn(msg)
        ) {
            score += 95
        }

        if (
            ORDINARY_BARREL_REGEX.containsMatchIn(msg)
        ) {
            score += 90
        }

        if (
            msg.contains("دبة") ||
            msg.contains("دبه") ||
            msg.contains("دباب") ||
            msg.contains("دبات") ||
            msg.contains("دبابات")
        ) {
            score += 85
        }

        if (
            msg.contains("لتر") ||
            msg.contains("لترات") ||
            msg.contains("ltr") ||
            msg.contains("liter") ||
            msg.contains("liters")
        ) {
            score += 80
        }

        if (
            msg.contains("برميل") ||
            msg.contains("براميل")
        ) {
            score += 85
        }

        /*
         * طلب الوقود لا يجب أن يصبح quantity_response.
         */
        if (
            msg.contains("ديزل") ||
            msg.contains("بنزين") ||
            msg.contains("gasoline") ||
            msg.contains("diesel") ||
            msg.contains("petrol")
        ) {
            score = minOf(
                score,
                70
            )
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Confirmation
    // ═══════════════════════════════════════════════════════════════

    private fun scoreConfirmOrder(
        msg: String
    ): Int {

        if (
            msg == "تاكيد" ||
            msg == "تأكيد"
        ) {
            return 100
        }

        var score = 0

        if (
            msg.contains("تاكيد") ||
            msg.contains("تأكيد")
        ) {
            score += 92
        }

        if (msg.contains("confirm")) {
            score += 88
        }

        if (
            msg == "نعم" ||
            msg == "نعم موافق" ||
            msg == "موافق" ||
            msg == "تم"
        ) {
            score += 85
        }

        if (msg == "yes") {
            score += 80
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Cancellation
    // ═══════════════════════════════════════════════════════════════

    private fun scoreCancelOrder(
        msg: String
    ): Int {

        if (
            msg == "الغاء" ||
            msg == "الغي" ||
            msg == "الغى"
        ) {
            return 100
        }

        var score = 0

        if (
            msg.contains("الغاء")
        ) {
            score += 95
        }

        if (
            msg.contains("الغي") ||
            msg.contains("الغى")
        ) {
            score += 90
        }

        if (msg == "cancel") {
            score += 90
        }

        if (msg == "لا") {
            score += 80
        }

        if (msg == "no") {
            score += 75
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Balance
    // ═══════════════════════════════════════════════════════════════

    private fun scoreBalanceQuery(
        msg: String
    ): Int {

        if (msg == "رصيد") {
            return 100
        }

        var score = 0

        if (msg.contains("رصيد")) {
            score += 92
        }

        if (msg.contains("حساب")) {
            score += 85
        }

        if (msg.contains("balance")) {
            score += 80
        }

        if (msg.contains("كم علي")) {
            score += 80
        }

        if (msg.contains("كم لي")) {
            score += 80
        }

        if (msg.contains("علي كم")) {
            score += 78
        }

        if (msg.contains("لي كم")) {
            score += 78
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Payment
    // ═══════════════════════════════════════════════════════════════

    private fun scorePaymentRequest(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("دفع")) {
            score += 92
        }

        if (msg.contains("تسديد")) {
            score += 92
        }

        if (msg.contains("سداد")) {
            score += 90
        }

        if (msg.contains("سدد")) {
            score += 85
        }

        if (msg.contains("pay")) {
            score += 80
        }

        if (msg.contains("payment")) {
            score += 78
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Transfer
    // ═══════════════════════════════════════════════════════════════

    private fun scoreTransferRequest(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("تحويل")) {
            score += 92
        }

        if (msg.contains("transfer")) {
            score += 88
        }

        if (msg.contains("بنكي")) {
            score += 82
        }

        if (msg.contains("bank")) {
            score += 78
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Offers
    // ═══════════════════════════════════════════════════════════════

    private fun scoreOffersQuery(
        msg: String
    ): Int {

        var score = 0

        if (
            msg.contains("عروض") ||
            msg.contains("عرض") ||
            msg.contains("تخفيض") ||
            msg.contains("خصم")
        ) {
            score += 95
        }

        if (msg.contains("offer")) {
            score += 88
        }

        if (msg.contains("offers")) {
            score += 88
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Price
    // ═══════════════════════════════════════════════════════════════

    private fun scorePriceQuery(
        msg: String
    ): Int {

        var score = 0

        if (msg == "سعر") {
            score = 100
        }

        if (msg.contains("سعر")) {
            score += 92
        }

        if (msg.contains("كم سعر")) {
            score += 96
        }

        if (msg.contains("بكم")) {
            score += 90
        }

        if (msg.contains("اسعار")) {
            score += 90
        }

        if (msg.contains("price")) {
            score += 85
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Loyalty
    // ═══════════════════════════════════════════════════════════════

    private fun scoreLoyaltyQuery(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("نقاط")) {
            score += 92
        }

        if (msg.contains("ولاء")) {
            score += 90
        }

        if (msg.contains("points")) {
            score += 85
        }

        if (msg.contains("loyalty")) {
            score += 82
        }

        if (msg.contains("مكافات")) {
            score += 80
        }

        if (msg.contains("مكافآت")) {
            score += 80
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Redeem Points
    // ═══════════════════════════════════════════════════════════════

    private fun scoreRedeemPoints(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("استبدال")) {
            score += 95
        }

        if (msg.contains("استبدل")) {
            score += 95
        }

        if (msg.contains("redeem")) {
            score += 90
        }

        if (msg.contains("صرف نقاط")) {
            score += 85
        }

        if (msg.contains("تحويل نقاط")) {
            score += 82
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Track Order
    // ═══════════════════════════════════════════════════════════════

    private fun scoreTrackOrder(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("حالة الطلب")) {
            score += 100
        }

        if (msg.contains("تتبع")) {
            score += 92
        }

        if (msg.contains("track")) {
            score += 88
        }

        if (msg.contains("order status")) {
            score += 88
        }

        if (msg.contains("طلبي")) {
            score += 88
        }

        if (
            msg.contains("وين الطلب") ||
            msg.contains("اين الطلب")
        ) {
            score += 85
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Order History
    // ═══════════════════════════════════════════════════════════════

    private fun scoreOrderHistory(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("سجل الطلبات")) {
            score += 100
        }

        if (msg == "سجل") {
            score += 90
        }

        if (msg.contains("سجل")) {
            score += 75
        }

        if (msg.contains("تاريخ الطلبات")) {
            score += 92
        }

        if (msg.contains("طلباتي")) {
            score += 90
        }

        if (msg.contains("history")) {
            score += 85
        }

        if (msg.contains("فواتيري")) {
            score += 78
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Help
    // ═══════════════════════════════════════════════════════════════

    private fun scoreHelp(
        msg: String
    ): Int {

        var score = 0

        if (
            msg == "استعلام" ||
            msg == "مساعدة" ||
            msg == "الخدمات"
        ) {
            score += 100
        }

        if (msg.contains("استعلام")) {
            score += 92
        }

        if (msg.contains("مساعدة")) {
            score += 90
        }

        if (msg.contains("help")) {
            score += 88
        }

        if (msg.contains("قائمة")) {
            score += 82
        }

        if (msg.contains("menu")) {
            score += 80
        }

        if (msg.contains("خدمات")) {
            score += 80
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Complaint
    // ═══════════════════════════════════════════════════════════════

    private fun scoreComplaint(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("شكوى")) {
            score += 98
        }

        if (
            msg.contains("شكايه") ||
            msg.contains("شكايتي")
        ) {
            score += 95
        }

        if (msg.contains("complaint")) {
            score += 90
        }

        if (
            msg.contains("مشكلة") ||
            msg.contains("مشكله")
        ) {
            score += 85
        }

        if (msg.contains("problem")) {
            score += 82
        }

        if (msg.contains("سيء")) {
            score += 65
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Emergency
    // ═══════════════════════════════════════════════════════════════

    private fun scoreEmergency(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("طوارئ")) {
            score += 100
        }

        if (msg.contains("طارئ")) {
            score += 98
        }

        if (msg.contains("urgent")) {
            score += 92
        }

        if (msg.contains("emergency")) {
            score += 92
        }

        if (msg.contains("عاجل")) {
            score += 95
        }

        if (msg.contains("ضروري")) {
            score += 78
        }

        if (msg.contains("نجدة")) {
            score += 90
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Callback
    // ═══════════════════════════════════════════════════════════════

    private fun scoreCallbackRequest(
        msg: String
    ): Int {

        var score = 0

        if (msg == "اتصال") {
            score += 100
        }

        if (msg.contains("اتصلوا")) {
            score += 95
        }

        if (msg.contains("اتصل")) {
            score += 90
        }

        if (
            msg.contains("كلموني") ||
            msg.contains("كلمني")
        ) {
            score += 90
        }

        if (msg.contains("callback")) {
            score += 88
        }

        if (msg.contains("اتصل بي")) {
            score += 95
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Location
    // ═══════════════════════════════════════════════════════════════

    private fun scoreLocationQuery(
        msg: String
    ): Int {

        var score = 0

        if (msg == "موقع") {
            score += 100
        }

        if (msg.contains("موقع المحطة")) {
            score += 98
        }

        if (msg.contains("موقع")) {
            score += 90
        }

        if (msg.contains("location")) {
            score += 85
        }

        if (msg.contains("عنوان")) {
            score += 90
        }

        if (msg.contains("خريطة")) {
            score += 82
        }

        if (
            msg.contains("وين المحطة") ||
            msg.contains("اين المحطة")
        ) {
            score += 90
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Working Hours
    // ═══════════════════════════════════════════════════════════════

    private fun scoreWorkingHours(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("ساعات العمل")) {
            score += 100
        }

        if (msg.contains("ساعات")) {
            score += 90
        }

        if (msg.contains("مواعيد العمل")) {
            score += 95
        }

        if (msg.contains("مواعيد")) {
            score += 85
        }

        if (msg.contains("hours")) {
            score += 85
        }

        if (msg.contains("متى تفتح")) {
            score += 95
        }

        if (
            msg.contains("متى تسكر") ||
            msg.contains("متى تغلق") ||
            msg.contains("متى تقفل")
        ) {
            score += 95
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Invoice
    // ═══════════════════════════════════════════════════════════════

    private fun scoreInvoiceRequest(
        msg: String
    ): Int {

        var score = 0

        if (msg == "فاتورة") {
            score += 100
        }

        if (msg.contains("فاتورة")) {
            score += 95
        }

        if (msg.contains("فواتير")) {
            score += 90
        }

        if (msg.contains("invoice")) {
            score += 88
        }

        if (msg.contains("bill")) {
            score += 82
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Weekly Report
    // ═══════════════════════════════════════════════════════════════

    private fun scoreWeeklyReport(
        msg: String
    ): Int {

        var score = 0

        if (msg == "تقرير") {
            score += 100
        }

        if (msg.contains("تقرير اسبوعي")) {
            score += 100
        }

        if (msg.contains("تقرير")) {
            score += 90
        }

        if (msg.contains("report")) {
            score += 85
        }

        if (msg.contains("weekly")) {
            score += 88
        }

        if (msg.contains("ملخص")) {
            score += 85
        }

        if (
            msg.contains("احصائيات") ||
            msg.contains("إحصائيات")
        ) {
            score += 80
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Scheduling
    // ═══════════════════════════════════════════════════════════════

    private fun scoreScheduleAppointment(
        msg: String
    ): Int {

        var score = 0

        if (msg.startsWith("حجز")) {
            score += 98
        }

        if (msg.contains("حجز")) {
            score += 90
        }

        if (msg.contains("موعد")) {
            score += 88
        }

        if (msg.contains("appointment")) {
            score += 85
        }

        if (msg.contains("booking")) {
            score += 85
        }

        if (msg.contains("جدولة")) {
            score += 82
        }

        return score.coerceAtMost(100)
    }

    private fun scoreRecurringSchedule(
        msg: String
    ): Int {

        var score = 0

        if (
            RECURRING_REGEX.containsMatchIn(msg)
        ) {
            score += 100
        }

        if (msg.contains("كل يوم")) {
            score += 95
        }

        if (
            msg.contains("كل اسبوع") ||
            msg.contains("كل أسبوع")
        ) {
            score += 95
        }

        if (msg.contains("كل شهر")) {
            score += 95
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Rating
    // ═══════════════════════════════════════════════════════════════

    private fun scoreRating(
        msg: String
    ): Int {

        if (
            RATING_REGEX.matches(msg)
        ) {
            return 95
        }

        var score = 0

        if (msg.contains("تقييم")) {
            score += 90
        }

        if (msg.contains("rating")) {
            score += 85
        }

        if (msg.contains("rate")) {
            score += 80
        }

        if (
            msg.contains("نجمة") ||
            msg.contains("نجوم")
        ) {
            score += 78
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Greeting
    // ═══════════════════════════════════════════════════════════════

    private fun scoreGreeting(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("مرحب")) {
            score += 92
        }

        if (msg.contains("hello")) {
            score += 90
        }

        if (msg == "hi") {
            score += 88
        }

        if (msg.contains("صباح الخير")) {
            score += 100
        }

        if (msg.contains("مساء الخير")) {
            score += 100
        }

        if (msg.contains("اهلا")) {
            score += 92
        }

        if (msg.contains("السلام عليكم")) {
            score += 100
        }

        if (msg.contains("السلام")) {
            score += 88
        }

        if (msg.contains("تحية")) {
            score += 75
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Thanks
    // ═══════════════════════════════════════════════════════════════

    private fun scoreThanks(
        msg: String
    ): Int {

        var score = 0

        if (msg.contains("شكرا")) {
            score += 98
        }

        if (msg.contains("شكراً")) {
            score += 98
        }

        if (msg.contains("شكري")) {
            score += 85
        }

        if (msg.contains("thanks")) {
            score += 90
        }

        if (msg.contains("thank you")) {
            score += 88
        }

        if (msg.contains("مشكور")) {
            score += 95
        }

        if (msg.contains("تسلم")) {
            score += 82
        }

        if (msg.contains("يعطيك العافية")) {
            score += 90
        }

        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // Quantity Parsing
    // ═══════════════════════════════════════════════════════════════

    fun parseQuantity(
        msgBody: String
    ): QuantityInfo {

        val normalized =
            normalizeNumberText(msgBody)

        if (normalized.isEmpty()) {
            return emptyQuantity()
        }

        /*
         * ═══════════════════════════════════════════════════════
         * 1. البرميل الكبير
         * ═══════════════════════════════════════════════════════
         *
         * 1 برميل كبير = 12 دبة = 240 لتر
         */
        LARGE_BARREL_REGEX
            .find(normalized)
            ?.let { match ->

                val barrels =
                    parseNumber(
                        match.groupValues
                            .getOrNull(1)
                    ).takeIf {
                        it > 0.0
                    } ?: 1.0

                val dabbas =
                    barrels *
                        LARGE_BARREL_DABBAS

                val liters =
                    barrels *
                        LARGE_BARREL_LITERS

                return if (
                    isValidQuantity(
                        liters
                    )
                ) {

                    QuantityInfo(
                        liters = liters,
                        dabbas = dabbas,
                        isDabba = false
                    )

                } else {

                    emptyQuantity()
                }
            }

        /*
         * ═══════════════════════════════════════════════════════
         * 2. البرميل العادي
         * ═══════════════════════════════════════════════════════
         *
         * 1 برميل عادي = 10 دباب = 200 لتر
         */
        ORDINARY_BARREL_REGEX
            .find(normalized)
            ?.let { match ->

                /*
                 * منع اعتبار "برميل كبير" كبرميل عادي.
                 *
                 * المفروض أن LARGE_BARREL_REGEX سبق وفاز،
                 * لكن هذا الحارس يجعل السلوك آمنًا حتى لو
                 * تغير ترتيب الاستدعاءات مستقبلًا.
                 */
                if (
                    normalized.contains(
                        "برميل كبير"
                    ) ||
                    normalized.contains(
                        "براميل كبير"
                    )
                ) {
                    return@let
                }

                val barrels =
                    parseNumber(
                        match.groupValues
                            .getOrNull(1)
                    ).takeIf {
                        it > 0.0
                    } ?: 1.0

                val dabbas =
                    barrels *
                        ORDINARY_BARREL_DABBAS

                val liters =
                    barrels *
                        ORDINARY_BARREL_LITERS

                if (
                    isValidQuantity(
                        liters
                    )
                ) {

                    return QuantityInfo(
                        liters = liters,
                        dabbas = dabbas,
                        isDabba = false
                    )
                }
            }

        /*
         * ═══════════════════════════════════════════════════════
         * 3. دبة / دباب
         * ═══════════════════════════════════════════════════════
         */
        DABBA_REGEX
            .find(normalized)
            ?.let { match ->

                val dabbas =
                    parseNumber(
                        match.groupValues
                            .getOrNull(1)
                    )

                if (dabbas > 0.0) {

                    val liters =
                        dabbas *
                            LITERS_PER_DABBA

                    if (
                        isValidQuantity(
                            liters
                        )
                    ) {

                        return QuantityInfo(
                            liters = liters,
                            dabbas = dabbas,
                            isDabba = true
                        )
                    }
                }
            }

        /*
         * ═══════════════════════════════════════════════════════
         * 4. لتر
         * ═══════════════════════════════════════════════════════
         */
        LITER_REGEX
            .find(normalized)
            ?.let { match ->

                val liters =
                    parseNumber(
                        match.groupValues
                            .getOrNull(1)
                    )

                if (
                    isValidQuantity(
                        liters
                    )
                ) {

                    return QuantityInfo(
                        liters = liters,
                        dabbas =
                            liters /
                                LITERS_PER_DABBA,
                        isDabba = false
                    )
                }
            }

        /*
         * ═══════════════════════════════════════════════════════
         * 5. رقم فقط
         * ═══════════════════════════════════════════════════════
         *
         * القاعدة التاريخية للمشروع:
         *
         * 1..50  => دباب
         * >50    => لتر
         *
         * أمثلة:
         *
         * "5"  => 5 دباب = 100 لتر
         * "20" => 20 دبة = 400 لتر
         * "50" => 50 دبة = 1000 لتر
         * "200" => 200 لتر
         */
        NUMBER_ONLY_REGEX
            .matchEntire(normalized)
            ?.let { match ->

                val value =
                    parseNumber(
                        match.value
                    )

                if (value <= 0.0) {
                    return emptyQuantity()
                }

                if (value <= 50.0) {

                    val liters =
                        value *
                            LITERS_PER_DABBA

                    return if (
                        isValidQuantity(
                            liters
                        )
                    ) {

                        QuantityInfo(
                            liters = liters,
                            dabbas = value,
                            isDabba = true
                        )

                    } else {

                        emptyQuantity()
                    }

                } else {

                    return if (
                        isValidQuantity(
                            value
                        )
                    ) {

                        QuantityInfo(
                            liters = value,
                            dabbas =
                                value /
                                    LITERS_PER_DABBA,
                            isDabba = false
                        )

                    } else {

                        emptyQuantity()
                    }
                }
            }

        return emptyQuantity()
    }

    private fun emptyQuantity(): QuantityInfo =
        QuantityInfo(
            liters = 0.0,
            dabbas = 0.0,
            isDabba = false
        )

    private fun isValidQuantity(
        liters: Double
    ): Boolean {

        return liters > 0.0 &&
            liters <= MAX_QUANTITY_LITERS
    }

    private fun containsQuantityExpression(
        msg: String
    ): Boolean {

        val normalized =
            normalizeNumberText(msg)

        return NUMBER_ONLY_REGEX.matches(
            normalized
        ) ||
            DABBA_REGEX.containsMatchIn(
                normalized
            ) ||
            LITER_REGEX.containsMatchIn(
                normalized
            ) ||
            LARGE_BARREL_REGEX.containsMatchIn(
                normalized
            ) ||
            ORDINARY_BARREL_REGEX.containsMatchIn(
                normalized
            )
    }

    // ═══════════════════════════════════════════════════════════════
    // Delivery Time Parsing
    // ═══════════════════════════════════════════════════════════════

    fun parseDeliveryTime(
        msgBody: String
    ): TimeInfo? {

        val normalized =
            normalizeNumberText(msgBody)
                .trim()

        if (normalized.isEmpty()) {
            return null
        }

        /*
         * الآن / حالا
         *
         * يتم اعتبارها بعد 30 دقيقة.
         */
        if (
            normalized == "الان" ||
            normalized == "حالا" ||
            normalized == "الان." ||
            normalized == "الآن" ||
            normalized == "الان!" ||
            normalized == "now"
        ) {

            val timestamp =
                System.currentTimeMillis() +
                    (30L * 60L * 1000L)

            val format =
                SimpleDateFormat(
                    "hh:mm a",
                    Locale("ar")
                )

            return TimeInfo(
                displayTime =
                    "الآن (${format.format(
                        Date(timestamp)
                    )})",
                timestamp = timestamp
            )
        }

        val relativeDay = when {
            normalized.contains("بعد بكره") || normalized.contains("بعد بكرة") || normalized.contains("بعد غد") -> 2
            normalized.contains("بكره") || normalized.contains("بكرة") || normalized.contains("غدا") || normalized.contains("غداً") -> 1
            else -> null
        }
        if (relativeDay != null) {
            val hour = when {
                normalized.contains("الصباح") || normalized.contains("صباح") -> 8
                normalized.contains("العصر") || normalized.contains("بعد العصر") -> 15
                normalized.contains("المساء") || normalized.contains("مساء") || normalized.contains("الليل") -> 18
                else -> 12
            }
            val calendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, relativeDay)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val timestamp = calendar.timeInMillis
            val display = when (relativeDay) {
                1 -> "غداً"
                else -> "بعد غد"
            } + " " + when (hour) {
                8 -> "صباحاً"
                15 -> "بعد العصر"
                18 -> "مساءً"
                else -> "ظهراً"
            }
            return TimeInfo(displayTime = display, timestamp = timestamp)
        }

        val match =
            TIME_REGEX.find(
                normalized
            ) ?: return null

        var hour =
            match.groupValues
                .getOrNull(1)
                ?.toIntOrNull()
                ?: return null

        val minute =
            match.groupValues
                .getOrNull(2)
                ?.toIntOrNull()
                ?: 0

        val period =
            match.groupValues
                .getOrNull(3)
                ?.trim()
                .orEmpty()

        /*
         * في حالة عدم وجود صباح/مساء،
         * نقبل 0..23.
         */
        if (hour !in 0..23) {
            return null
        }

        if (minute !in 0..59) {
            return null
        }

        val normalizedPeriod =
            period.lowercase(
                Locale.ROOT
            )

        val isPm =
            normalizedPeriod == "م" ||
                normalizedPeriod.contains(
                    "مساء"
                ) ||
                normalizedPeriod == "pm"

        val isAm =
            normalizedPeriod == "ص" ||
                normalizedPeriod.contains(
                    "صباح"
                ) ||
                normalizedPeriod == "am"

        /*
         * مع ص/م:
         *
         * 1..12 فقط.
         */
        if (
            isAm ||
            isPm
        ) {

            if (hour !in 1..12) {
                return null
            }

            if (
                isPm &&
                hour != 12
            ) {
                hour += 12
            }

            if (
                isAm &&
                hour == 12
            ) {
                hour = 0
            }
        }

        val now =
            Calendar.getInstance()

        val cal =
            Calendar.getInstance()

        cal.set(
            Calendar.HOUR_OF_DAY,
            hour
        )

        cal.set(
            Calendar.MINUTE,
            minute
        )

        cal.set(
            Calendar.SECOND,
            0
        )

        cal.set(
            Calendar.MILLISECOND,
            0
        )

        /*
         * إذا كان الوقت قد مضى اليوم،
         * نعتبره غدًا.
         */
        if (
            cal.timeInMillis <=
            now.timeInMillis
        ) {
            cal.add(
                Calendar.DAY_OF_MONTH,
                1
            )
        }

        /*
         * العرض:
         *
         * إذا كتب المستخدم 18:30
         * نعرض 6:30 م.
         *
         * إذا كتب 6:30 مساء
         * نعرض 6:30 م.
         */
        val originalHour =
            match.groupValues
                .getOrNull(1)
                ?.toIntOrNull()
                ?: hour

        val displayHour =
            if (
                isAm ||
                isPm
            ) {

                originalHour

            } else {

                when {

                    hour == 0 ->
                        12

                    hour > 12 ->
                        hour - 12

                    else ->
                        hour
                }
            }

        val displayPeriod =
            when {

                isAm ->
                    "ص"

                isPm ->
                    "م"

                hour < 12 ->
                    "ص"

                else ->
                    "م"
            }

        val display =
            "$displayHour:${String.format(
                Locale.US,
                "%02d",
                minute
            )} $displayPeriod"

        return TimeInfo(
            displayTime = display,
            timestamp = cal.timeInMillis
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Amount Parsing
    // ═══════════════════════════════════════════════════════════════

    fun extractAmount(
        msgBody: String
    ): Double {

        val normalized =
            normalizeNumberText(msgBody)

        if (normalized.isEmpty()) {
            return 0.0
        }

        /*
         * أولًا:
         * ابحث عن مبلغ مرتبط بكلمة ريال.
         */
        val amountMatch =
            AMOUNT_REGEX.find(
                normalized
            )

        if (amountMatch != null) {

            return parseNumber(
                amountMatch
                    .groupValues
                    .getOrNull(1)
            )
        }

        /*
         * fallback:
         *
         * "دفع 5000"
         * "سدد 1000"
         *
         * ولكن نأخذ أول رقم واضح.
         */
        val fallback =
            GENERIC_NUMBER_REGEX.find(
                normalized
            )

        return parseNumber(
            fallback?.value
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Recurring Schedule
    // ═══════════════════════════════════════════════════════════════

    fun parseRecurringSchedule(
        msgBody: String
    ): Pair<String, String>? {

        val normalized =
            normalizeText(msgBody)

        val match =
            RECURRING_REGEX.find(
                normalized
            ) ?: return null

        val period =
            when (
                match.groupValues
                    .getOrNull(1)
                    .orEmpty()
            ) {

                "اسبوع" ->
                    "أسبوع"

                else ->
                    match.groupValues[1]
            }

        val day =
            match.groupValues
                .getOrNull(2)
                ?.trim()
                .orEmpty()

        /*
         * "كل يوم"
         */
        if (
            period == "يوم"
        ) {
            return "يوم" to day
        }

        if (day.isEmpty()) {
            return null
        }

        return period to day
    }

    // ═══════════════════════════════════════════════════════════════
    // Text Normalization
    // ═══════════════════════════════════════════════════════════════

    /**
     * Normalizes text for intent matching.
     *
     * This is an instance method because normalizeText() is an instance
     * helper and is not part of the companion object.
     */
    fun normalizeForMatching(value: String?): String {
        return normalizeText(value)
    }

    private fun normalizeText(
        value: String?
    ): String {

        if (value.isNullOrBlank()) {
            return ""
        }

        var result =
            value
                .lowercase(
                    Locale.ROOT
                )
                .trim()

        /*
         * الأرقام العربية والهندية.
         */
        result =
            normalizeArabicDigits(
                result
            )

        /*
         * إزالة التشكيل.
         */
        result =
            ARABIC_DIACRITICS_REGEX
                .replace(
                    result,
                    ""
                )

        /*
         * توحيد الحروف العربية.
         */
        result =
            result
                .replace(
                    'أ',
                    'ا'
                )
                .replace(
                    'إ',
                    'ا'
                )
                .replace(
                    'آ',
                    'ا'
                )
                .replace(
                    'ٱ',
                    'ا'
                )
                .replace(
                    'ى',
                    'ي'
                )
                .replace(
                    'ؤ',
                    'و'
                )
                .replace(
                    'ئ',
                    'ي'
                )

        /*
         * توحيد بعض الرموز الشائعة.
         */
        result =
            result
                .replace(
                    '\u00A0',
                    ' '
                )
                .replace(
                    '\u2007',
                    ' '
                )
                .replace(
                    '\u202F',
                    ' '
                )

        /*
         * النص العام:
         *
         * نحذف علامات الترقيم.
         *
         * هذا مناسب للتصنيف العام.
         */
        result =
            TEXT_PUNCTUATION_REGEX
                .replace(
                    result,
                    " "
                )

        /*
         * إزالة أي مسافات زائدة.
         */
        result =
            MULTIPLE_SPACES_REGEX
                .replace(
                    result,
                    " "
                )

        return result.trim()
    }

    /**
     * تطبيع خاص بالأرقام والأوقات.
     *
     * الفرق عن normalizeText():
     *
     * لا نحذف:
     *
     * .
     * ,
     * :
     *
     * حتى لا نفسد:
     *
     * 12.5 لتر
     * 12,5 لتر
     * 5:30 مساء
     */
    private fun normalizeNumberText(
        value: String?
    ): String {

        if (value.isNullOrBlank()) {
            return ""
        }

        var result =
            value
                .lowercase(
                    Locale.ROOT
                )
                .trim()

        result =
            normalizeArabicDigits(
                result
            )

        result =
            ARABIC_DIACRITICS_REGEX
                .replace(
                    result,
                    ""
                )

        result =
            result
                .replace(
                    'أ',
                    'ا'
                )
                .replace(
                    'إ',
                    'ا'
                )
                .replace(
                    'آ',
                    'ا'
                )
                .replace(
                    'ٱ',
                    'ا'
                )
                .replace(
                    'ى',
                    'ي'
                )
                .replace(
                    'ؤ',
                    'و'
                )
                .replace(
                    'ئ',
                    'ي'
                )

        /*
         * الفاصلة العربية في السياق الرقمي.
         */
        result =
            result.replace(
                '،',
                ','
            )

        result =
            NUMBER_TEXT_PUNCTUATION_REGEX
                .replace(
                    result,
                    " "
                )

        /*
         * توحيد الفاصلة العشرية:
         *
         * 12,5 -> 12.5
         *
         * فقط عندما تكون بين رقمين.
         */
        result =
            result.replace(
                Regex(
                    """(?<=\d),(?=\d)"""
                ),
                "."
            )

        /*
         * تحويل الفواصل غير الرقمية المتبقية
         * إلى مسافات، مع الحفاظ على:
         *
         * .
         * :
         */
        result =
            result
                .replace(
                    ',',
                    ' '
                )
                .replace(
                    ';',
                    ' '
                )
                .replace(
                    '؛',
                    ' '
                )
                .replace(
                    '!',
                    ' '
                )
                .replace(
                    '؟',
                    ' '
                )
                .replace(
                    '?',
                    ' '
                )

        result =
            MULTIPLE_SPACES_REGEX
                .replace(
                    result,
                    " "
                )

        return result.trim()
    }

    // ═══════════════════════════════════════════════════════════════
    // Arabic Digit Normalization
    // ═══════════════════════════════════════════════════════════════

    private fun normalizeArabicDigits(
        value: String
    ): String {

        val builder =
            StringBuilder(
                value.length
            )

        for (char in value) {

            val indicIndex =
                ARABIC_INDIC_DIGITS.indexOf(
                    char
                )

            if (indicIndex >= 0) {

                builder.append(
                    indicIndex
                )

                continue
            }

            val easternIndex =
                ARABIC_EASTERN_DIGITS.indexOf(
                    char
                )

            if (easternIndex >= 0) {

                builder.append(
                    easternIndex
                )

                continue
            }

            builder.append(
                char
            )
        }

        return builder.toString()
    }

    // ═══════════════════════════════════════════════════════════════
    // Number Parsing
    // ═══════════════════════════════════════════════════════════════

    private fun parseNumber(
        value: String?
    ): Double {

        if (
            value.isNullOrBlank()
        ) {
            return 0.0
        }

        var normalized =
            value.trim()

        /*
         * في حال وجود فاصلة عشرية.
         */
        normalized =
            normalized.replace(
                ',',
                '.'
            )

        /*
         * حماية إضافية من أكثر من نقطة.
         */
        if (
            normalized.count {
                it == '.'
            } > 1
        ) {
            return 0.0
        }

        return normalized
            .toDoubleOrNull()
            ?: 0.0
    }

    // ═══════════════════════════════════════════════════════════════
    // Common Message Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun isConfirmMessage(
        msg: String
    ): Boolean {

        return msg == "تأكيد" ||
            msg == "تاكيد" ||
            msg == "نعم" ||
            msg == "موافق" ||
            msg == "تم" ||
            msg == "confirm" ||
            msg == "yes"
    }

    private fun isCancelMessage(
        msg: String
    ): Boolean {

        /*
         * بعد normalizeText():
         *
         * إلغاء -> الغاء
         * ألغي  -> الغي
         *
         * لذلك نعتمد الشكل المطبع.
         */
        return msg == "الغاء" ||
            msg == "الغي" ||
            msg == "الغى" ||
            msg == "لا" ||
            msg == "cancel" ||
            msg == "no"
    }

    // ═══════════════════════════════════════════════════════════════
    // Obvious Commands
    // ═══════════════════════════════════════════════════════════════

    private fun containsObviousCommand(
        msg: String
    ): Boolean {

        val commands =
            listOf(
                "اريد ديزل",
                "طلب ديزل",
                "اريد بنزين",
                "طلب بنزين",
                "رصيد",
                "عروض",
                "سعر",
                "فاتورة",
                "مساعدة",
                "استعلام",
                "حالة الطلب",
                "تتبع",
                "اتصال",
                "طوارئ",
                "شكوى",
                "الغاء",
                "الغي",
                "تاكيد",
                "تأكيد"
            )

        return commands.any {
            msg.contains(it)
        }
    }
}