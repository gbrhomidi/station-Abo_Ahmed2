package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthStatus
import kotlinx.coroutines.CancellationException

/**
 * ═══════════════════════════════════════════════════════════════
 * مرحلة فحص صحة خدمة SMS - HealthCheckPhase
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 *
 * 1. التأكد من أن SMSService أصبحت قابلة للمراقبة بعد طلب تشغيلها.
 * 2. الاعتماد على HealthMonitor بدل التعامل المباشر مع SMSService.
 * 3. احترام CancellationToken.
 * 4. تحويل HealthStatus إلى PhaseResult.
 *
 * مبدأ معماري مهم:
 * ───────────────────────────────────────────────────────────────
 *
 * هذه المرحلة لا تقوم بتشغيل الخدمة.
 *
 * تشغيل SMSService مسؤولية ServiceLaunchPhase.
 *
 * هذه المرحلة تأتي بعد ServiceLaunch فقط، ولذلك تعتمد عليها
 * صراحةً من خلال dependencies.
 *
 * كما أن المرحلة غير حرجة:
 *
 * - Healthy    → Success
 * - Unknown    → Skipped
 * - Unhealthy  → Failure قابل لإعادة المحاولة
 *
 * فشل الفحص الصحي لا يعني بالضرورة أن عملية startup الأساسية
 * فشلت، ولذلك لا يتم اعتباره Critical.
 */
class HealthCheckPhase : InitializationPhase {

    companion object {
        private const val TAG = "HealthCheckPhase"
    }

    override val name: String = "HealthCheck"

    /**
     * الفحص الصحي ليس شرطًا لإكمال startup الأساسي.
     *
     * الخدمة قد تكون قد بدأت بنجاح، لكن heartbeat قد لا يكون
     * متاحًا فورًا.
     */
    override val isCritical: Boolean = false

    /**
     * لا يمكن إجراء الفحص الصحي قبل طلب تشغيل الخدمة.
     */
    override val dependencies: List<String> = listOf(
        "ServiceLaunch"
    )

    override suspend fun execute(
        ctx: InitializationContext
    ): PhaseResult {

        /*
         * احترام الإلغاء قبل بدء الفحص.
         */
        ctx.cancellationToken.throwIfCancelled()

        return try {

            /*
             * HealthMonitor هو المصدر المسؤول عن تحديد صحة الخدمة.
             *
             * لا يتم هنا الوصول المباشر إلى SMSService أو
             * SMSServiceHeartbeatProvider.
             */
            val status = ctx.healthMonitor.check()

            /*
             * فحص الإلغاء بعد العملية أيضًا، لأن check() قد يستغرق
             * وقتًا قبل إرجاع النتيجة.
             */
            ctx.cancellationToken.throwIfCancelled()

            when (status) {

                is HealthStatus.Healthy -> {

                    ctx.logger.logHealthCheck(
                        "HEALTHY",
                        null,
                        ctx.correlationId
                    )

                    PhaseResult.Success(
                        "Service healthy"
                    )
                }

                is HealthStatus.Unhealthy -> {

                    ctx.logger.logHealthCheck(
                        "UNHEALTHY",
                        status.reason,
                        ctx.correlationId
                    )

                    /*
                     * المرحلة غير حرجة، لذلك لا يمنع هذا الفشل
                     * استمرار pipeline.
                     *
                     * retryable=true يسمح لـ InitializationPipeline
                     * بتطبيق RetryPolicy على الفحص الصحي.
                     */
                    PhaseResult.Failure(
                        error = "Health check failed: ${status.reason}",
                        retryable = true
                    )
                }

                is HealthStatus.Unknown -> {

                    ctx.logger.logHealthCheck(
                        "UNKNOWN",
                        status.reason,
                        ctx.correlationId
                    )

                    /*
                     * Unknown تعني أن حالة الخدمة لم تصبح مؤكدة
                     * بعد، وليست بالضرورة فشلًا حقيقيًا.
                     *
                     * لذلك نحافظ على الدلالة الحالية Skipped.
                     */
                    PhaseResult.Skipped(
                        status.reason
                    )
                }
            }

        } catch (e: CancellationException) {

            /*
             * لا يجب ابتلاع CancellationException.
             *
             * يجب أن تصل إلى ApplicationInitializationCoordinator
             * ليتم التعامل معها كإلغاء حقيقي للـ startup pipeline.
             */
            throw e

        } catch (e: Exception) {

            /*
             * حماية من أي استثناء غير متوقع صادر من HealthMonitor.
             *
             * بما أن المرحلة غير حرجة، فلن يؤدي هذا الفشل إلى
             * إيقاف المراحل الحرجة السابقة، لكنه سيظهر في نتيجة
             * المرحلة ويسمح للـ RetryPolicy بالتعامل معه.
             */
            ctx.logger.logHealthCheck(
                "ERROR",
                e.message ?: e.javaClass.simpleName,
                ctx.correlationId
            )

            PhaseResult.Failure(
                error = "Unexpected health check error: " +
                    (e.message ?: e.javaClass.simpleName),
                retryable = true
            )
        }
    }
}