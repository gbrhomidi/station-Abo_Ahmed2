package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.startup.*
import com.aistudio.dieselstationsms.kxmpzq.startup.event.EventBus
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy
import kotlinx.coroutines.*

/**
 * ═══════════════════════════════════════════════════════════════
 * خط تنفيذ تهيئة التطبيق - InitializationPipeline
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 * 1. التحقق من صحة DAG الخاص بمراحل التهيئة.
 * 2. ترتيب المراحل حسب الاعتماديات.
 * 3. تنفيذ المراحل بالتوازي أو بالتسلسل وفق StartupPolicy.
 * 4. احترام CancellationToken.
 * 5. تطبيق timeout مستقل لكل مرحلة.
 * 6. تطبيق RetryPolicy على المراحل القابلة لإعادة المحاولة.
 * 7. إيقاف الـ pipeline عند فشل مرحلة حرجة.
 * 8. تسجيل أحداث بدء/نجاح/تخطي/فشل المراحل.
 *
 * ملاحظات تكاملية:
 * - لا يقوم هذا الملف بإنشاء ServiceLauncher أو تشغيل SMSService
 *   مباشرة؛ مسؤولية ذلك تقع على مراحل التهيئة المناسبة.
 * - لا يغيّر تعريف StartupPolicy أو InitializationPhase أو PhaseResult.
 * - لا يفترض وجود StartupCoordinator أو أي ملف آخر غير مستخدم
 *   أصلًا في هذا المسار.
 */
class InitializationPipeline(
    private val phases: List<InitializationPhase>,
    private val eventBus: EventBus,
    private val stateMachine: StartupStateMachine,
    private val retryPolicy: RetryPolicy
) {

    companion object {
        private const val TAG = "InitPipeline"
    }

    /**
     * نتيجة تنفيذ الـ Pipeline بالكامل.
     */
    data class PipelineResult(
        val success: Boolean,
        val completedPhases: List<String>,
        val skippedPhases: List<String>,
        val failedPhase: String? = null,
        val error: String? = null
    )

    /**
     * تنفيذ جميع مراحل التهيئة حسب الـ DAG والسياسة المحددة.
     */
    suspend fun execute(
        ctx: InitializationContext,
        policy: StartupPolicy
    ): PipelineResult {

        /*
         * لا نسمح بتنفيذ Pipeline غير صالح.
         * DagEngine هو المسؤول عن التحقق من:
         * - الاعتماديات
         * - الدورات
         * - المراحل غير القابلة للوصول
         * - ترتيب التنفيذ
         */
        val dag = DagEngine(phases)
        val dagResult = dag.validateAndSort()

        if (!dagResult.isValid) {
            return PipelineResult(
                success = false,
                completedPhases = emptyList(),
                skippedPhases = emptyList(),
                failedPhase = "DAG",
                error = dagResult.error
            )
        }

        val completed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val executed = mutableSetOf<String>()

        /*
         * إشعار بداية الـ Pipeline.
         */
        eventBus.emit(
            StartupEvent.PipelineStarted(
                ctx.startupReason,
                ctx.correlationId
            )
        )

        /*
         * نستمر حتى تتم معالجة جميع المراحل.
         */
        while (executed.size < phases.size) {

            /*
             * يجب فحص الإلغاء قبل كل دورة، وليس فقط داخل المرحلة،
             * حتى لا يستمر الـ Pipeline في تشغيل مراحل جديدة بعد الإلغاء.
             */
            ctx.cancellationToken.throwIfCancelled()

            /*
             * الحصول على المراحل التي أصبحت جاهزة وفق DAG.
             */
            val ready = dag.getReadyPhases(executed)

            /*
             * عدم وجود مراحل جاهزة مع وجود مراحل لم تُنفذ يعني
             * أن DAG عالق أو أن هناك اعتماديات غير قابلة للحل.
             */
            if (ready.isEmpty() && executed.size < phases.size) {
                return PipelineResult(
                    success = false,
                    completedPhases = completed,
                    skippedPhases = skipped,
                    failedPhase = "DAG",
                    error = "Stuck: ${phases.map { it.name } - executed}"
                )
            }

            /*
             * تنفيذ المراحل الجاهزة:
             *
             * - بالتوازي إذا سمحت السياسة.
             * - بالتسلسل إذا لم تسمح.
             *
             * كل مرحلة تحصل على executePhase() الخاص بها،
             * وبالتالي تخضع للـ timeout والـ retry.
             */
            val results = if (policy.allowParallelExecution) {
                coroutineScope {
                    ready.map { phase ->
                        async {
                            executePhase(ctx, phase)
                        }
                    }.awaitAll()
                }
            } else {
                ready.map { phase ->
                    executePhase(ctx, phase)
                }
            }

            /*
             * ربط كل نتيجة بالمرحلة التي أنتجتها.
             *
             * ready و results يحافظان على نفس الترتيب:
             * ready[0] -> results[0]
             * ready[1] -> results[1]
             * ...
             */
            for ((phase, result) in ready.zip(results)) {

                /*
                 * بمجرد الحصول على نتيجة للمرحلة، تعتبر معالجة في هذه
                 * الدورة ولا يعاد تشغيلها من الـ DAG تلقائيًا.
                 *
                 * إعادة المحاولة الداخلية تتم داخل executeWithRetry().
                 */
                executed.add(phase.name)

                when (result) {

                    /*
                     * المرحلة نجحت.
                     */
                    is PhaseResult.Success -> {
                        completed.add(phase.name)

                        eventBus.emit(
                            StartupEvent.PhaseCompleted(
                                phase.name,
                                result.message ?: "OK",
                                ctx.correlationId
                            )
                        )
                    }

                    /*
                     * المرحلة تم تخطيها بصورة مقصودة.
                     */
                    is PhaseResult.Skipped -> {
                        skipped.add(phase.name)

                        eventBus.emit(
                            StartupEvent.PhaseSkipped(
                                phase.name,
                                result.reason,
                                ctx.correlationId
                            )
                        )
                    }

                    /*
                     * المرحلة فشلت.
                     */
                    is PhaseResult.Failure -> {
                        eventBus.emit(
                            StartupEvent.PhaseFailed(
                                phase.name,
                                result.error,
                                ctx.correlationId
                            )
                        )

                        /*
                         * المرحلة الحرجة توقف الـ Pipeline بالكامل.
                         *
                         * لا نسمح بمتابعة مراحل تعتمد منطقيًا على
                         * مرحلة حرجة فاشلة.
                         */
                        if (phase.isCritical) {
                            return PipelineResult(
                                success = false,
                                completedPhases = completed,
                                skippedPhases = skipped,
                                failedPhase = phase.name,
                                error = result.error
                            )
                        }

                        /*
                         * المرحلة غير الحرجة:
                         * نسجلها ضمن skipped وفق العقد الحالي
                         * لـ PipelineResult، ثم نسمح لبقية الـ Pipeline
                         * بالاستمرار.
                         */
                        skipped.add(phase.name)
                    }
                }
            }
        }

        /*
         * الوصول إلى هنا يعني أن جميع مراحل الـ DAG تمت معالجتها
         * دون فشل حرج.
         */
        return PipelineResult(
            success = true,
            completedPhases = completed,
            skippedPhases = skipped
        )
    }

    /**
     * تنفيذ مرحلة واحدة مع:
     * - تسجيل البداية.
     * - timeout مستقل.
     * - retry حسب RetryPolicy.
     */
    private suspend fun executePhase(
        ctx: InitializationContext,
        phase: InitializationPhase
    ): PhaseResult {

        ctx.cancellationToken.throwIfCancelled()

        ctx.logger.logPhaseStarted(
            phase.name,
            ctx.correlationId
        )

        eventBus.emit(
            StartupEvent.PhaseStarted(
                phase.name,
                ctx.correlationId
            )
        )

        return try {

            /*
             * timeout خاص بالمرحلة.
             *
             * إذا انتهى الوقت، تتحول النتيجة إلى Failure قابلة
             * لإعادة المحاولة، وهو ما يسمح لـ executeWithRetry()
             * بتطبيق السياسة المحددة.
             */
            withTimeout(phase.timeoutMs) {
                executeWithRetry(ctx, phase)
            }

        } catch (e: TimeoutCancellationException) {

            /*
             * Timeout المرحلة لا يجب أن يسقط الـ Pipeline مباشرة.
             * يتم إرجاع Failure retryable حتى تستطيع RetryPolicy
             * تقرير ما إذا كانت هناك محاولة أخرى.
             */
            PhaseResult.Failure(
                error = "Timeout after ${phase.timeoutMs}ms",
                retryable = true
            )

        } catch (e: CancellationException) {

            /*
             * الإلغاء ليس Failure عاديًا.
             *
             * يجب إعادة رمي CancellationException حتى تنتشر
             * عملية الإلغاء إلى coroutine الأب ولا يتم تحويلها
             * إلى خطأ قابل لإعادة المحاولة.
             */
            throw e

        } catch (e: Exception) {

            /*
             * أي استثناء غير متوقع أثناء تنفيذ المرحلة.
             *
             * نعيده كـ Failure حتى يتمكن RetryPolicy من التعامل
             * معه في المستوى المناسب.
             */
            PhaseResult.Failure(
                error = e.message ?: "Unknown error",
                retryable = false
            )
        }
    }

    /**
     * تنفيذ المرحلة مع RetryPolicy.
     */
    private suspend fun executeWithRetry(
        ctx: InitializationContext,
        phase: InitializationPhase
    ): PhaseResult {

        /*
         * ضمان وجود محاولة واحدة على الأقل حتى لو كان
         * maxAttempts مضبوطًا بشكل غير صحيح إلى قيمة صفر.
         */
        val maxAttempts = retryPolicy.maxAttempts.coerceAtLeast(1)

        for (attempt in 0 until maxAttempts) {

            /*
             * فحص الإلغاء قبل كل محاولة.
             */
            ctx.cancellationToken.throwIfCancelled()

            try {

                val result = phase.execute(ctx)

                when (result) {

                    /*
                     * النجاح: ننهي المحاولة مباشرة.
                     */
                    is PhaseResult.Success -> {
                        return result
                    }

                    /*
                     * التخطي: لا معنى لإعادة المحاولة.
                     */
                    is PhaseResult.Skipped -> {
                        return result
                    }

                    /*
                     * Failure:
                     * نعيد المحاولة فقط إذا كانت المرحلة نفسها
                     * قابلة لإعادة المحاولة ولم نصل إلى آخر محاولة.
                     */
                    is PhaseResult.Failure -> {

                        val hasAttemptsRemaining =
                            attempt < maxAttempts - 1

                        if (result.retryable && hasAttemptsRemaining) {

                            Log.w(
                                TAG,
                                "${phase.name} retry " +
                                    "${attempt + 1}/$maxAttempts"
                            )

                            /*
                             * احترام سياسة التأخير بين المحاولات.
                             */
                            val delayMs = retryPolicy
                                .getDelayMs(attempt)
                                .coerceAtLeast(0L)

                            if (delayMs > 0L) {
                                delay(delayMs)
                            }

                            continue
                        }

                        /*
                         * إما أن الخطأ غير قابل لإعادة المحاولة
                         * أو انتهت جميع المحاولات.
                         */
                        return result
                    }
                }

            } catch (e: CancellationException) {

                /*
                 * لا نعيد محاولة CancellationException.
                 * هذا مهم جدًا حتى لا يتحول إلغاء Startup إلى
                 * retry غير مرغوب فيه.
                 */
                throw e

            } catch (e: Exception) {

                /*
                 * الاستثناءات غير المتوقعة تخضع لـ RetryPolicy.
                 */
                val hasAttemptsRemaining =
                    attempt < maxAttempts - 1

                if (
                    hasAttemptsRemaining &&
                    retryPolicy.shouldRetry(attempt, e)
                ) {

                    Log.w(
                        TAG,
                        "${phase.name} exception retry " +
                            "${attempt + 1}/$maxAttempts: ${e.message}"
                    )

                    val delayMs = retryPolicy
                        .getDelayMs(attempt)
                        .coerceAtLeast(0L)

                    if (delayMs > 0L) {
                        delay(delayMs)
                    }

                } else {

                    return PhaseResult.Failure(
                        error = e.message ?: "Unknown",
                        retryable = false
                    )
                }
            }
        }

        /*
         * لا يفترض الوصول إلى هنا، لأن maxAttempts >= 1.
         * لكنه fallback آمن في حال تغير تنفيذ RetryPolicy.
         */
        return PhaseResult.Failure(
            error = "All retries exhausted",
            retryable = false
        )
    }
}