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
 */
class SmsIntentDetector {

    companion object {
        private val QUANTITY_REGEX = Regex("""^\d+.*""")
        private val NUMBER_ONLY_REGEX = Regex("""^(\d{1,5})$""")
        private val DABBA_REGEX = Regex("""(\d{1,5})\s*(?:دباب|دبابات|دبة|دبات)""")
        private val LITER_REGEX = Regex("""(\d{1,5}(?:\.\d{1,2})?)\s*(?:لتر|ltr|L|liter)?""")
        private val TIME_REGEX = Regex("""(\d{1,2})[:.]?(\d{2})?\s*(ص|صباح|am|م|مساء|pm)?""")
        private val RATING_REGEX = Regex("""^[1-5]$""")
        private val AMOUNT_REGEX = Regex("""(\d{1,10}(?:\.\d{1,2})?)\s*(?:ريال|riyal|ry|YER)?""")
        private val RECURRING_REGEX = Regex("""كل\s+(يوم|أسبوع|شهر)\s+([\w]+)""")
        private val QUANTITY_RESPONSE_REGEX = Regex("""^\d+\s*(?:دباب|دبابات|دبة|دبات|لتر|ltr|L)?\s*$""", RegexOption.IGNORE_CASE)
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

    fun detectIntent(msgBody: String, ctx: ConversationState, sender: String): IntentResult {
        val normalized = msgBody.lowercase(Locale.getDefault()).trim()
        val scores = mutableMapOf<String, Int>()

        if (ctx.awaitingResponse) {
            val contextResult = detectContextIntent(normalized, ctx)
            if (contextResult != null) {
                return contextResult
            }
        }

        scores["diesel_request"] = scoreDieselRequest(normalized)
        scores["gasoline_request"] = scoreGasolineRequest(normalized)
        scores["quantity_response"] = scoreQuantityResponse(normalized)
        scores["confirm_order"] = scoreConfirmOrder(normalized)
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
        scores["rating"] = scoreRating(normalized)
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
                            !normalized.contains("إلغاء") &&
                            !normalized.contains("cancel") ->
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
                    normalized.contains("تأكيد") || normalized.contains("confirm") ||
                            normalized.contains("نعم") || normalized.contains("yes") ->
                        IntentResult("confirm_order", 95)
                    normalized.contains("إلغاء") || normalized.contains("cancel") ||
                            normalized.contains("لا") || normalized.contains("no") ->
                        IntentResult("cancel_order", 95)
                    else -> null
                }
            }
            else -> null
        }
    }

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

    private fun scoreQuantityResponse(msg: String): Int {
        var score = 0
        if (NUMBER_ONLY_REGEX.matches(msg)) score += 90
        if (DABBA_REGEX.containsMatchIn(msg)) score += 85
        if (LITER_REGEX.containsMatchIn(msg)) score += 85
        if (msg.contains("دباب") || msg.contains("دبة")) score += 80
        if (msg.contains("لتر")) score += 75
        return score.coerceAtMost(100)
    }

    private fun scoreConfirmOrder(msg: String): Int {
        var score = 0
        if (msg == "تأكيد") score += 100
        if (msg.contains("تأكيد")) score += 90
        if (msg.contains("confirm")) score += 85
        if (msg.contains("نعم") && msg.length < 10) score += 80
        if (msg.contains("yes")) score += 75
        if (msg.contains("موافق")) score += 70
        return score.coerceAtMost(100)
    }

    private fun scoreCancelOrder(msg: String): Int {
        var score = 0
        if (msg == "إلغاء" || msg == "الغاء") score += 100
        if (msg.contains("إلغاء") || msg.contains("الغاء")) score += 95
        if (msg.contains("cancel")) score += 90
        if (msg.contains("لا") && msg.length < 5) score += 75
        if (msg.contains("no")) score += 70
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
        if (msg.contains("سعر")) score += 80
        if (msg.contains("price")) score += 75
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

    private fun scoreRating(msg: String): Int {
        var score = 0
        if (RATING_REGEX.matches(msg)) score += 95
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

    data class QuantityInfo(val liters: Double, val dabbas: Double, val isDabba: Boolean)

    fun parseQuantity(msgBody: String): QuantityInfo {
        val normalized = msgBody.trim().lowercase(Locale.getDefault())

        val dabbaMatch = DABBA_REGEX.find(normalized)
        if (dabbaMatch != null) {
            val dabbas = dabbaMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val liters = dabbas * 20.0
            return QuantityInfo(liters, dabbas, true)
        }

        val literMatch = LITER_REGEX.find(normalized)
        if (literMatch != null) {
            val liters = literMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val dabbas = liters / 20.0
            return QuantityInfo(liters, dabbas, false)
        }

        val numberOnly = NUMBER_ONLY_REGEX.find(normalized)
        if (numberOnly != null) {
            val value = numberOnly.groupValues[1].toDouble()
            return if (value <= 50) {
                QuantityInfo(value * 20.0, value, true)
            } else {
                QuantityInfo(value, value / 20.0, false)
            }
        }

        return QuantityInfo(0.0, 0.0, false)
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