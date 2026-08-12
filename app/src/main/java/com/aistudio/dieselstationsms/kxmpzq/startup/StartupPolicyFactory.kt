package com.aistudio.dieselstationsms.kxmpzq.startup

import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.PhaseRegistry
import com.aistudio.dieselstationsms.kxmpzq.startup.policy.BootStartupPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.policy.ManualStartupPolicy

/**
 * ═══════════════════════════════════════════════════════════════
 * مصنع سياسات بدء التشغيل - StartupPolicyFactory
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 *
 * 1. إنشاء StartupPolicy المناسبة حسب StartupReason.
 * 2. تحديد مجموعة مراحل التهيئة المطلوبة لكل سبب.
 * 3. الاعتماد فقط على المراحل المسجلة فعليًا في PhaseRegistry.
 * 4. الحفاظ على ترتيب المراحل المنطقي من خلال dependencies
 *    الموجودة داخل InitializationPhase / DagEngine.
 * 5. عدم إنشاء أو افتراض مراحل غير موجودة.
 * 6. عدم إنشاء StartupPolicy جديدة غير موجودة في المشروع.
 *
 * التصميم:
 *
 * BOOT
 * └── BootStartupPolicy
 *
 * جميع الأسباب الأخرى
 * └── ManualStartupPolicy
 *
 * ملاحظة مهمة:
 *
 * BootDelay خاص بمسار BOOT فقط.
 *
 * لا يتم استخدام BootDelay في:
 * - MANUAL
 * - CRASH_RECOVERY
 * - APP_UPDATED
 * - TIME_CHANGED
 * - TIMEZONE_CHANGED
 * - ALARM
 * - SCHEDULED
 * - USER_UNLOCKED
 *
 * وذلك لأن هذه الحالات لا تعني بالضرورة أن الجهاز دخل مرحلة
 * Android Boot جديدة.
 */
object StartupPolicyFactory {

    private const val ENVIRONMENT_CHECK = "EnvironmentCheck"
    private const val BOOT_DELAY = "BootDelay"
    private const val PERMISSION_CHECK = "PermissionCheck"
    private const val SERVICE_LAUNCH = "ServiceLaunch"
    private const val HEALTH_CHECK = "HealthCheck"

    /**
     * إنشاء سياسة بدء التشغيل المناسبة.
     *
     * @param reason سبب بدء التشغيل.
     * @param phaseRegistry سجل مراحل التهيئة الفعلية.
     *
     * @return سياسة StartupPolicy المناسبة.
     *
     * @throws IllegalStateException
     * إذا كانت مرحلة مطلوبة غير مسجلة في PhaseRegistry.
     */
    fun create(
        reason: StartupReason,
        phaseRegistry: PhaseRegistry
    ): StartupPolicy {

        val phases = phasesFor(
            reason = reason,
            phaseRegistry = phaseRegistry
        )

        return when (reason) {
            StartupReason.BOOT -> {
                BootStartupPolicy(phases)
            }

            StartupReason.CRASH_RECOVERY,
            StartupReason.MANUAL,
            StartupReason.APP_UPDATED,
            StartupReason.TIME_CHANGED,
            StartupReason.TIMEZONE_CHANGED,
            StartupReason.ALARM,
            StartupReason.SCHEDULED,
            StartupReason.USER_UNLOCKED -> {
                ManualStartupPolicy(phases)
            }
        }
    }

    /**
     * تحديد مراحل التهيئة المطلوبة لكل StartupReason.
     *
     * لا يتم هنا تنفيذ المراحل.
     *
     * DagEngine داخل InitializationPipeline هو المسؤول عن:
     * - التحقق من dependencies.
     * - اكتشاف الدورات.
     * - تحديد المراحل الجاهزة.
     * - ترتيب التنفيذ الفعلي.
     *
     * الترتيب الموجود هنا هو ترتيب منطقي مقصود فقط.
     */
    private fun phasesFor(
        reason: StartupReason,
        phaseRegistry: PhaseRegistry
    ): List<InitializationPhase> {

        return when (reason) {

            /**
             * ═════════════════════════════════════════════════════
             * BOOT
             * ═════════════════════════════════════════════════════
             *
             * المسار الكامل عند إقلاع الجهاز:
             *
             * EnvironmentCheck
             *        ↓
             * BootDelay
             *        ↓
             * PermissionCheck
             *        ↓
             * ServiceLaunch
             *        ↓
             * HealthCheck
             */
            StartupReason.BOOT -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(BOOT_DELAY),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH),
                phaseRegistry.requirePhase(HEALTH_CHECK)
            )

            /**
             * ═════════════════════════════════════════════════════
             * CRASH_RECOVERY
             * ═════════════════════════════════════════════════════
             *
             * الهدف هو استعادة الخدمة بسرعة.
             *
             * لا نستخدم BootDelay لأن الجهاز قد لا يكون في مرحلة
             * إقلاع جديدة.
             *
             * ولا نضيف HealthCheck هنا؛ لأن HealthCheck ليس ضروريًا
             * لعملية الاستعادة الأساسية.
             */
            StartupReason.CRASH_RECOVERY -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH)
            )

            /**
             * ═════════════════════════════════════════════════════
             * MANUAL
             * ═════════════════════════════════════════════════════
             *
             * تشغيل يدوي من المستخدم.
             */
            StartupReason.MANUAL -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH)
            )

            /**
             * ═════════════════════════════════════════════════════
             * APP_UPDATED
             * ═════════════════════════════════════════════════════
             *
             * بعد تحديث التطبيق:
             *
             * EnvironmentCheck
             *        ↓
             * PermissionCheck
             *        ↓
             * ServiceLaunch
             *        ↓
             * HealthCheck
             */
            StartupReason.APP_UPDATED -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH),
                phaseRegistry.requirePhase(HEALTH_CHECK)
            )

            /**
             * ═════════════════════════════════════════════════════
             * TIME_CHANGED
             * ═════════════════════════════════════════════════════
             */
            StartupReason.TIME_CHANGED -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH),
                phaseRegistry.requirePhase(HEALTH_CHECK)
            )

            /**
             * ═════════════════════════════════════════════════════
             * TIMEZONE_CHANGED
             * ═════════════════════════════════════════════════════
             */
            StartupReason.TIMEZONE_CHANGED -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH),
                phaseRegistry.requirePhase(HEALTH_CHECK)
            )

            /**
             * ═════════════════════════════════════════════════════
             * ALARM
             * ═════════════════════════════════════════════════════
             *
             * الـ Alarm ليس Boot.
             */
            StartupReason.ALARM -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH),
                phaseRegistry.requirePhase(HEALTH_CHECK)
            )

            /**
             * ═════════════════════════════════════════════════════
             * SCHEDULED
             * ═════════════════════════════════════════════════════
             */
            StartupReason.SCHEDULED -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH),
                phaseRegistry.requirePhase(HEALTH_CHECK)
            )

            /**
             * ═════════════════════════════════════════════════════
             * USER_UNLOCKED
             * ═════════════════════════════════════════════════════
             *
             * الجهاز تم فتحه بعد Direct Boot.
             *
             * لا يوجد BootDelay هنا لأن مرحلة الإقلاع نفسها
             * ليست ما يتم التعامل معه.
             */
            StartupReason.USER_UNLOCKED -> listOf(
                phaseRegistry.requirePhase(ENVIRONMENT_CHECK),
                phaseRegistry.requirePhase(PERMISSION_CHECK),
                phaseRegistry.requirePhase(SERVICE_LAUNCH),
                phaseRegistry.requirePhase(HEALTH_CHECK)
            )
        }
    }

    /**
     * الحصول على Phase من PhaseRegistry مع فشل واضح ومباشر
     * إذا لم تكن المرحلة مسجلة.
     *
     * استخدام requirePhase يمنع أخطاء null أو مراحل مفقودة
     * من الظهور لاحقًا داخل InitializationPipeline.
     */
    private fun PhaseRegistry.requirePhase(
        name: String
    ): InitializationPhase {
        return get(name)
            ?: throw IllegalStateException(
                "Required startup phase '$name' is not registered in PhaseRegistry"
            )
    }
}