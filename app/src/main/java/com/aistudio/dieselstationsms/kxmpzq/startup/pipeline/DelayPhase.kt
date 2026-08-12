package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import kotlinx.coroutines.delay

/**
 * ═══════════════════════════════════════════════════════════════
 * مرحلة تأخير الإقلاع - DelayPhase
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 *
 * 1. تطبيق تأخير الإقلاع عند بدء التشغيل بسبب BOOT فقط.
 * 2. عدم تأخير بقية أسباب startup مثل:
 *    - MANUAL
 *    - CRASH_RECOVERY
 *    - APP_UPDATED
 *    - TIME_CHANGED
 *    - TIMEZONE_CHANGED
 *    - ALARM
 *    - SCHEDULED
 *    - USER_UNLOCKED
 *
 * 3. استخدام ConfigurationProvider بدل القيم الثابتة.
 * 4. احترام CancellationToken أثناء فترة التأخير.
 *
 * مبدأ معماري مهم:
 * ───────────────────────────────────────────────────────────────
 *
 * هذه المرحلة لا تقوم بتشغيل أي Service.
 *
 * كما أنها لا تتعامل مع Android Service lifecycle.
 *
 * مسؤوليتها الوحيدة هي تأخير مسار BOOT وفق الإعداد المركزي.
 */
class DelayPhase : InitializationPhase {

    companion object {
        private const val DEFAULT_STEPS = 10
    }

    /**
     * الاسم الذي يستخدمه:
     *
     * - PhaseRegistry
     * - StartupPolicyFactory
     * - DagEngine
     */
    override val name: String = "BootDelay"

    /**
     * التأخير ليس مرحلة حرجة.
     *
     * عدم تطبيق التأخير لا يعني أن startup فشل.
     */
    override val isCritical: Boolean = false

    override suspend fun execute(
        ctx: InitializationContext
    ): PhaseResult {

        /*
         * احترام الإلغاء قبل بدء أي معالجة.
         */
        ctx.cancellationToken.throwIfCancelled()

        /*
         * BootDelay مطلوب فقط عندما يكون سبب startup هو BOOT.
         *
         * بالنسبة لبقية الأسباب، نتجاوز المرحلة بصورة طبيعية.
         */
        if (ctx.startupReason != StartupReason.BOOT) {
            return PhaseResult.Skipped(
                "Delay only for BOOT"
            )
        }

        /*
         * قراءة قيمة التأخير من ConfigurationProvider.
         *
         * لا توجد قيمة hard-coded للتأخير نفسه هنا.
         */
        val delayMs = ctx.config.getBootDelayMs()

        /*
         * حماية من الإعدادات غير الصالحة.
         *
         * إذا كانت القيمة صفرًا أو سالبة، لا يوجد سبب لتنفيذ
         * delay غير ضروري.
         */
        if (delayMs <= 0L) {
            ctx.cancellationToken.throwIfCancelled()

            return PhaseResult.Success(
                "Boot delay skipped: ${delayMs}ms"
            )
        }

        /*
         * تقسيم التأخير إلى عدة فترات صغيرة.
         *
         * السبب:
         *
         * CancellationToken الخاص بالمشروع ليس مرتبطًا مباشرةً
         * بـ kotlinx.coroutines cancellation.
         *
         * لذلك يجب إعادة فحصه أثناء فترة الانتظار حتى لا يبقى
         * startup في DelayPhase طوال مدة التأخير بعد طلب الإلغاء.
         */
        val steps = DEFAULT_STEPS

        /*
         * نستخدم ceil-style calculation بشكل آمن بحيث لا يؤدي
         * delayMs الأصغر من عدد الخطوات إلى stepDelay = 0.
         */
        val stepDelay = maxOf(
            1L,
            (delayMs + steps - 1L) / steps
        )

        var elapsed = 0L

        while (elapsed < delayMs) {

            /*
             * فحص الإلغاء قبل كل فترة انتظار.
             */
            ctx.cancellationToken.throwIfCancelled()

            val remaining = delayMs - elapsed
            val currentDelay = minOf(
                stepDelay,
                remaining
            )

            /*
             * kotlinx.coroutines.delay نفسها قابلة للإلغاء إذا
             * تم إلغاء Coroutine التي تشغل pipeline.
             */
            delay(currentDelay)

            elapsed += currentDelay
        }

        /*
         * فحص نهائي حتى لا نعلن نجاح المرحلة إذا تم طلب الإلغاء
         * أثناء آخر فترة انتظار.
         */
        ctx.cancellationToken.throwIfCancelled()

        return PhaseResult.Success(
            "Delay completed: ${delayMs}ms"
        )
    }
}