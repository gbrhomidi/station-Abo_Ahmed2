package com.aistudio.dieselstationsms.kxmpzq.startup

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
 * 1. إنشاء سياسة StartupPolicy المناسبة حسب StartupReason.
 * 2. تحديد مراحل التهيئة المطلوبة لكل سبب.
 * 3. عدم افتراض وجود مراحل غير مسجلة في PhaseRegistry.
 * 4. الحفاظ على ترتيب المراحل كما هو معرف من خلال dependencies
 *    في InitializationPhase / DagEngine.
 *
 * ملاحظات تصميمية مهمة:
 *
 * - BOOT هو السبب الوحيد الذي يحتاج BootDelay.
 * - أسباب التشغيل الأخرى لا ينبغي أن تحصل تلقائيًا على BootDelay،
 *   لأن ذلك قد يؤخر استعادة الخدمة دون حاجة.
 * - CRASH_RECOVERY يستخدم المسار المحافظ نفسه المستخدم للتشغيل
 *   اليدوي، مع تجنب BootDelay وHealthCheck الإضافي.
 * - لا يتم إنشاء سياسة جديدة غير موجودة في المشروع.
 */
object StartupPolicyFactory {

    /**
     * إنشاء سياسة بدء التشغيل المناسبة للسبب المحدد.
     *
     * @param reason سبب بدء التشغيل.
     * @param phaseRegistry سجل مراحل التهيئة المسجلة فعليًا.
     *
     * @return StartupPolicy المناسبة.
     */
    fun create(
        reason: StartupReason,
        phaseRegistry: PhaseRegistry
    ): StartupPolicy {

        val phases = when (reason) {

            /**
             * بدء التطبيق نتيجة إقلاع الجهاز.
             *
             * BootDelay يبقى خاصًا بهذا المسار حتى لا تبدأ الخدمة
             * قبل استقرار بيئة Android بعد الإقلاع.
             */
            StartupReason.BOOT -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("BootDelay"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )

            /**
             * استرداد الخدمة بعد انهيار سابق.
             *
             * لا نعيد BootDelay؛ الجهاز لم يُقلع من جديد بالضرورة.
             *
             * كما لا نضيف HealthCheck هنا افتراضيًا، لأن الهدف الأساسي
             * هو استعادة الخدمة بأقل عدد ممكن من العمليات.
             */
            StartupReason.CRASH_RECOVERY -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch")
            )

            /**
             * التشغيل اليدوي من المستخدم.
             */
            StartupReason.MANUAL -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch")
            )

            /**
             * تحديث التطبيق.
             *
             * لا نستخدم BootDelay؛ تحديث التطبيق لا يعني أن الجهاز
             * دخل حالة إقلاع جديدة.
             */
            StartupReason.APP_UPDATED -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )

            /**
             * تغيّر وقت النظام.
             *
             * إعادة تشغيل الخدمة مباشرة بعد تغير الساعة ليست
             * حالة إقلاع، ولذلك لا يوجد BootDelay.
             */
            StartupReason.TIME_CHANGED -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )

            /**
             * تغيّر المنطقة الزمنية.
             */
            StartupReason.TIMEZONE_CHANGED -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )

            /**
             * تشغيل ناتج عن Alarm.
             *
             * لا يوجد سبب لإضافة BootDelay هنا؛ الـ Alarm ليس إقلاعًا.
             */
            StartupReason.ALARM -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )

            /**
             * تشغيل مجدول.
             */
            StartupReason.SCHEDULED -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )

            /**
             * فتح الجهاز بعد USER_UNLOCKED.
             *
             * الجهاز قد يكون قد أقلع بالفعل، لذلك لا نستخدم BootDelay.
             */
            StartupReason.USER_UNLOCKED -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )
        }

        /*
         * لا توجد حاليًا CrashRecoveryStartupPolicy مستقلة في العقد
         * الذي نعمل عليه، لذلك يبقى CRASH_RECOVERY ضمن
         * ManualStartupPolicy بدل اختراع نوع جديد قد يكسر المشروع.
         *
         * Boot فقط يستخدم BootStartupPolicy.
         */
        return when (reason) {
            StartupReason.BOOT -> BootStartupPolicy(phases)
            else -> ManualStartupPolicy(phases)
        }
    }
}