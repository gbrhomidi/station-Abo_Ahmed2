package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.startup.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.event.EventBus
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.metrics.MetricsCollector
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPipeline
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.PhaseRegistry
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════
 * منسق تهيئة التطبيق - ApplicationInitializationCoordinator
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 * 1. استقبال طلبات بدء التشغيل.
 * 2. إنشاء سياق التهيئة لكل عملية تشغيل.
 * 3. إنشاء وتنفيذ Startup Policy و Initialization Pipeline.
 * 4. إدارة حالة التشغيل عبر StartupStateMachine.
 * 5. دعم الإلغاء عبر CancellationRegistry / CancellationToken.
 * 6. تسجيل المقاييس والأحداث والسجلات.
 * 7. ضمان تنظيف موارد كل عملية تشغيل.
 * 8. ضمان إعادة StartupStateMachine إلى IDLE بعد انتهاء العملية.
 *
 * ملاحظات تكاملية:
 * - InitializationPipeline يبقى مسؤولاً عن ترتيب الـ Phases
 *   وإعادة المحاولة وTimeout الخاص بكل Phase.
 * - StartupExecutionGuard يبقى مسؤولاً عن سياسة السماح بالتنفيذ
 *   المتوازي حسب StartupPolicy.
 * - CancellationRegistry يبقى مصدر التحكم الخارجي بالإلغاء.
 * - لا يتم اختراع StartupCoordinator آخر؛ هذا الملف هو منسق
 *   التهيئة الموجود فعلياً في المشروع.
 */
class ApplicationInitializationCoordinator(
    private val config: ConfigurationProvider,
    private val eventBus: EventBus,
    private val stateMachine: StartupStateMachine,
    private val metricsCollector: MetricsCollector,
    private val executionGuard: StartupExecutionGuard,
    private val cancellationRegistry: CancellationRegistry,
    private val phaseRegistry: PhaseRegistry,
    private val loggerFactory: (Context) -> StartupLogger,
    private val launcherFactory: (Context) -> ServiceLauncher,
    private val healthMonitorFactory: () -> HealthMonitor,
    private val retryPolicyFactory: () -> RetryPolicy
) {

    companion object {
        private const val TAG = "InitCoordinator"
    }

    /**
     * نطاق مستقل للمنسق.
     *
     * SupervisorJob يمنع فشل عملية تشغيل واحدة من إسقاط
     * عمليات التشغيل الأخرى.
     */
    private val coordinatorScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineName("InitCoordinator")
    )

    /**
     * StartupStateMachine الحالية في المشروع مشتركة بين عمليات
     * التهيئة، لذلك يجب حماية انتقالات الحالة على مستوى المنسق.
     *
     * هذا لا يلغي StartupExecutionGuard؛ بل يمنع فقط حدوث سباق
     * على آلة الحالات نفسها.
     */
    private val stateTransitionMutex = Mutex()

    /**
     * تنفيذ عملية الانتقال مع حماية Mutex.
     */
    private suspend fun transitionState(
        state: StartupStateMachine.State
    ): Boolean {
        return stateTransitionMutex.withLock {
            stateMachine.transition(state)
        }
    }

    /**
     * بدء عملية تهيئة جديدة.
     *
     * جميع الوظائف العامة الموجودة في النسخة الأصلية محفوظة:
     * - execute(...)
     * - cancel(...)
     * - cancelAll()
     * - activeCount()
     */
    fun execute(
        context: Context,
        reason: StartupReason,
        action: String? = null,
        onComplete: () -> Unit
    ) {
        val appContext = context.applicationContext
        val startTime = SystemClock.elapsedRealtime()
        val correlationId = generateCorrelationId()

        val cancellationToken = CancellationToken()
        cancellationRegistry.register(
            correlationId,
            cancellationToken
        )

        val logger = try {
            loggerFactory(appContext)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to create StartupLogger | $correlationId",
                e
            )

            cancellationRegistry.remove(correlationId)
            safelyComplete(onComplete, correlationId)
            return
        }

        val serviceLauncher = try {
            launcherFactory(appContext)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to create ServiceLauncher | $correlationId",
                e
            )

            cancellationRegistry.remove(correlationId)
            safelyComplete(onComplete, correlationId)
            return
        }

        val healthMonitor = try {
            healthMonitorFactory()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to create HealthMonitor | $correlationId",
                e
            )

            cancellationRegistry.remove(correlationId)
            safelyComplete(onComplete, correlationId)
            return
        }

        val retryPolicy = try {
            retryPolicyFactory()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to create RetryPolicy | $correlationId",
                e
            )

            cancellationRegistry.remove(correlationId)
            safelyComplete(onComplete, correlationId)
            return
        }

        val initContext = InitializationContext(
            appContext = appContext,
            startupReason = reason,
            logger = logger,
            serviceLauncher = serviceLauncher,
            config = config,
            healthMonitor = healthMonitor,
            retryPolicy = retryPolicy,
            correlationId = correlationId,
            cancellationToken = cancellationToken
        )

        try {
            logger.logBootReceived(
                reason,
                action,
                correlationId
            )
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to log boot received | $correlationId",
                e
            )
        }

        coordinatorScope.launch {
            var terminalStateReached = false

            try {
                /**
                 * يجب أن تبدأ كل عملية جديدة من IDLE.
                 *
                 * إذا كانت آلة الحالات مشغولة بعملية أخرى، لا نقوم
                 * بإجبارها على الانتقال إلى PREPARING.
                 */
                val prepared = transitionState(
                    StartupStateMachine.State.PREPARING
                )

                if (!prepared) {
                    val currentState = stateMachine.getCurrentState()

                    try {
                        logger.logCancelled(
                            correlationId,
                            "Invalid startup state: $currentState"
                        )
                    } catch (logError: Exception) {
                        Log.w(
                            TAG,
                            "Failed to log invalid startup state | $correlationId",
                            logError
                        )
                    }

                    recordMetricSafely(
                        "startup_rejected",
                        1,
                        correlationId
                    )

                    return@launch
                }

                val policy = try {
                    StartupPolicyFactory.create(
                        reason,
                        phaseRegistry
                    )
                } catch (e: Exception) {
                    transitionState(
                        StartupStateMachine.State.FAILED
                    )
                    terminalStateReached = true

                    val errorMessage =
                        e.message ?: "Failed to create startup policy"

                    recordMetricSafely(
                        "startup_policy_failed",
                        1,
                        correlationId
                    )

                    try {
                        logger.logPipelineFailed(
                            "Policy",
                            errorMessage,
                            correlationId
                        )
                    } catch (logError: Exception) {
                        Log.w(
                            TAG,
                            "Failed to log policy failure | $correlationId",
                            logError
                        )
                    }

                    eventBus.emit(
                        StartupEvent.PipelineFailed(
                            "Policy",
                            errorMessage,
                            correlationId
                        )
                    )

                    Log.e(
                        TAG,
                        "❌ Startup policy creation failed: $errorMessage | $correlationId",
                        e
                    )

                    return@launch
                }

                val pipeline = InitializationPipeline(
                    policy.phases,
                    eventBus,
                    stateMachine,
                    retryPolicy
                )

                /**
                 * الانتقال إلى RUNNING يجب أن ينجح قبل بدء الـ Pipeline.
                 */
                val running = transitionState(
                    StartupStateMachine.State.RUNNING
                )

                if (!running) {
                    val currentState = stateMachine.getCurrentState()
                    val errorMessage =
                        "Unable to transition to RUNNING from $currentState"

                    transitionState(
                        StartupStateMachine.State.FAILED
                    )
                    terminalStateReached = true

                    recordMetricSafely(
                        "startup_state_transition_failed",
                        1,
                        correlationId
                    )

                    try {
                        logger.logPipelineFailed(
                            "Coordinator",
                            errorMessage,
                            correlationId
                        )
                    } catch (logError: Exception) {
                        Log.w(
                            TAG,
                            "Failed to log state transition failure | $correlationId",
                            logError
                        )
                    }

                    eventBus.emit(
                        StartupEvent.PipelineFailed(
                            "Coordinator",
                            errorMessage,
                            correlationId
                        )
                    )

                    Log.e(
                        TAG,
                        "❌ $errorMessage | $correlationId"
                    )

                    return@launch
                }

                /**
                 * فحص الإلغاء قبل بدء التنفيذ الفعلي.
                 */
                cancellationToken.throwIfCancelled()

                val result = executionGuard.execute(policy) {
                    cancellationToken.throwIfCancelled()

                    withTimeout(
                        config.getPipelineTimeoutMs()
                    ) {
                        pipeline.execute(
                            initContext,
                            policy
                        )
                    }
                }

                val duration =
                    SystemClock.elapsedRealtime() - startTime

                if (config.getMetricsEnabled()) {
                    metricsCollector.record(
                        "startup_duration_ms",
                        duration
                    )

                    metricsCollector.record(
                        "startup_reason",
                        reason.name
                    )

                    metricsCollector.record(
                        "completed_phases",
                        result.completedPhases.size
                    )

                    metricsCollector.record(
                        "skipped_phases",
                        result.skippedPhases.size
                    )

                    metricsCollector.record(
                        "correlation_id",
                        correlationId
                    )
                }

                if (result.success) {
                    val completed = transitionState(
                        StartupStateMachine.State.COMPLETED
                    )

                    terminalStateReached = completed

                    if (!completed) {
                        Log.e(
                            TAG,
                            "❌ Failed to transition to COMPLETED | " +
                                "current=${stateMachine.getCurrentState()} | " +
                                "$correlationId"
                        )
                    }

                    try {
                        logger.logPipelineSuccess(
                            duration,
                            result.completedPhases,
                            correlationId
                        )
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Failed to log pipeline success | $correlationId",
                            e
                        )
                    }

                    eventBus.emit(
                        StartupEvent.PipelineCompleted(
                            duration,
                            result.completedPhases,
                            correlationId
                        )
                    )

                    Log.i(
                        TAG,
                        "✅ Pipeline completed in ${duration}ms | " +
                            "$correlationId"
                    )
                } else {
                    val failedPhase =
                        result.failedPhase ?: "Unknown"

                    val error =
                        result.error ?: "Unknown"

                    val failed = transitionState(
                        StartupStateMachine.State.FAILED
                    )

                    terminalStateReached = failed

                    if (config.getMetricsEnabled()) {
                        metricsCollector.record(
                            "failed_phase",
                            failedPhase
                        )
                    }

                    try {
                        logger.logPipelineFailed(
                            failedPhase,
                            error,
                            correlationId
                        )
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Failed to log pipeline failure | $correlationId",
                            e
                        )
                    }

                    eventBus.emit(
                        StartupEvent.PipelineFailed(
                            failedPhase,
                            error,
                            correlationId
                        )
                    )

                    Log.e(
                        TAG,
                        "❌ Pipeline failed at $failedPhase: " +
                            "$error | $correlationId"
                    )
                }
            } catch (e: TimeoutCancellationException) {
                val transitioned = transitionState(
                    StartupStateMachine.State.FAILED
                )

                terminalStateReached = transitioned

                val errorMessage = "Pipeline timeout"

                if (config.getMetricsEnabled()) {
                    metricsCollector.record(
                        "startup_timeout",
                        1
                    )
                }

                try {
                    logger.logPipelineFailed(
                        "Coordinator",
                        errorMessage,
                        correlationId
                    )
                } catch (logError: Exception) {
                    Log.w(
                        TAG,
                        "Failed to log timeout | $correlationId",
                        logError
                    )
                }

                eventBus.emit(
                    StartupEvent.PipelineFailed(
                        "Coordinator",
                        errorMessage,
                        correlationId
                    )
                )

                Log.e(
                    TAG,
                    "⏱️ Pipeline timeout | $correlationId",
                    e
                )
            } catch (e: CancellationException) {
                val transitioned = transitionState(
                    StartupStateMachine.State.CANCELLED
                )

                terminalStateReached = transitioned

                val message =
                    e.message ?: "Startup cancelled"

                if (config.getMetricsEnabled()) {
                    metricsCollector.record(
                        "startup_cancelled",
                        1
                    )
                }

                try {
                    logger.logCancelled(
                        correlationId,
                        message
                    )
                } catch (logError: Exception) {
                    Log.w(
                        TAG,
                        "Failed to log cancellation | $correlationId",
                        logError
                    )
                }

                eventBus.emit(
                    StartupEvent.Cancelled(
                        correlationId,
                        message
                    )
                )

                Log.w(
                    TAG,
                    "🚫 Cancelled: $reason | $correlationId"
                )
            } catch (e: Exception) {
                val transitioned = transitionState(
                    StartupStateMachine.State.FAILED
                )

                terminalStateReached = transitioned

                val errorMessage =
                    e.message ?: "Unexpected startup error"

                if (config.getMetricsEnabled()) {
                    metricsCollector.record(
                        "startup_exception",
                        1
                    )
                }

                try {
                    logger.logPipelineFailed(
                        "Coordinator",
                        errorMessage,
                        correlationId
                    )
                } catch (logError: Exception) {
                    Log.w(
                        TAG,
                        "Failed to log unexpected startup error | $correlationId",
                        logError
                    )
                }

                eventBus.emit(
                    StartupEvent.PipelineFailed(
                        "Coordinator",
                        errorMessage,
                        correlationId
                    )
                )

                Log.e(
                    TAG,
                    "💥 Unexpected: $errorMessage | $correlationId",
                    e
                )
            } finally {
                /**
                 * إزالة عملية التشغيل من سجل الإلغاء مهما كانت
                 * نتيجة التنفيذ.
                 */
                cancellationRegistry.remove(correlationId)

                /**
                 * StartupStateMachine مشتركة على مستوى المنسق.
                 *
                 * لذلك يجب إعادتها إلى IDLE بعد الوصول إلى حالة
                 * نهائية حتى يمكن لعملية تشغيل لاحقة البدء من جديد.
                 */
                if (terminalStateReached) {
                    transitionToIdleSafely(correlationId)
                }

                safelyComplete(
                    onComplete,
                    correlationId
                )
            }
        }
    }

    /**
     * إلغاء عملية تهيئة محددة.
     */
    fun cancel(correlationId: String): Boolean {
        return cancellationRegistry.cancel(correlationId)
    }

    /**
     * إلغاء جميع عمليات التهيئة المسجلة.
     */
    fun cancelAll() {
        cancellationRegistry.cancelAll()
    }

    /**
     * عدد عمليات التهيئة النشطة.
     */
    fun activeCount(): Int {
        return cancellationRegistry.activeCount()
    }

    /**
     * إعادة آلة الحالات إلى IDLE بأمان.
     *
     * الانتقال مسموح فقط من:
     * COMPLETED / FAILED / CANCELLED -> IDLE
     */
    private suspend fun transitionToIdleSafely(
        correlationId: String
    ) {
        try {
            val current = stateMachine.getCurrentState()

            if (
                current == StartupStateMachine.State.COMPLETED ||
                current == StartupStateMachine.State.FAILED ||
                current == StartupStateMachine.State.CANCELLED
            ) {
                val transitioned = transitionState(
                    StartupStateMachine.State.IDLE
                )

                if (!transitioned) {
                    Log.w(
                        TAG,
                        "⚠️ Failed to reset state to IDLE | " +
                            "current=${stateMachine.getCurrentState()} | " +
                            "$correlationId"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to reset StartupStateMachine to IDLE | " +
                    "$correlationId",
                e
            )
        }
    }

    /**
     * تسجيل metric مع حماية المنسق من فشل MetricsCollector.
     */
    private fun recordMetricSafely(
        key: String,
        value: Any,
        correlationId: String
    ) {
        if (!isMetricsEnabledSafely()) {
            return
        }

        try {
            metricsCollector.record(key, value)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to record metric '$key' | $correlationId",
                e
            )
        }
    }

    private fun isMetricsEnabledSafely(): Boolean {
        return try {
            config.getMetricsEnabled()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * حماية callback النهائي من التأثير على دورة التنظيف.
     */
    private fun safelyComplete(
        onComplete: () -> Unit,
        correlationId: String
    ) {
        try {
            onComplete()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "onComplete callback failed | $correlationId",
                e
            )
        }
    }

    /**
     * إنشاء معرف قصير وفريد نسبيًا لكل عملية Startup.
     */
    private fun generateCorrelationId(): String {
        return UUID.randomUUID()
            .toString()
            .substring(0, 8)
    }

    /**
     * إيقاف نطاق المنسق عند عدم الحاجة إليه.
     *
     * لا يتم استدعاؤها تلقائيًا حتى لا نكسر دورة حياة الكائن
     * الحالية أو التكامل القائم معه.
     */
    fun shutdown() {
        cancellationRegistry.cancelAll()
        coordinatorScope.cancel()
    }
}