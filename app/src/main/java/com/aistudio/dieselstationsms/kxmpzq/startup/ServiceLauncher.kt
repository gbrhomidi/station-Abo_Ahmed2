package com.aistudio.dieselstationsms.kxmpzq.startup

/**
 * ═══════════════════════════════════════════════════════════════
 * نتائج محاولة تشغيل الخدمة - ServiceLaunchResult
 * ═══════════════════════════════════════════════════════════════
 *
 * يمثل هذا الـ sealed class النتيجة المباشرة لمحاولة تشغيل
 * SMSService من خلال ServiceLauncher.
 *
 * الحالات:
 *
 * 1. Success
 *    تم قبول طلب تشغيل الخدمة بنجاح.
 *
 * 2. AlreadyRunning
 *    الخدمة مسجلة حاليًا على أنها تعمل، ولذلك لم تتم محاولة
 *    تشغيل جديدة.
 *
 * 3. Failure
 *    فشل طلب التشغيل، مع تحديد ما إذا كان من الآمن إعادة
 *    المحاولة بواسطة منظومة Retry/Recovery.
 */
sealed class ServiceLaunchResult {

    /**
     * تم قبول طلب تشغيل الخدمة.
     *
     * ملاحظة:
     * Success تعني نجاح طلب التشغيل، ولا تعني بالضرورة أن
     * SMSService أكملت جميع مراحل التهيئة الداخلية وأصبحت
     * جاهزة لمعالجة SMS.
     */
    data class Success(
        val message: String? = null
    ) : ServiceLaunchResult()

    /**
     * الخدمة تعمل بالفعل وفق حالة منظومة Startup.
     *
     * هذه ليست حالة فشل؛ بل نتيجة طبيعية تمنع تشغيل نسخة
     * إضافية من الخدمة.
     */
    data class AlreadyRunning(
        val message: String? = null
    ) : ServiceLaunchResult()

    /**
     * فشل تشغيل الخدمة.
     *
     * @param error وصف الخطأ.
     * @param retryable يحدد ما إذا كان من المناسب أن تقوم
     * منظومة Retry/Recovery بمحاولة التشغيل مرة أخرى.
     *
     * أخطاء الصلاحيات أو القيود الدائمة ينبغي أن تكون
     * retryable = false، بينما الأخطاء المؤقتة يمكن أن تكون
     * retryable = true.
     */
    data class Failure(
        val error: String,
        val retryable: Boolean = true
    ) : ServiceLaunchResult()
}

/**
 * ═══════════════════════════════════════════════════════════════
 * عقد تشغيل الخدمة - ServiceLauncher
 * ═══════════════════════════════════════════════════════════════
 *
 * هذا هو العقد الذي يجب أن تلتزم به أي جهة مسؤولة عن طلب
 * تشغيل SMSService.
 *
 * SmsServiceLauncher هو التنفيذ الحالي لهذا العقد.
 */
interface ServiceLauncher {

    /**
     * طلب تشغيل الخدمة بسبب محدد.
     *
     * @param reason السبب الذي أدى إلى محاولة التشغيل.
     *
     * @return نتيجة محاولة التشغيل:
     * - Success
     * - AlreadyRunning
     * - Failure
     */
    fun launch(reason: StartupReason): ServiceLaunchResult
}