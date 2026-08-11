package com.aistudio.dieselstationsms.kxmpzq.sms

import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════
 * كاشف النية (Intent Detector) مع Confidence Score
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. اكتشاف نية الرسالة بدقة
 * 2. حساب Confidence Score لكل نية
 * 3. اختيار النية الأعلى درجة
 * 4. Compiled Regex patterns للأداء
 * 5. دعم وحدات القياس: لتر / دباب / برميل
 */
class SmsIntentDetector {

    companion object {
        private val QUANTITY_REGEX = Regex("""^\d+.*""")
        private val NUMBER_ONLY_REGEX = Regex("""^(\d{1,5})$""")
        private val DABBA_REGEX = Regex("""(\d{1,5})\s*(?:دباب|دبابات|دبة|دبات)""")
        private val LITER_REGEX = Regex("""(\d{1,5}(?:\.\d{1,2})?)\s*(?:لتر|ltr|L|liter)?""")
        private val BARREL_REGEX = Regex("""(\d{1,5})\s*(?:برميل|براميل|طن|طن)""")
        private val TIME_REGEX = Regex("""(\d{1,2})[:.]?(\d{2})?\s*(ص|صباح|am|م|مساء|pm)?""")
        private val RATING_REGEX = Regex("""^[1-5]$""")
        private val AMOUNT_REGEX = Regex("""(\d{1,10}(?:\.\d{1,2})?)\s*(?:ريال|riyal|ry|YER)?""")
        private val RECURRING_REGEX = Regex("""كل\s+(يوم|أسبوع|شهر)\s+([\w]+)""")
        private val QUANTITY_RESPONSE_REGEX = Regex("""^\d+\s*(?:دباب|دبابات|دبة|دبات|لتر|ltr|L|برميل|براميل)?\s*$""", RegexOption.IGNORE_CASE)

        // ✅ Word boundary patterns
        private val YES_PATTERN = Regex("""\b(نعم|yes|موافق|أوافق|أوكي|ok)\b""")
        private val NO_PATTERN = Regex("""\b(لا|no|رفض)\b""")
        private val CANCEL_PATTERN = Regex("""\b(إلغاء|الغاء|cancel)\b""")

        // ✅ Constants for units
        const val LITER_PER_DABBA = 20.0
        const val LITER_PER_BARREL = 200.0

        // ✅ Messages
        const val MSG_CLARIFY_UNIT = " ماذا؟ يرجى تحديد الطلب (لتر & دباب & براميل)"
        const val MSG_SMALL_QUANTITY_DELIVERY = "سعر الـ %s ديزل هو %s ريال، وللحصول على طلبك هذا يرجى حضورك للمحطة، فلا يمكن توصيل هذه الكمية"
        const val MIN_DELIVERY_LITERS = 100.0  // أقل كمية للتوصيل
    }

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

    /**
     * ✅ نتيجة تحليل الكمية مع معلومات الوحدة
     */
    data class QuantityInfo(
        val liters: Double,
        val dabbas: Double,
        val barrels: Double,
        val isDabba: Boolean,
        val isBarrel: Boolean,
        val isLiter: Boolean,
        val rawValue: Double,
        val unit: String,
        val needsClarification: Boolean  // true إذا كان الرقم فقط بدون وحدة
    )

    fun detectIntent(msgBody: String, ctx: ConversationState, sender: String): IntentResult {
        val normalized = msgBody.lowercase(Locale.getDefault()).trim()
        val scores = mutableMapOf<String, Int>()

        // ✅ التحقق من انتهاء صلاحية السياق (5 دقائق)
        if (ctx.awaitingResponse && System.currentTimeMillis() - ctx.timestamp < 300000L) {
            val contextResult = detectContextIntent(normalized, ctx)
            if (contextResult != null) {
                return contextResult
            }
        }

        // ✅ اكتشاف الطلب المشترك أولاً
        if (normalized.contains("ديزل") && normalized.contains("بنزين")) {
            scores["mixed_fuel_request"] = 95
            return IntentResult("mixed_fuel_request", 95, scores)
        }

        scores["diesel_request"] = scoreDieselRequest(normalized)
        scores["gasoline_request"] = scoreGasolineRequest(normalized)
        scores["quantity_response"] = scoreQuantityResponse(normalized, ctx)
        scores["confirm_order"] = scoreConfirmOrder(normalized, ctx)
        scores["cancel_order"] = scoreCancelOrder(normalized)
        scores["balance_query"] = scoreBalanceQuery(normalized)
        scores["payment_request"] = scorePaymentRequest(normalized)
        scores["transfer_request"] = scoreTransferRequest(normalized)
        scores["offers_query"] = scoreOffersQuery(normalized)
        scores["price_query"] = scorePriceQuery(normalized)
        scores["loyalty_query"] = scoreLoyaltyQuery(normalized)
        scores["redeem_points"] = scoreRedeemPoints(normalized)
        scores["track_order"] = scoreTrackOrder(normalized)
        scores["order_history"] = scoreOrderHistory(normalized)
        scores["help"] = scoreHelp(normalized)
        scores["complaint"] = scoreComplaint(normalized)
        scores["emergency"] = scoreEmergency(normalized)
        scores["callback_request"] = scoreCallbackRequest(normalized)
        scores["location_query"] = scoreLocationQuery(normalized)
        scores["working_hours"] = scoreWorkingHours(normalized)
        scores["invoice_request"] = scoreInvoiceRequest(normalized)
        scores["weekly_report"] = scoreWeeklyReport(normalized)
        scores["schedule_appointment"] = scoreScheduleAppointment(normalized)
        scores["schedule_recurring"] = scoreRecurringSchedule(normalized)
        scores["rating"] = scoreRating(normalized, ctx)
        scores["greeting"] = scoreGreeting(normalized)
        scores["thanks"] = scoreThanks(normalized)

        val bestIntent = scores.maxByOrNull { it.value }
            ?: return IntentResult("unknown", 0, scores)

        if (bestIntent.value < 30) {
            return IntentResult("unknown", bestIntent.value, scores)
        }

        return IntentResult(bestIntent.key, bestIntent.value, scores)
    }

    private fun detectContextIntent(normalized: String, ctx: ConversationState): IntentResult? {
        return when (ctx.pendingAction) {
            "awaiting_quantity", "awaiting_quantity_gasoline" -> {
                when {
                    QUANTITY_RESPONSE_REGEX.matches(normalized) ->
                        IntentResult("quantity_response", 95)
                    QUANTITY_REGEX.matches(normalized) ->
                        IntentResult("quantity_response", 80)
                    else -> null
                }
            }
            "awaiting_location" -> {
                when {
                    normalized.length in 3..200 &&
                            !CANCEL_PATTERN.containsMatchIn(normalized) ->
                        IntentResult("location_response", 90)
                    else -> null
                }
            }
            "awaiting_time" -> {
                when {
                    normalized.contains(":") ||
                            normalized.contains("ص") || normalized.contains("م") ||
                            normalized.contains("الآن") || normalized.contains("now") ||
                            normalized.contains("حالا") ||
                            normalized.contains("am") || normalized.contains("pm") ->
                        IntentResult("time_response", 92)
                    else -> null
                }
            }
            "awaiting_confirmation" -> {
                when {
                    YES_PATTERN.containsMatchIn(normalized) ->
                        IntentResult("confirm_order", 95)
                    NO_PATTERN.containsMatchIn(normalized) || 
                    CANCEL_PATTERN.containsMatchIn(normalized) ->
                        IntentResult("cancel_order", 95)
                    else -> null
                }
            }
            "awaiting_rating" -> {
                when {
                    RATING_REGEX.matches(normalized) ->
                        IntentResult("rating", 95)
                    else -> null
                }
            }
            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ دوال التقييم (Scoring) ═══
    // ═══════════════════════════════════════════════════════════════

    private fun scoreDieselRequest(msg: String): Int {
        var score = 0
        if (msg.contains("اريد ديزل")) score += 100
        if (msg.contains("طلب ديزل")) score += 95
        if (msg.contains("diesel")) score += 90
        if (msg.contains("ديزل") && !msg.contains("بنزين")) score += 85
        if (msg.contains("توريد ديزل")) score += 80
        if (msg.contains("محروقات") && msg.contains("ثقيل")) score += 60
        return score.coerceAtMost(100)
    }

    private fun scoreGasolineRequest(msg: String): Int {
        var score = 0
        if (msg.contains("اريد بنزين")) score += 100
        if (msg.contains("بنزين")) score += 90
        if (msg.contains("gasoline")) score += 90
        if (msg.contains("petrol")) score += 85
        if (msg.contains("توريد بنزين")) score += 80
        return score.coerceAtMost(100)
    }

    private fun scoreQuantityResponse(msg: String, ctx: ConversationState): Int {
        var score = 0
        if (NUMBER_ONLY_REGEX.matches(msg)) score += 90
        if (DABBA_REGEX.containsMatchIn(msg)) score += 85
        if (LITER_REGEX.containsMatchIn(msg)) score += 85
        if (BARREL_REGEX.containsMatchIn(msg)) score += 85
        if (msg.contains("دباب") || msg.contains("دبة")) score += 80
        if (msg.contains("لتر")) score += 75
        if (msg.contains("برميل") || msg.contains("براميل")) score += 80
        // ✅ Boost if awaiting quantity
        if (ctx.pendingAction.startsWith("awaiting_quantity")) score += 10
        return score.coerceAtMost(100)
    }

    private fun scoreConfirmOrder(msg: String, ctx: ConversationState): Int {
        var score = 0
        if (msg == "تأكيد") score += 100
        if (CANCEL_PATTERN.containsMatchIn(msg)) score -= 50
        if (YES_PATTERN.containsMatchIn(msg)) score += 90
        if (msg.contains("confirm")) score += 85
        if (ctx.pendingAction == "awaiting_confirmation") score += 15
        return score.coerceAtMost(100)
    }

    private fun scoreCancelOrder(msg: String): Int {
        var score = 0
        if (msg == "إلغاء" || msg == "الغاء") score += 100
        if (CANCEL_PATTERN.containsMatchIn(msg)) score += 95
        if (NO_PATTERN.containsMatchIn(msg)) score += 80
        if (msg.contains("ألغي")) score += 80
        return score.coerceAtMost(100)
    }

    private fun scoreBalanceQuery(msg: String): Int {
        var score = 0
        if (msg == "رصيد") score += 100
        if (msg.contains("رصيد")) score += 90
        if (msg.contains("حساب")) score += 85
        if (msg.contains("balance")) score += 80
        if (msg.contains("كم علي")) score += 75
        if (msg.contains("كم لي")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scorePaymentRequest(msg: String): Int {
        var score = 0
        if (msg.contains("دفع")) score += 90
        if (msg.contains("تسديد")) score += 90
        if (msg.contains("سداد")) score += 85
        if (msg.contains("pay")) score += 80
        if (msg.contains("payment")) score += 75
        if (msg.contains("سدد")) score += 80
        return score.coerceAtMost(100)
    }

    private fun scoreTransferRequest(msg: String): Int {
        var score = 0
        if (msg.contains("تحويل")) score += 90
        if (msg.contains("transfer")) score += 85
        if (msg.contains("بنكي")) score += 80
        if (msg.contains("bank")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scoreOffersQuery(msg: String): Int {
        var score = 0
        if (msg.contains("عروض")) score += 95
        if (msg.contains("offer")) score += 85
        if (msg.contains("تخفيض")) score += 70
        return score.coerceAtMost(100)
    }

    private fun scorePriceQuery(msg: String): Int {
        var score = 0
        if (msg.contains("سعر")) score += 90
        if (msg.contains("price")) score += 80
        if (msg.contains("كم سعر")) score += 85
        if (msg.contains("بكم")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scoreLoyaltyQuery(msg: String): Int {
        var score = 0
        if (msg.contains("نقاط")) score += 90
        if (msg.contains("ولاء")) score += 90
        if (msg.contains("points")) score += 80
        if (msg.contains("loyalty")) score += 80
        if (msg.contains("مكافآت")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scoreRedeemPoints(msg: String): Int {
        var score = 0
        if (msg.contains("استبدال")) score += 90
        if (msg.contains("redeem")) score += 85
        if (msg.contains("صرف نقاط")) score += 80
        return score.coerceAtMost(100)
    }

    private fun scoreTrackOrder(msg: String): Int {
        var score = 0
        if (msg.contains("حالة")) score += 90
        if (msg.contains("تتبع")) score += 90
        if (msg.contains("track")) score += 80
        if (msg.contains("order status")) score += 85
        if (msg.contains("طلبي")) score += 85
        if (msg.contains("وين الطلب")) score += 80
        return score.coerceAtMost(100)
    }

    private fun scoreOrderHistory(msg: String): Int {
        var score = 0
        if (msg.contains("سجل")) score += 90
        if (msg.contains("تاريخ")) score += 85
        if (msg.contains("history")) score += 80
        if (msg.contains("طلباتي")) score += 85
        if (msg.contains("فواتيري")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scoreHelp(msg: String): Int {
        var score = 0
        if (msg == "استعلام" || msg == "مساعدة") score += 100
        if (msg.contains("استعلام")) score += 90
        if (msg.contains("help")) score += 85
        if (msg.contains("مساعدة")) score += 85
        if (msg.contains("?") || msg.contains("؟")) score += 70
        if (msg.contains("قائمة")) score += 80
        if (msg.contains("menu")) score += 75
        if (msg.contains("خدمات")) score += 80
        return score.coerceAtMost(100)
    }

    private fun scoreComplaint(msg: String): Int {
        var score = 0
        if (msg.contains("شكوى")) score += 95
        if (msg.contains("complaint")) score += 85
        if (msg.contains("مشكلة")) score += 80
        if (msg.contains("problem")) score += 75
        if (msg.contains("سيء")) score += 60
        return score.coerceAtMost(100)
    }

    private fun scoreEmergency(msg: String): Int {
        var score = 0
        if (msg.contains("طوارئ")) score += 95
        if (msg.contains("urgent")) score += 85
        if (msg.contains("emergency")) score += 85
        if (msg.contains("عاجل")) score += 90
        if (msg.contains("ضروري")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scoreCallbackRequest(msg: String): Int {
        var score = 0
        if (msg.contains("اتصال")) score += 90
        if (msg.contains("اتصلوا")) score += 90
        if (msg.contains("callback")) score += 85
        if (msg.contains("كلموني")) score += 85
        if (msg.contains("اتصل")) score += 80
        return score.coerceAtMost(100)
    }

    private fun scoreLocationQuery(msg: String): Int {
        var score = 0
        if (msg.contains("موقع")) score += 90
        if (msg.contains("location")) score += 80
        if (msg.contains("عنوان")) score += 85
        if (msg.contains("خريطة")) score += 75
        if (msg.contains("وين المحطة")) score += 80
        return score.coerceAtMost(100)
    }

    private fun scoreWorkingHours(msg: String): Int {
        var score = 0
        if (msg.contains("ساعات")) score += 90
        if (msg.contains("مواعيد")) score += 85
        if (msg.contains("hours")) score += 80
        if (msg.contains("متى تفتح")) score += 90
        if (msg.contains("متى تسكر")) score += 85
        return score.coerceAtMost(100)
    }

    private fun scoreInvoiceRequest(msg: String): Int {
        var score = 0
        if (msg.contains("فاتورة")) score += 95
        if (msg.contains("invoice")) score += 85
        if (msg.contains("bill")) score += 80
        if (msg.contains("فواتير")) score += 85
        return score.coerceAtMost(100)
    }

    private fun scoreWeeklyReport(msg: String): Int {
        var score = 0
        if (msg.contains("تقرير")) score += 90
        if (msg.contains("report")) score += 80
        if (msg.contains("weekly")) score += 85
        if (msg.contains("ملخص")) score += 85
        if (msg.contains("إحصائيات")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scoreScheduleAppointment(msg: String): Int {
        var score = 0
        if (msg.contains("حجز")) score += 90
        if (msg.contains("موعد")) score += 85
        if (msg.contains("appointment")) score += 80
        if (msg.contains("booking")) score += 80
        if (msg.contains("جدولة")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scoreRecurringSchedule(msg: String): Int {
        var score = 0
        if (RECURRING_REGEX.containsMatchIn(msg)) score += 95
        if (msg.contains("كل يوم")) score += 90
        if (msg.contains("كل أسبوع")) score += 90
        if (msg.contains("كل شهر")) score += 90
        return score.coerceAtMost(100)
    }

    private fun scoreRating(msg: String, ctx: ConversationState): Int {
        var score = 0
        if (ctx.pendingAction == "awaiting_rating" && RATING_REGEX.matches(msg)) score += 95
        if (msg.contains("تقييم")) score += 85
        if (msg.contains("rating")) score += 80
        if (msg.contains("rate")) score += 75
        if (msg.contains("نجمة") || msg.contains("نجوم")) score += 70
        return score.coerceAtMost(100)
    }

    private fun scoreGreeting(msg: String): Int {
        var score = 0
        if (msg.contains("مرحب")) score += 90
        if (msg.contains("hello")) score += 85
        if (msg.contains("hi")) score += 80
        if (msg.contains("صباح")) score += 85
        if (msg.contains("مساء")) score += 85
        if (msg.contains("اهلا") || msg.contains("أهلا")) score += 90
        if (msg.contains("السلام")) score += 85
        if (msg.contains("تحية")) score += 70
        return score.coerceAtMost(100)
    }

    private fun scoreThanks(msg: String): Int {
        var score = 0
        if (msg.contains("شكر")) score += 95
        if (msg.contains("thanks")) score += 85
        if (msg.contains("thank you")) score += 80
        if (msg.contains("مشكور")) score += 90
        if (msg.contains("تسلم")) score += 75
        return score.coerceAtMost(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ أدوات تحليل النصوص ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * ✅ تحليل الكمية مع دعم الوحدات: لتر / دباب / برميل
     * 
     * إذا أرسل المستخدم رقم فقط (مثل "5")، يُرجع needsClarification = true
     * ويجب على المعالج إرسال رسالة توضيحية
     */
    fun parseQuantity(msgBody: String, expectedUnit: String = "auto"): QuantityInfo {
        val normalized = msgBody.trim().lowercase(Locale.getDefault())

        // ✅ التحقق من برميل أولاً
        val barrelMatch = BARREL_REGEX.find(normalized)
        if (barrelMatch != null) {
            val barrels = barrelMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val liters = barrels * LITER_PER_BARREL
            return QuantityInfo(
                liters = liters,
                dabbas = liters / LITER_PER_DABBA,
                barrels = barrels,
                isDabba = false,
                isBarrel = true,
                isLiter = false,
                rawValue = barrels,
                unit = "برميل",
                needsClarification = false
            )
        }

        // ✅ التحقق من دباب
        val dabbaMatch = DABBA_REGEX.find(normalized)
        if (dabbaMatch != null) {
            val dabbas = dabbaMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val liters = dabbas * LITER_PER_DABBA
            return QuantityInfo(
                liters = liters,
                dabbas = dabbas,
                barrels = liters / LITER_PER_BARREL,
                isDabba = true,
                isBarrel = false,
                isLiter = false,
                rawValue = dabbas,
                unit = "دباب",
                needsClarification = false
            )
        }

        // ✅ التحقق من لتر
        val literMatch = LITER_REGEX.find(normalized)
        if (literMatch != null) {
            val liters = literMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            return QuantityInfo(
                liters = liters,
                dabbas = liters / LITER_PER_DABBA,
                barrels = liters / LITER_PER_BARREL,
                isDabba = false,
                isBarrel = false,
                isLiter = true,
                rawValue = liters,
                unit = "لتر",
                needsClarification = false
            )
        }

        // ✅ إذا كان رقم فقط - يحتاج توضيح
        val numberOnly = NUMBER_ONLY_REGEX.find(normalized)
        if (numberOnly != null) {
            val value = numberOnly.groupValues[1].toDouble()
            return QuantityInfo(
                liters = 0.0,
                dabbas = 0.0,
                barrels = 0.0,
                isDabba = false,
                isBarrel = false,
                isLiter = false,
                rawValue = value,
                unit = "",
                needsClarification = true  // ✅ يحتاج توضيح الوحدة
            )
        }

        return QuantityInfo(0.0, 0.0, 0.0, false, false, false, 0.0, "", false)
    }

    /**
     * ✅ إنشاء رسالة توضيحية للوحدة
     */
    fun buildClarificationMessage(rawValue: Double): String {
        return "${rawValue.toInt()} $MSG_CLARIFY_UNIT"
    }

    /**
     * ✅ إنشاء رسالة الكمية الصغيرة
     */
    fun buildSmallQuantityMessage(quantityInfo: QuantityInfo, pricePerLiter: Double): String {
        val totalPrice = quantityInfo.liters * pricePerLiter
        val quantityStr = when {
            quantityInfo.isLiter -> "${quantityInfo.rawValue} لتر"
            quantityInfo.isDabba -> "${quantityInfo.rawValue} دباب"
            quantityInfo.isBarrel -> "${quantityInfo.rawValue} برميل"
            else -> "${quantityInfo.liters} لتر"
        }
        return MSG_SMALL_QUANTITY_DELIVERY.format(quantityStr, String.format("%.2f", totalPrice))
    }

    /**
     * ✅ التحقق مما إذا كانت الكمية تصلح للتوصيل
     */
    fun isDeliverable(quantityInfo: QuantityInfo): Boolean {
        return quantityInfo.liters >= MIN_DELIVERY_LITERS
    }

    data class TimeInfo(val displayTime: String, val timestamp: Long)

    fun parseDeliveryTime(msgBody: String): TimeInfo? {
        val normalized = msgBody.trim().lowercase(Locale.getDefault())

        if (normalized.contains("الآن") || normalized.contains("now") || normalized.contains("حالا")) {
            val now = System.currentTimeMillis() + (30 * 60 * 1000)
            val timeFormat = java.text.SimpleDateFormat("hh:mm a", Locale("ar"))
            return TimeInfo("الآن (${timeFormat.format(java.util.Date(now))})", now)
        }

        val match = TIME_REGEX.find(normalized)
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

            // ✅ حد أدنى 1 ساعة
            if (cal.timeInMillis < System.currentTimeMillis() + 3600000L) {
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

    fun extractAmount(msgBody: String): Double {
        val match = AMOUNT_REGEX.find(msgBody)
        return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    fun parseRecurringSchedule(msgBody: String): Pair<String, String>? {
        val match = RECURRING_REGEX.find(msgBody)
        return if (match != null) {
            match.groupValues[1] to match.groupValues[2]
        } else null
    }
}