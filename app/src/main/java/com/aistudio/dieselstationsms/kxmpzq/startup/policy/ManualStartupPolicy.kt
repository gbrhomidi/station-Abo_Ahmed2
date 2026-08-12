package com.aistudio.dieselstationsms.kxmpzq.startup.policy

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase

/**
 * ═══════════════════════════════════════════════════════════════
 * سياسة بدء التشغيل اليدوي - ManualStartupPolicy
 * ═══════════════════════════════════════════════════════════════
 *
 * هذه السياسة تستخدم لمسارات التشغيل التي لا تمثل إقلاعًا كاملًا
 * للجهاز، وتشمل المسارات التي يمررها StartupPolicyFactory إلى
 * ManualStartupPolicy، مثل:
 *
 * - MANUAL
 * - CRASH_RECOVERY
 * - APP_UPDATED
 * - TIME_CHANGED
 * - TIMEZONE_CHANGED
 * - ALARM
 * - SCHEDULED
 * - USER_UNLOCKED
 *
 * ملاحظات مهمة:
 *
 * 1. هذه الفئة لا تنفذ أي InitializationPhase بنفسها.
 * 2. ترتيب المراحل تحدده StartupPolicyFactory / PhaseRegistry /
 *    DagEngine، وليس هذه السياسة.
 * 3. يسمح هذا المسار بالتنفيذ المتوازي عندما تكون المراحل الجاهزة
 *    مستقلة وفق DAG.
 * 4. الفشل في مرحلة غير حرجة لا يمنع استمرار الـ Pipeline.
 * 5. الفشل في مرحلة حرجة يبقى قادرًا على إيقاف الـ Pipeline،
 *    لأن InitializationPipeline يعتمد على phase.isCritical.
 * 6. لا يتم تفعيل HealthCheck افتراضيًا في هذه السياسة.
 * 7. جمع المقاييس يبقى مفعلًا لمراقبة مسارات الاستعادة والتشغيل.
 */
class ManualStartupPolicy(
    override val phases: List<InitializationPhase>
) : StartupPolicy {

    /**
     * السبب الأساسي المرتبط بهذه السياسة.
     *
     * StartupPolicyFactory يستخدم ManualStartupPolicy أيضًا
     * للأسباب الأخرى التي لا تمتلك Policy مستقلة.
     *
     * لذلك يجب عدم تغيير هذه القيمة إلى CRASH_RECOVERY أو
     * SCHEDULED حسب سبب التشغيل؛ reason هنا جزء من عقد السياسة
     * وليس مرآة ديناميكية للسبب الخارجي.
     */
    override val reason: StartupReason = StartupReason.MANUAL

    /**
     * السماح بتنفيذ المراحل الجاهزة بالتوازي.
     *
     * DagEngine يحدد المراحل التي أصبحت جاهزة، ولذلك لا يعني
     * parallel execution تجاهل dependencies.
     */
    override val allowParallelExecution: Boolean = true

    /**
     * السماح للـ Pipeline بالاستمرار بعد فشل مرحلة غير حرجة.
     *
     * ملاحظة:
     * InitializationPipeline يطبق أيضًا phase.isCritical عند
     * معالجة PhaseResult.Failure.
     */
    override val continueOnFailure: Boolean = true

    /**
     * لا يتم فرض HealthCheck إضافي على مسار التشغيل اليدوي.
     *
     * إذا احتاج مسار معين إلى HealthCheck، فيمكن لـ
     * StartupPolicyFactory إدراج HealthCheckPhase ضمن phases.
     */
    override val healthCheckEnabled: Boolean = false

    /**
     * تفعيل جمع مقاييس Startup.
     *
     * هذا يسمح بتتبع أداء المسارات اليدوية ومسارات الاستعادة
     * دون فرض HealthCheck إضافي عليها.
     */
    override val metricsEnabled: Boolean = true
}