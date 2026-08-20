package com.aistudio.dieselstationsms.kxmpzq.sms

import java.util.Locale

/** يقرر الحاجة إلى AI قبل أي استدعاء خارجي، ولا يستخدم quota لتحليل الأوامر المحلية البسيطة. */
class SmsAiRoutingEngine {
    fun decide(message: String, context: SmsConversationManager.ConversationContext): SmsAiRoutingDecision {
        val normalized = SmsMessageNormalizer.normalizeForMatch(message).lowercase(Locale.ROOT)
        val simpleLocal = SIMPLE_LOCAL_PATTERNS.any { normalized.matches(it) }
        val complexSignals = listOf(
            "نفس الطلب", "مثل السابق", "اذا", "إذا", "قبل التأكيد", "خبرني", "بشرط", "لكن",
            "وإذا", "وبكرة", "بكرة", "بكره", "بعد العصر", "الأسبوع الماضي", "اسبوع الماضي",
            "عدّل", "عدل", "اجمع", "أكثر من", "اقل من", "أقل من", "معاً", "معا"
        ).count { normalized.contains(it) }
        val hasMultipleClauses = normalized.count { it == 'و' || it == ',' || it == '،' } >= 2
        val longOrUnstructured = normalized.length >= 80 || normalized.split(Regex("\\s+")).size >= 16
        val contextDependent = context.awaitingResponse && (
            normalized in setOf("نعم", "اي", "أيوه", "لا", "تمام", "نفسه", "نفسها", "غيره", "عدله") ||
                normalized.contains("السابق")
            )
        val explicitFuelRequestWithEntities = normalized.contains("ديزل") || normalized.contains("بنزين") || normalized.contains("dизel") || normalized.contains("diesel")
        val needsAi = !simpleLocal && (
            complexSignals > 0 ||
                hasMultipleClauses ||
                longOrUnstructured ||
                contextDependent ||
                explicitFuelRequestWithEntities && (
                    normalized.contains(Regex("\\d")) ||
                        normalized.contains("دباب") ||
                        normalized.contains("دبة") ||
                        normalized.contains("لتر") ||
                        normalized.contains("الى") ||
                        normalized.contains("إلى") ||
                        normalized.contains("بكرة") ||
                        normalized.contains("بكره") ||
                        normalized.contains("غدا")
                    )
            )
        return SmsAiRoutingDecision(
            needsAi = needsAi,
            complexity = when {
                complexSignals >= 2 || longOrUnstructured -> SmsAiTaskComplexity.HIGH
                needsAi -> SmsAiTaskComplexity.MEDIUM
                else -> SmsAiTaskComplexity.LOW
            },
            sensitive = normalized.contains("دفع") || normalized.contains("تحويل") || normalized.contains("استبدال") || normalized.contains("تأكيد") || normalized.contains("الغاء") || normalized.contains("إلغاء"),
            reason = when {
                simpleLocal -> "local_pattern"
                contextDependent -> "context_dependent_reply"
                complexSignals > 0 -> "complex_constraint_signal"
                hasMultipleClauses -> "multiple_clauses"
                longOrUnstructured -> "long_or_unstructured_message"
                else -> "local_engine_sufficient"
            }
        )
    }

    companion object {
        private val SIMPLE_LOCAL_PATTERNS = listOf(
            Regex("^(اريد|أريد|ابغى|أبغى|اشتي|أشتي)\\s+(ديزل|دیزل)$"),
            Regex("^(رصيد|حسابي|كم رصيدي|نقاط|ولاء)$"),
            Regex("^(سعر|الاسعار|الأسعار|كم السعر)$"),
            Regex("^(عروض|عرض|خصم)$"),
            Regex("^(موقع|وينكم|العنوان|دوام|ساعات العمل)$"),
            Regex("^(مساعدة|استعلام|الاوامر|الأوامر|help)$"),
            Regex("^(الغاء|إلغاء|لا|نعم|تأكيد|تاكيد|تمام)$")
        )
    }
}

enum class SmsAiTaskComplexity { LOW, MEDIUM, HIGH }

data class SmsAiRoutingDecision(
    val needsAi: Boolean,
    val complexity: SmsAiTaskComplexity,
    val sensitive: Boolean,
    val reason: String
)
