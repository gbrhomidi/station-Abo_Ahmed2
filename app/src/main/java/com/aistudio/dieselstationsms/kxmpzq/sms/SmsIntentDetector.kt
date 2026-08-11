package com.aistudio.dieselstationsms.kxmpzq.sms

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

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

* - 1 دبة = 20 لتر.

* - البرميل العادي = 10 دباب = 200 لتر.

* - البرميل الكبير = 12 دبة = 240 لتر.

* 

* ملاحظة:

* لا يتم تغيير أسماء الـ intents لأنها جزء من عقد التكامل

* مع SmsProcessor.kt.
  */
  class SmsIntentDetector {
  
  companion object {
  
   private const val LITERS_PER_DABBA = 20.0
 private const val ORDINARY_BARREL_DABBAS = 10.0
 private const val LARGE_BARREL_DABBAS = 12.0

 private const val ORDINARY_BARREL_LITERS =
     LITERS_PER_DABBA * ORDINARY_BARREL_DABBAS

 private const val LARGE_BARREL_LITERS =
     LITERS_PER_DABBA * LARGE_BARREL_DABBAS

 private const val MAX_QUANTITY_LITERS = 10000.0
 private const val MIN_LOCATION_LENGTH = 3
 private const val MAX_LOCATION_LENGTH = 200

 private const val UNKNOWN_CONFIDENCE_THRESHOLD = 30

 private val ARABIC_INDIC_DIGITS = charArrayOf(
     '٠', '١', '٢', '٣', '٤',
     '٥', '٦', '٧', '٨', '٩'
 )

 private val ARABIC_EASTERN_DIGITS = charArrayOf(
     '۰', '۱', '۲', '۳', '۴',
     '۵', '۶', '۷', '۸', '۹'
 )

 private val NUMBER_ONLY_REGEX =
     Regex("""^\d{1,6}(?:[.,]\d{1,2})?$""")

 private val DABBA_REGEX = Regex(
     """(\d{1,6}(?:[.,]\d{1,2})?)\s*(?:دبة|دبه|دبات|دباب|دبابات)"""
 )

 private val LITER_REGEX = Regex(
     """(\d{1,7}(?:[.,]\d{1,2})?)\s*(?:لتر|لترات|ltr|liter|liters)\b?""",
     RegexOption.IGNORE_CASE
 )

 private val ORDINARY_BARREL_REGEX = Regex(
     """(\d{1,4}(?:[.,]\d{1,2})?)?\s*(?:برميل|براميل)\s*(?:عادي|العادي)?"""
 )

 private val LARGE_BARREL_REGEX = Regex(
     """(\d{1,4}(?:[.,]\d{1,2})?)?\s*(?:برميل|براميل)\s*(?:كبير|الكبير)"""
 )

 private val TIME_REGEX = Regex(
     """(?:^|\s)(\d{1,2})(?:\s*[:.]\s*(\d{1,2}))?\s*(صباح(?:اً|ا)?|ص|مساء(?:ً|ا)?|م|am|pm)?(?:\s|$)""",
     RegexOption.IGNORE_CASE
 )

 private val RATING_REGEX =
     Regex("""^[1-5]$""")

 private val AMOUNT_REGEX = Regex(
     """(?:^|\D)(\d{1,12}(?:[.,]\d{1,2})?)\s*(?:ريال|ريالاً|رياليات|yer|riyal|ry)?(?:\D|$)""",
     RegexOption.IGNORE_CASE
 )

 private val RECURRING_REGEX = Regex(
     """كل\s+(يوم|أسبوع|اسبوع|شهر)\s*(.*)"""
 )

 private val QUANTITY_RESPONSE_REGEX = Regex(
     """^\s*\d{1,6}(?:[.,]\d{1,2})?\s*(?:دبة|دبه|دبات|دباب|دبابات|لتر|لترات|ltr|liter)?\s*$""",
     RegexOption.IGNORE_CASE
 )

 private val ARABIC_DIACRITICS_REGEX = Regex(
     "[\\u064B-\\u065F\\u0670]"
 )

 private val MULTIPLE_SPACES_REGEX =
     Regex("""\s+""")

 private val PUNCTUATION_REGEX =
     Regex("""[،,:;؛!?؟.!]+""")
  
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
     return IntentResult("unknown", 0)
 }

 /*
  * بعض النوايا حساسة جدًا ولا ينبغي أن تُهزم بنية أخرى
  * بسبب وجود كلمات مشتركة.
  */
 detectHighPriorityIntent(normalized)?.let {
     return it
 }

 /*
  * إذا كانت هناك محادثة معلقة، فإن السياق له الأولوية
  * على التصنيف العام.
  */
 if (ctx.awaitingResponse) {
     val contextResult = detectContextIntent(normalized, ctx)
     if (contextResult != null) {
         return contextResult
     }
 }

 val scores = linkedMapOf<String, Int>()

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

 val bestIntent = scores
     .filterValues { it > 0 }
     .maxByOrNull { it.value }

     ?: return IntentResult(
         intent = "unknown",
         confidence = 0,
         allScores = scores
     )

 if (bestIntent.value < UNKNOWN_CONFIDENCE_THRESHOLD) {
     return IntentResult(
         intent = "unknown",
         confidence = bestIntent.value,
         allScores = scores
     )
 }

 return IntentResult(
     intent = bestIntent.key,
     confidence = bestIntent.value.coerceIn(0, 100),
     allScores = scores
 )
  
  }
  
  private fun detectHighPriorityIntent(
  msg: String
  ): IntentResult? {
  
   if (isCancelMessage(msg)) {
     return IntentResult("cancel_order", 100)
 }

 if (isConfirmMessage(msg)) {
     return IntentResult("confirm_order", 100)
 }

 if (scoreEmergency(msg) >= 95) {
     return IntentResult("emergency", scoreEmergency(msg))
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
  
   return when (ctx.pendingAction) {

     "awaiting_quantity",
     "awaiting_quantity_gasoline" -> {

         when {
             QUANTITY_RESPONSE_REGEX.matches(normalized) ->
                 IntentResult("quantity_response", 98)

             containsQuantityExpression(normalized) ->
                 IntentResult("quantity_response", 92)

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
             IntentResult("location_response", 92)
         } else {
             null
         }
     }

     "awaiting_time" -> {

         when {
             parseDeliveryTime(normalized) != null ->
                 IntentResult("time_response", 96)

             else -> null
         }
     }

     "awaiting_confirmation" -> {

         when {
             isConfirmMessage(normalized) ->
                 IntentResult("confirm_order", 98)

             isCancelMessage(normalized) ->
                 IntentResult("cancel_order", 98)

             else -> null
         }
     }

     else -> null
 }
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Diesel
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreDieselRequest(msg: String): Int {
  
   if (
     msg.contains("اريد ديزل") ||
     msg.contains("أريد ديزل")
 ) {
     return 100
 }

 var score = 0

 if (msg.contains("طلب ديزل")) score += 95
 if (msg.contains("diesel")) score += 90

 if (
     msg.contains("ديزل") &&
     !msg.contains("بنزين")
 ) {
     score += 85
 }

 if (msg.contains("توريد ديزل")) score += 80
 if (msg.contains("محروقات") && msg.contains("ثقيل")) score += 65

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Gasoline
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreGasolineRequest(msg: String): Int {
  
   if (
     msg.contains("اريد بنزين") ||
     msg.contains("أريد بنزين")
 ) {
     return 100
 }

 var score = 0

 if (msg.contains("طلب بنزين")) score += 95
 if (msg.contains("بنزين")) score += 90
 if (msg.contains("gasoline")) score += 90
 if (msg.contains("petrol")) score += 88
 if (msg.contains("توريد بنزين")) score += 85

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Quantity
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreQuantityResponse(msg: String): Int {
  
   if (NUMBER_ONLY_REGEX.matches(msg)) {
     return 88
 }

 var score = 0

 if (DABBA_REGEX.containsMatchIn(msg)) score += 90
 if (LITER_REGEX.containsMatchIn(msg)) score += 90
 if (ORDINARY_BARREL_REGEX.containsMatchIn(msg)) score += 90
 if (LARGE_BARREL_REGEX.containsMatchIn(msg)) score += 95

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
     msg.contains("ltr")
 ) {
     score += 80
 }

 if (
     msg.contains("برميل") ||
     msg.contains("براميل")
 ) {
     score += 85
 }

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Confirmation / Cancellation
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreConfirmOrder(msg: String): Int {
  
   if (msg == "تأكيد" || msg == "تاكيد") {
     return 100
 }

 var score = 0

 if (
     msg.contains("تأكيد") ||
     msg.contains("تاكيد")
 ) {
     score += 92
 }

 if (msg.contains("confirm")) score += 88

 if (
     msg == "نعم" ||
     msg == "نعم موافق" ||
     msg == "موافق" ||
     msg == "تم"
 ) {
     score += 85
 }

 if (msg == "yes") score += 80

 return score.coerceAtMost(100)
  
  }
  
  private fun scoreCancelOrder(msg: String): Int {
  
   if (
     msg == "إلغاء" ||
     msg == "الغاء" ||
     msg == "ألغي" ||
     msg == "الغي"
 ) {
     return 100
 }

 var score = 0

 if (
     msg.contains("إلغاء") ||
     msg.contains("الغاء")
 ) {
     score += 95
 }

 if (
     msg.contains("ألغي") ||
     msg.contains("الغي")
 ) {
     score += 90
 }

 if (msg == "cancel") score += 90

 if (msg == "لا") score += 80
 if (msg == "no") score += 75

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Balance
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreBalanceQuery(msg: String): Int {
  
   if (msg == "رصيد") {
     return 100
 }

 var score = 0

 if (msg.contains("رصيد")) score += 92
 if (msg.contains("حساب")) score += 85
 if (msg.contains("balance")) score += 80
 if (msg.contains("كم علي")) score += 80
 if (msg.contains("كم لي")) score += 80
 if (msg.contains("علي كم")) score += 78
 if (msg.contains("لي كم")) score += 78

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Payment
  // ═══════════════════════════════════════════════════════════════
  
  private fun scorePaymentRequest(msg: String): Int {
  
   var score = 0

 if (msg.contains("دفع")) score += 92
 if (msg.contains("تسديد")) score += 92
 if (msg.contains("سداد")) score += 90
 if (msg.contains("سدد")) score += 85
 if (msg.contains("pay")) score += 80
 if (msg.contains("payment")) score += 78

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Transfer
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreTransferRequest(msg: String): Int {
  
   var score = 0

 if (msg.contains("تحويل")) score += 92
 if (msg.contains("transfer")) score += 88
 if (msg.contains("بنكي")) score += 82
 if (msg.contains("بنكي")) score += 82
 if (msg.contains("bank")) score += 78

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Offers / Prices
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreOffersQuery(msg: String): Int {
  
   var score = 0

 if (
     msg.contains("عروض") ||
     msg.contains("عرض") ||
     msg.contains("تخفيض") ||
     msg.contains("خصم")
 ) {
     score += 95
 }

 if (msg.contains("offer")) score += 88
 if (msg.contains("offers")) score += 88

 /*
  * كلمة "سعر" وحدها ليست عرضًا.
  * لذلك لا نعطيها نقاطًا هنا كما كان في النسخة القديمة.
  */
 return score.coerceAtMost(100)
  
  }
  
  private fun scorePriceQuery(msg: String): Int {
  
   var score = 0

 if (msg == "سعر") score += 100

 if (msg.contains("سعر")) score += 92
 if (msg.contains("كم سعر")) score += 96
 if (msg.contains("بكم")) score += 90
 if (msg.contains("اسعار")) score += 90
 if (msg.contains("أسعار")) score += 90
 if (msg.contains("price")) score += 85

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Loyalty
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreLoyaltyQuery(msg: String): Int {
  
   var score = 0

 if (msg.contains("نقاط")) score += 92
 if (msg.contains("ولاء")) score += 90
 if (msg.contains("points")) score += 85
 if (msg.contains("loyalty")) score += 82
 if (msg.contains("مكافآت")) score += 80
 if (msg.contains("مكافآت")) score += 80

 return score.coerceAtMost(100)
  
  }
  
  private fun scoreRedeemPoints(msg: String): Int {
  
   var score = 0

 if (msg.contains("استبدال")) score += 95
 if (msg.contains("استبدل")) score += 95
 if (msg.contains("redeem")) score += 90
 if (msg.contains("صرف نقاط")) score += 85
 if (msg.contains("تحويل نقاط")) score += 82

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Orders
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreTrackOrder(msg: String): Int {
  
   var score = 0

 if (msg.contains("حالة الطلب")) score += 100
 if (msg.contains("تتبع")) score += 92
 if (msg.contains("track")) score += 88
 if (msg.contains("order status")) score += 88
 if (msg.contains("طلبي")) score += 88
 if (msg.contains("وين الطلب")) score += 85
 if (msg.contains("اين الطلب")) score += 85
 if (msg.contains("أين الطلب")) score += 85

 return score.coerceAtMost(100)
  
  }
  
  private fun scoreOrderHistory(msg: String): Int {
  
   var score = 0

 if (msg.contains("سجل الطلبات")) score += 100
 if (msg.contains("سجل")) score += 90
 if (msg.contains("تاريخ الطلبات")) score += 92
 if (msg.contains("طلباتي")) score += 90
 if (msg.contains("history")) score += 85
 if (msg.contains("فواتيري")) score += 78

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Help
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreHelp(msg: String): Int {
  
   var score = 0

 if (
     msg == "استعلام" ||
     msg == "مساعدة" ||
     msg == "الخدمات"
 ) {
     score += 100
 }

 if (msg.contains("استعلام")) score += 92
 if (msg.contains("مساعدة")) score += 90
 if (msg.contains("help")) score += 88
 if (msg.contains("قائمة")) score += 82
 if (msg.contains("menu")) score += 80
 if (msg.contains("خدمات")) score += 80

 /*
  * لا نعتبر أي سؤال عادي "مساعدة" بدرجة عالية.
  */
 if (
     msg.endsWith("?") ||
     msg.endsWith("؟")
 ) {
     score += 20
 }

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Complaint
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreComplaint(msg: String): Int {
  
   var score = 0

 if (msg.contains("شكوى")) score += 98
 if (msg.contains("شكايه")) score += 95
 if (msg.contains("شكايتي")) score += 95
 if (msg.contains("complaint")) score += 90
 if (msg.contains("مشكلة")) score += 85
 if (msg.contains("مشكله")) score += 85
 if (msg.contains("problem")) score += 82
 if (msg.contains("سيء")) score += 65

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Emergency
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreEmergency(msg: String): Int {
  
   var score = 0

 if (msg.contains("طوارئ")) score += 100
 if (msg.contains("طارئ")) score += 98
 if (msg.contains("urgent")) score += 92
 if (msg.contains("emergency")) score += 92
 if (msg.contains("عاجل")) score += 95
 if (msg.contains("ضروري")) score += 78
 if (msg.contains("نجدة")) score += 90

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Callback
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreCallbackRequest(msg: String): Int {
  
   var score = 0

 if (msg == "اتصال") score += 100
 if (msg.contains("اتصلوا")) score += 95
 if (msg.contains("اتصل")) score += 90
 if (msg.contains("كلموني")) score += 90
 if (msg.contains("كلمني")) score += 88
 if (msg.contains("callback")) score += 88
 if (msg.contains("اتصل بي")) score += 95

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Location
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreLocationQuery(msg: String): Int {
  
   var score = 0

 if (msg == "موقع") score += 100
 if (msg.contains("موقع المحطة")) score += 98
 if (msg.contains("موقع")) score += 90
 if (msg.contains("location")) score += 85
 if (msg.contains("عنوان")) score += 90
 if (msg.contains("خريطة")) score += 82
 if (msg.contains("وين المحطة")) score += 90
 if (msg.contains("اين المحطة")) score += 90
 if (msg.contains("أين المحطة")) score += 90

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Working Hours
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreWorkingHours(msg: String): Int {
  
   var score = 0

 if (msg.contains("ساعات العمل")) score += 100
 if (msg.contains("ساعات")) score += 90
 if (msg.contains("مواعيد العمل")) score += 95
 if (msg.contains("مواعيد")) score += 85
 if (msg.contains("hours")) score += 85
 if (msg.contains("متى تفتح")) score += 95
 if (msg.contains("متى تسكر")) score += 95
 if (msg.contains("متى تغلق")) score += 95
 if (msg.contains("متى تقفل")) score += 90

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Invoice
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreInvoiceRequest(msg: String): Int {
  
   var score = 0

 if (msg == "فاتورة") score += 100
 if (msg.contains("فاتورة")) score += 95
 if (msg.contains("فواتير")) score += 90
 if (msg.contains("invoice")) score += 88
 if (msg.contains("bill")) score += 82

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Weekly Report
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreWeeklyReport(msg: String): Int {
  
   var score = 0

 if (msg == "تقرير") score += 100
 if (msg.contains("تقرير أسبوعي")) score += 100
 if (msg.contains("تقرير")) score += 90
 if (msg.contains("report")) score += 85
 if (msg.contains("weekly")) score += 88
 if (msg.contains("ملخص")) score += 85
 if (msg.contains("إحصائيات")) score += 80
 if (msg.contains("احصائيات")) score += 80

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Scheduling
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreScheduleAppointment(msg: String): Int {
  
   var score = 0

 if (msg.startsWith("حجز")) score += 98
 if (msg.contains("حجز")) score += 90
 if (msg.contains("موعد")) score += 88
 if (msg.contains("appointment")) score += 85
 if (msg.contains("booking")) score += 85
 if (msg.contains("جدولة")) score += 82

 return score.coerceAtMost(100)
  
  }
  
  private fun scoreRecurringSchedule(msg: String): Int {
  
   var score = 0

 if (RECURRING_REGEX.containsMatchIn(msg)) score += 100
 if (msg.contains("كل يوم")) score += 95
 if (msg.contains("كل أسبوع")) score += 95
 if (msg.contains("كل اسبوع")) score += 95
 if (msg.contains("كل شهر")) score += 95

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Rating
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreRating(msg: String): Int {
  
   if (RATING_REGEX.matches(msg)) {
     return 95
 }

 var score = 0

 if (msg.contains("تقييم")) score += 90
 if (msg.contains("rating")) score += 85
 if (msg.contains("rate")) score += 80
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
  
  private fun scoreGreeting(msg: String): Int {
  
   var score = 0

 if (msg.contains("مرحب")) score += 92
 if (msg.contains("hello")) score += 90
 if (msg == "hi") score += 88
 if (msg.contains("صباح الخير")) score += 100
 if (msg.contains("مساء الخير")) score += 100
 if (msg.contains("اهلا")) score += 92
 if (msg.contains("أهلا")) score += 92
 if (msg.contains("السلام عليكم")) score += 100
 if (msg.contains("السلام")) score += 88
 if (msg.contains("تحية")) score += 75

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Thanks
  // ═══════════════════════════════════════════════════════════════
  
  private fun scoreThanks(msg: String): Int {
  
   var score = 0

 if (msg.contains("شكرا")) score += 98
 if (msg.contains("شكراً")) score += 98
 if (msg.contains("شكري")) score += 85
 if (msg.contains("thanks")) score += 90
 if (msg.contains("thank you")) score += 88
 if (msg.contains("مشكور")) score += 95
 if (msg.contains("تسلم")) score += 82
 if (msg.contains("يعطيك العافية")) score += 90

 return score.coerceAtMost(100)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Quantity Parsing
  // ═══════════════════════════════════════════════════════════════
  
  fun parseQuantity(msgBody: String): QuantityInfo {
  
   val normalized = normalizeNumberText(msgBody)

 if (normalized.isEmpty()) {
     return QuantityInfo(0.0, 0.0, false)
 }

 /*
  * البرميل الكبير:
  * 1 برميل كبير = 12 دبة = 240 لتر.
  */
 LARGE_BARREL_REGEX.find(normalized)?.let { match ->

     val barrels = parseNumber(match.groupValues.getOrNull(1))

         .takeIf { it > 0 }
         ?: 1.0

     val dabbas = barrels * LARGE_BARREL_DABBAS
     val liters = barrels * LARGE_BARREL_LITERS

     return QuantityInfo(
         liters = liters,
         dabbas = dabbas,
         isDabba = false
     )
 }

 /*
  * البرميل العادي:
  * 1 برميل عادي = 10 دباب = 200 لتر.
  */
 ORDINARY_BARREL_REGEX.find(normalized)?.let { match ->

     val barrels = parseNumber(match.groupValues.getOrNull(1))

         .takeIf { it > 0 }
         ?: 1.0

     val dabbas = barrels * ORDINARY_BARREL_DABBAS
     val liters = barrels * ORDINARY_BARREL_LITERS

     return QuantityInfo(
         liters = liters,
         dabbas = dabbas,
         isDabba = false
     )
 }

 /*
  * دبة / دباب:
  */
 DABBA_REGEX.find(normalized)?.let { match ->

     val dabbas =
         parseNumber(match.groupValues.getOrNull(1))

     if (dabbas > 0) {
         return QuantityInfo(
             liters = dabbas * LITERS_PER_DABBA,
             dabbas = dabbas,
             isDabba = true
         )
     }
 }

 /*
  * لتر:
  */
 LITER_REGEX.find(normalized)?.let { match ->

     val liters =
         parseNumber(match.groupValues.getOrNull(1))

     if (liters > 0) {
         return QuantityInfo(
             liters = liters,
             dabbas = liters / LITERS_PER_DABBA,
             isDabba = false
         )
     }
 }

 /*
  * رقم فقط:
  *
  * داخل تدفق الطلب:
  * - 1..50 => دبابات.
  * - >50 => لتر.
  *
  * هذا يحافظ على السلوك السابق للمشروع، لكنه الآن
  * يطبق فقط بعد التأكد من أن النص عبارة عن رقم فقط.
  */
 NUMBER_ONLY_REGEX.matchEntire(normalized)?.let { match ->

     val value =
         parseNumber(match.value)

     if (value <= 0) {
         return QuantityInfo(0.0, 0.0, false)
     }

     return if (value <= 50.0) {

         QuantityInfo(
             liters = value * LITERS_PER_DABBA,
             dabbas = value,
             isDabba = true
         )

     } else {

         QuantityInfo(
             liters = value,
             dabbas = value / LITERS_PER_DABBA,
             isDabba = false
         )
     }
 }

 return QuantityInfo(
     liters = 0.0,
     dabbas = 0.0,
     isDabba = false
 )
  
  }
  
  private fun containsQuantityExpression(msg: String): Boolean {
  
   return NUMBER_ONLY_REGEX.matches(msg) ||
         DABBA_REGEX.containsMatchIn(msg) ||
         LITER_REGEX.containsMatchIn(msg) ||
         ORDINARY_BARREL_REGEX.containsMatchIn(msg) ||
         LARGE_BARREL_REGEX.containsMatchIn(msg)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Delivery Time Parsing
  // ═══════════════════════════════════════════════════════════════
  
  fun parseDeliveryTime(msgBody: String): TimeInfo? {
  
   val normalized = normalizeText(msgBody)

 if (normalized.isEmpty()) {
     return null
 }

 /*
  * "الآن" و"حالا" تعني أقرب وقت تجهيز،
  * وهنا نحجز 30 دقيقة كما كان مقصودًا في النظام.
  */
 if (
     normalized == "الان" ||
     normalized.contains("الآن") ||
     normalized.contains("حالا") ||
     normalized.contains("حالاً") ||
     normalized == "now"
 ) {

     val timestamp =
         System.currentTimeMillis() + (30L * 60L * 1000L)

     val format =
         SimpleDateFormat("hh:mm a", Locale("ar"))

     return TimeInfo(
         displayTime = "الآن (${format.format(Date(timestamp))})",
         timestamp = timestamp
     )
 }

 val match =
     TIME_REGEX.find(normalized)
         ?: return null

 var hour =
     match.groupValues.getOrNull(1)
         ?.toIntOrNull()
         ?: return null

 val minute =
     match.groupValues.getOrNull(2)
         ?.toIntOrNull()
         ?: 0

 val period =
     match.groupValues.getOrNull(3)
         ?.trim()
         ?: ""

 /*
  * إذا لم توجد فترة صباح/مساء:
  * لا نفترض AM بشكل أعمى.
  * نستخدم الساعة كما أدخلها المستخدم.
  */
 if (hour !in 0..23) {
     return null
 }

 if (minute !in 0..59) {
     return null
 }

 val isPm =
     period == "م" ||
             period.contains("مساء") ||
             period.equals("pm", ignoreCase = true)

 val isAm =
     period == "ص" ||
             period.contains("صباح") ||
             period.equals("am", ignoreCase = true)

 if (isAm || isPm) {

     if (hour !in 1..12) {
         return null
     }

     if (isPm && hour != 12) {
         hour += 12
     }

     if (isAm && hour == 12) {
         hour = 0
     }
 }

 val cal = Calendar.getInstance()

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
  * إذا كان الوقت قد مضى اليوم، نعتبره للغد.
  */
 if (
     cal.timeInMillis <=
     System.currentTimeMillis()
 ) {
     cal.add(
         Calendar.DAY_OF_MONTH,
         1
     )
 }

 val displayHour =
     when {
         isAm || isPm ->
             match.groupValues[1].toIntOrNull()
                 ?: hour

         hour == 0 ->
             12

         hour > 12 ->
             hour - 12

         else ->
             hour
     }

 val displayPeriod =
     when {
         isAm -> "ص"
         isPm -> "م"
         hour < 12 -> "ص"
         else -> "م"
     }

 val display =
     "$displayHour:${String.format(Locale.US, "%02d", minute)} $displayPeriod"

 return TimeInfo(
     displayTime = display,
     timestamp = cal.timeInMillis
 )
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Amount Parsing
  // ═══════════════════════════════════════════════════════════════
  
  fun extractAmount(msgBody: String): Double {
  
   val normalized =
     normalizeNumberText(msgBody)

 if (normalized.isEmpty()) {
     return 0.0
 }

 val match =
     AMOUNT_REGEX.find(normalized)

 if (match != null) {
     return parseNumber(
         match.groupValues.getOrNull(1)
     )
 }

 /*
  * fallback:
  * إذا لم توجد كلمة "ريال"، نحاول استخراج رقم واضح
  * من رسالة مثل:
  * "دفع 5000"
  */
 val fallback =
     Regex("""\d{1,12}(?:[.,]\d{1,2})?""")
         .find(normalized)

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
     RECURRING_REGEX.find(normalized)
         ?: return null

 val period =
     when (match.groupValues[1]) {
         "اسبوع" -> "أسبوع"
         else -> match.groupValues[1]
     }

 val day =
     match.groupValues
         .getOrNull(2)
         ?.trim()
         .orEmpty()

 /*
  * يوميًا:
  * "كل يوم"
  */
 if (period == "يوم") {
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
  
  private fun normalizeText(
  value: String?
  ): String {
  
   if (value.isNullOrBlank()) {
     return ""
 }

 var result =
     value
         .lowercase(Locale.getDefault())
         .trim()

 result =
     normalizeArabicDigits(result)

 result =
     ARABIC_DIACRITICS_REGEX
         .replace(result, "")

 /*
  * توحيد بعض الحروف العربية التي تظهر في SMS
  * بأشكال مختلفة.
  */
 result = result
     .replace('أ', 'ا')
     .replace('إ', 'ا')
     .replace('آ', 'ا')
     .replace('ٱ', 'ا')
     .replace('ى', 'ي')
     .replace('ؤ', 'و')
     .replace('ئ', 'ي')

 /*
  * إزالة التكرار الزائد للمسافات.
  */
 result =
     MULTIPLE_SPACES_REGEX
         .replace(result, " ")

 return result.trim()
  
  }
  
  private fun normalizeNumberText(
  value: String?
  ): String {
  
   var result =
     normalizeText(value)

 /*
  * الفاصلة العربية تصبح فاصلة عشرية/آمنة.
  */
 result =
     result.replace('،', ',')

 /*
  * في الأرقام نعتبر الفاصلة نقطة عشرية عندما تكون
  * بين أرقام.
  */
 result =
     result.replace(
         Regex("""(?<=\d),(?=\d)"""),
         "."
     )

 return result
  
  }
  
  private fun normalizeArabicDigits(
  value: String
  ): String {
  
   val builder =
     StringBuilder(value.length)

 for (char in value) {

     val indicIndex =
         ARABIC_INDIC_DIGITS.indexOf(char)

     if (indicIndex >= 0) {
         builder.append(indicIndex)
         continue
     }

     val easternIndex =
         ARABIC_EASTERN_DIGITS.indexOf(char)

     if (easternIndex >= 0) {
         builder.append(easternIndex)
         continue
     }

     builder.append(char)
 }

 return builder.toString()
  
  }
  
  private fun parseNumber(
  value: String?
  ): Double {
  
   if (value.isNullOrBlank()) {
     return 0.0
 }

 return value
     .replace(',', '.')
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
  
   return msg == "إلغاء" ||
         msg == "الغاء" ||
         msg == "الغي" ||
         msg == "ألغي" ||
         msg == "لا" ||
         msg == "cancel" ||
         msg == "no"
  
  }
  
  private fun containsObviousCommand(
  msg: String
  ): Boolean {
  
   val commands = listOf(
     "اريد ديزل",
     "أريد ديزل",
     "طلب ديزل",
     "اريد بنزين",
     "أريد بنزين",
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
     "إلغاء"
 )

 return commands.any {
     msg.contains(it)
 }
  
  }
  }