package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext

/**
 * ═══════════════════════════════════════════════════════════════
 * عقد مرحلة تهيئة التطبيق - InitializationPhase
 * ═══════════════════════════════════════════════════════════════
 *
 * تمثل هذه الواجهة مرحلة واحدة مستقلة ضمن Startup Pipeline.
 *
 * كل مرحلة تحدد:
 * 1. اسمًا فريدًا.
 * 2. هل فشلها حرج أم لا.
 * 3. الحد الأقصى لزمن تنفيذها.
 * 4. المراحل التي تعتمد عليها.
 * 5. منطق تنفيذها.
 *
 * ملاحظات:
 * - لا تحتوي الواجهة على منطق خاص بـ SMSService.
 * - لا تحتوي على منطق قاعدة البيانات.
 * - لا تنشئ CoroutineScope جديدة.
 * - الإلغاء والـ timeout وإعادة المحاولة تتم إدارتها
 *   بواسطة InitializationPipeline والسياسات المرتبطة به.
 */
interface InitializationPhase {

    /**
     * الاسم الفريد للمرحلة.
     *
     * يجب ألا يتكرر بين المراحل لأن DagEngine يعتمد عليه
     * في بناء الاعتماديات وترتيب التنفيذ.
     */
    val name: String

    /**
     * هل فشل هذه المرحلة يؤدي إلى فشل الـ Pipeline بالكامل؟
     *
     * القيمة الافتراضية:
     * true
     */
    val isCritical: Boolean
        get() = true

    /**
     * الحد الأقصى المسموح لتنفيذ المرحلة بالمللي ثانية.
     *
     * InitializationPipeline هو المسؤول عن تطبيق هذا
     * الحد باستخدام withTimeout().
     *
     * القيمة الافتراضية:
     * 30 ثانية.
     */
    val timeoutMs: Long
        get() = 30_000L

    /**
     * أسماء المراحل التي يجب أن تكتمل قبل بدء هذه المرحلة.
     *
     * DagEngine يستخدم هذه القائمة لبناء وترتيب DAG.
     *
     * القيمة الافتراضية:
     * لا توجد اعتماديات.
     */
    val dependencies: List<String>
        get() = emptyList()

    /**
     * تنفيذ منطق المرحلة.
     *
     * لا ينبغي للمرحلة إنشاء Scope مستقل أو ابتلاع
     * CancellationException؛ يجب السماح للإلغاء بالانتشار
     * إلى InitializationPipeline.
     */
    suspend fun execute(
        ctx: InitializationContext
    ): PhaseResult
}

/**
 * ═══════════════════════════════════════════════════════════════
 * نتائج تنفيذ مرحلة Startup
 * ═══════════════════════════════════════════════════════════════
 *
 * يوجد ثلاثة أنواع فقط من النتائج:
 *
 * Success  -> المرحلة اكتملت بنجاح.
 * Failure  -> المرحلة فشلت.
 * Skipped  -> المرحلة لم تنفذ بصورة فعلية وتم تخطيها عمدًا.
 */
sealed class PhaseResult {

    /**
     * نجاح المرحلة.
     *
     * @param message رسالة اختيارية للتسجيل أو التشخيص.
     */
    data class Success(
        val message: String? = null
    ) : PhaseResult()

    /**
     * فشل المرحلة.
     *
     * @param error وصف الخطأ.
     * @param retryable هل يسمح هذا الخطأ بإعادة المحاولة؟
     */
    data class Failure(
        val error: String,
        val retryable: Boolean = true
    ) : PhaseResult()

    /**
     * تخطي المرحلة بصورة مقصودة.
     *
     * @param reason سبب التخطي.
     */
    data class Skipped(
        val reason: String
    ) : PhaseResult()
}