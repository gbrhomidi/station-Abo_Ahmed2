package com.aistudio.dieselstationsms.kxmpzq.startup.policy

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase

/**
 * ═══════════════════════════════════════════════════════════════
 * سياسة بدء التشغيل عند إقلاع الجهاز - BootStartupPolicy
 * ═══════════════════════════════════════════════════════════════
 *
 * هذه السياسة مخصصة حصريًا لمسار:
 *
 *     StartupReason.BOOT
 *
 * وهي تصف كيفية تنفيذ InitializationPipeline لمراحل الإقلاع،
 * ولا تقوم بتنفيذ أي Phase بنفسها.
 *
 * المسؤوليات:
 *
 * 1. تشغيل مراحل BOOT وفق المراحل التي يحددها
 *    StartupPolicyFactory.
 *
 * 2. منع التنفيذ المتوازي لمراحل BOOT.
 *
 * 3. إيقاف مسار startup عند فشل مرحلة حرجة.
 *
 * 4. تفعيل HealthCheck ضمن سياسة BOOT.
 *
 * 5. تفعيل جمع المقاييس ضمن سياسة BOOT.
 *
 * مبدأ معماري:
 *
 * BootStartupPolicy لا تنشئ أو تشغل:
 * - SMSService
 * - ServiceLauncher
 * - HealthMonitor
 * - RetryPolicy
 * - InitializationPipeline
 *
 * وإنما توفر فقط configuration policy للـ pipeline.
 */
class BootStartupPolicy(
    phases: List<InitializationPhase>
) : StartupPolicy {

    /**
     * قائمة مراحل BOOT التي يحددها StartupPolicyFactory.
     *
     * نحتفظ بها كقائمة غير قابلة للتعديل من خارج هذه الفئة
     * عبر إنشاء نسخة مستقلة، حتى لا يتم تغيير السياسة بعد
     * إنشائها عن طريق تعديل القائمة الأصلية.
     */
    override val phases: List<InitializationPhase> =
        phases.toList()

    /**
     * سبب التشغيل الذي تنطبق عليه هذه السياسة.
     */
    override val reason: StartupReason =
        StartupReason.BOOT

    /**
     * مراحل BOOT تنفذ بصورة تسلسلية.
     *
     * هذا يمنع تشغيل مراحل مستقلة في نفس الوقت عندما يكون
     * ترتيب الإقلاع مهمًا.
     *
     * كما أن DagEngine يبقى المسؤول عن احترام dependencies
     * وترتيب المراحل الجاهزة.
     */
    override val allowParallelExecution: Boolean =
        false

    /**
     * فشل مرحلة حرجة يجب أن يوقف مسار BOOT.
     *
     * InitializationPipeline يعتمد على:
     *
     *     phase.isCritical
     *
     * لاتخاذ قرار الإيقاف الفعلي.
     *
     * لذلك هذه الخاصية تعبر عن سياسة BOOT نفسها، بينما
     * قرار الإيقاف التنفيذي موجود داخل InitializationPipeline.
     */
    override val continueOnFailure: Boolean =
        false

    /**
     * فحص صحة الخدمة مطلوب في مسار BOOT.
     *
     * StartupPolicyFactory يضيف HealthCheckPhase إلى مراحل
     * BOOT، وHealthCheckPhase نفسها تعتمد على ServiceLaunch.
     */
    override val healthCheckEnabled: Boolean =
        true

    /**
     * جمع مقاييس startup مفعل لمسار BOOT.
     *
     * يمكن للـ ApplicationInitializationCoordinator
     * وطبقة metrics استخدام هذه القيمة بالإضافة إلى
     * ConfigurationProvider.getMetricsEnabled().
     */
    override val metricsEnabled: Boolean =
        true
}