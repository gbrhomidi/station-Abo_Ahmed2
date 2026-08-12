package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceLaunchResult

/**
 * ═══════════════════════════════════════════════════════════════
 * مرحلة تشغيل خدمة SMS - ServiceLaunchPhase
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 * 1. تنفيذ طلب تشغيل SMSService من خلال ServiceLauncher.
 * 2. احترام CancellationToken قبل بدء عملية التشغيل.
 * 3. تحويل نتيجة ServiceLauncher إلى PhaseResult.
 * 4. الحفاظ على دلالة Success / AlreadyRunning / Failure.
 * 5. عدم تنفيذ منطق تشغيل Android Service مباشرة هنا.
 *
 * مبدأ معماري مهم:
 * ───────────────────────────────────────────────────────────────
 * هذه المرحلة لا تستدعي SMSService مباشرة، ولا تتعامل مع
 * Context.startService() أو startForegroundService().
 *
 * المسؤول عن ذلك هو ServiceLauncher، وتحديدًا SmsServiceLauncher
 * في البنية الحالية للمشروع.
 *
 * كما أن هذه المرحلة لا تفترض أن طلب التشغيل يعني أن الخدمة
 * أصبحت "حية" بالفعل؛ فهي تعتمد على النتيجة التي يعيدها
 * ServiceLauncher فقط.
 */
class ServiceLaunchPhase : InitializationPhase {

    companion object {
        private const val TAG = "ServiceLaunchPhase"
    }

    override val name: String = "ServiceLaunch"

    /**
     * تشغيل هذه المرحلة يُعد حرجًا بالنسبة لمسار startup.
     *
     * السبب:
     * إذا تعذر إطلاق SMSService، فلا ينبغي اعتبار تهيئة
     * منظومة SMS ناجحة بالكامل.
     */
    override val isCritical: Boolean = true

    /**
     * تنفيذ مرحلة تشغيل خدمة SMS.
     *
     * لا يتم هنا تشغيل الخدمة مباشرة، بل يتم تفويض ذلك إلى
     * ServiceLauncher حتى تبقى مسؤوليات startup مفصولة عن
     * تفاصيل Android Service lifecycle.
     */
    override suspend fun execute(ctx: InitializationContext): PhaseResult {

        // يجب احترام طلب الإلغاء قبل بدء أي عملية تشغيل.
        ctx.cancellationToken.throwIfCancelled()

        return try {

            Log.d(
                TAG,
                "Launching SMS service. reason=${ctx.startupReason.name}"
            )

            /*
             * ServiceLauncher هو المسؤول الفعلي عن:
             *
             * - إنشاء Intent.
             * - اختيار startForegroundService / startService.
             * - التعامل مع إصدار Android.
             * - تحديث ServiceStatusRepository.
             * - تحديد ما إذا كانت الخدمة تعمل بالفعل.
             */
            when (
                val result = ctx.serviceLauncher.launch(ctx.startupReason)
            ) {

                is ServiceLaunchResult.Success -> {
                    Log.i(
                        TAG,
                        "SMS service launch request succeeded: ${result.message ?: "no message"}"
                    )

                    PhaseResult.Success(result.message)
                }

                is ServiceLaunchResult.AlreadyRunning -> {
                    Log.i(
                        TAG,
                        "SMS service is already running: ${result.message ?: "no message"}"
                    )

                    /*
                     * AlreadyRunning ليست حالة فشل.
                     *
                     * لذلك نحافظ على الدلالة الأصلية ونحولها إلى
                     * Skipped بدل Failure.
                     */
                    PhaseResult.Skipped(
                        result.message ?: "SMS service already running"
                    )
                }

                is ServiceLaunchResult.Failure -> {
                    Log.e(
                        TAG,
                        "Failed to launch SMS service: " +
                            "${result.error}; retryable=${result.retryable}"
                    )

                    /*
                     * لا نقرر هنا هل تتم إعادة المحاولة أم لا.
                     *
                     * ServiceLauncher هو الذي حدد retryable،
                     * والـ InitializationPipeline / RetryPolicy
                     * يتولى التعامل مع ذلك وفق سياسة startup.
                     */
                    PhaseResult.Failure(
                        error = result.error,
                        retryable = result.retryable
                    )
                }
            }

        } catch (e: kotlinx.coroutines.CancellationException) {

            /*
             * لا يجب ابتلاع CancellationException.
             *
             * يجب أن تصل إلى طبقة إدارة الإلغاء في الـ pipeline
             * حتى يتم إيقاف startup بصورة صحيحة.
             */
            Log.i(
                TAG,
                "SMS service launch phase cancelled"
            )

            throw e

        } catch (e: SecurityException) {

            /*
             * SecurityException غالبًا تعني أن النظام منع عملية
             * تشغيل الخدمة.
             *
             * لا نحولها إلى Success ولا نخفيها.
             *
             * نعتبرها غير قابلة لإعادة المحاولة من هذه المرحلة،
             * لأن إعادة نفس العملية دون تغيير السبب الأمني لن تحل
             * المشكلة.
             */
            Log.e(
                TAG,
                "Security exception while launching SMS service",
                e
            )

            PhaseResult.Failure(
                error = "Security error while launching SMS service: " +
                    (e.message ?: e.javaClass.simpleName),
                retryable = false
            )

        } catch (e: Exception) {

            /*
             * حماية أخيرة من أي استثناء غير متوقع صادر من
             * ServiceLauncher أو أحد مكوناته.
             *
             * لا نعتبر العملية ناجحة عند حدوث استثناء.
             *
             * نسمح للـ pipeline بمعالجة الفشل وفق سياسة retry
             * العامة، ولذلك نضع retryable=true هنا.
             */
            Log.e(
                TAG,
                "Unexpected error while launching SMS service",
                e
            )

            PhaseResult.Failure(
                error = "Unexpected SMS service launch error: " +
                    (e.message ?: e.javaClass.simpleName),
                retryable = true
            )
        }
    }
}