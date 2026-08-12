package com.aistudio.dieselstationsms.kxmpzq.startup

/**
 * ═══════════════════════════════════════════════════════════════
 * عقد تسجيل أحداث وعمليات بدء تشغيل التطبيق
 * StartupLogger
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 * - تسجيل أحداث بدء التشغيل.
 * - تسجيل مراحل Pipeline.
 * - تسجيل حالات النجاح والفشل والتخطي.
 * - تسجيل الفحوصات الصحية.
 * - تسجيل انتقالات State Machine.
 * - تسجيل عمليات الإلغاء.
 *
 * ملاحظات مهمة:
 * 1. هذا الملف يعرّف العقد فقط ولا يحتوي على تنفيذ فعلي.
 * 2. لا ينبغي للـ Logger تعديل حالة التطبيق أو قاعدة البيانات.
 * 3. لا ينبغي للـ Logger إطلاق Services أو تنفيذ عمليات Startup.
 * 4. يجب أن يكون التنفيذ الفعلي في StartupLoggerImpl.kt.
 * 5. جميع الدوال مصممة لتكون آمنة للاستدعاء من مراحل Startup
 *    المختلفة.
 */
interface StartupLogger {

    /**
     * تسجيل استقبال حدث بدء تشغيل جديد.
     *
     * @param reason سبب بدء التشغيل.
     * @param action الإجراء الاختياري المرتبط بالحدث.
     * @param correlationId المعرّف الفريد لدورة Startup الحالية.
     */
    fun logBootReceived(
        reason: StartupReason,
        action: String?,
        correlationId: String
    )

    /**
     * تسجيل بداية تنفيذ مرحلة.
     *
     * @param phase اسم المرحلة.
     * @param correlationId معرّف دورة Startup.
     */
    fun logPhaseStarted(
        phase: String,
        correlationId: String
    )

    /**
     * تسجيل نجاح مرحلة.
     *
     * @param phase اسم المرحلة.
     * @param message رسالة النتيجة الاختيارية.
     * @param correlationId معرّف دورة Startup.
     */
    fun logPhaseSuccess(
        phase: String,
        message: String?,
        correlationId: String
    )

    /**
     * تسجيل تخطي مرحلة.
     *
     * @param phase اسم المرحلة.
     * @param reason سبب التخطي.
     * @param correlationId معرّف دورة Startup.
     */
    fun logPhaseSkipped(
        phase: String,
        reason: String,
        correlationId: String
    )

    /**
     * تسجيل فشل مرحلة.
     *
     * @param phase اسم المرحلة.
     * @param error وصف الخطأ.
     * @param correlationId معرّف دورة Startup.
     */
    fun logPhaseFailed(
        phase: String,
        error: String,
        correlationId: String
    )

    /**
     * تسجيل نجاح Pipeline بالكامل.
     *
     * @param durationMs مدة التنفيذ بالميلي ثانية.
     * @param phases قائمة المراحل التي اكتملت بنجاح.
     * @param correlationId معرّف دورة Startup.
     */
    fun logPipelineSuccess(
        durationMs: Long,
        phases: List<String>,
        correlationId: String
    )

    /**
     * تسجيل فشل Pipeline.
     *
     * @param phase المرحلة التي حدث عندها الفشل.
     * @param error وصف الخطأ.
     * @param correlationId معرّف دورة Startup.
     */
    fun logPipelineFailed(
        phase: String,
        error: String,
        correlationId: String
    )

    /**
     * تسجيل نتيجة الفحص الصحي للخدمة.
     *
     * @param status حالة الفحص الصحي.
     * @param details تفاصيل إضافية اختيارية.
     * @param correlationId معرّف دورة Startup.
     */
    fun logHealthCheck(
        status: String,
        details: String?,
        correlationId: String
    )

    /**
     * تسجيل انتقال State Machine من حالة إلى أخرى.
     *
     * @param oldState الحالة السابقة.
     * @param newState الحالة الجديدة.
     * @param correlationId معرّف دورة Startup.
     */
    fun logStateChanged(
        oldState: StartupStateMachine.State,
        newState: StartupStateMachine.State,
        correlationId: String
    )

    /**
     * تسجيل إلغاء دورة Startup.
     *
     * @param correlationId معرّف دورة Startup التي تم إلغاؤها.
     * @param reason سبب الإلغاء.
     */
    fun logCancelled(
        correlationId: String,
        reason: String
    )
}