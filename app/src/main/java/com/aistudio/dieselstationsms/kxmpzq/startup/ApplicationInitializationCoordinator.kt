package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.startup.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.event.EventBus
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.metrics.MetricsCollector
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.*
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy
import kotlinx.coroutines.*
import java.util.UUID

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
    private val coordinatorScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("InitCoordinator")
    )

    companion object { private const val TAG = "InitCoordinator" }

    fun execute(context: Context, reason: StartupReason, action: String? = null, onComplete: () -> Unit) {
        val startTime = SystemClock.elapsedRealtime()
        val correlationId = generateCorrelationId()

        val cancellationToken = CancellationToken()
        cancellationRegistry.register(correlationId, cancellationToken)

        val logger = loggerFactory(context)
        val serviceLauncher = launcherFactory(context)
        val healthMonitor = healthMonitorFactory()
        val retryPolicy = retryPolicyFactory()

        val initContext = InitializationContext(
            appContext = context, startupReason = reason, logger = logger,
            serviceLauncher = serviceLauncher, config = config, healthMonitor = healthMonitor,
            retryPolicy = retryPolicy, correlationId = correlationId, cancellationToken = cancellationToken
        )

        logger.logBootReceived(reason, action, correlationId)

        coordinatorScope.launch {
            val transitioned = stateMachine.transition(StartupStateMachine.State.PREPARING)
            if (!transitioned) {
                logger.logCancelled(correlationId, "Invalid state: ${stateMachine.getCurrentState()}")
                cancellationRegistry.remove(correlationId)
                onComplete()
                return@launch
            }

            try {
                val policy = StartupPolicyFactory.create(reason, phaseRegistry)
                val pipeline = InitializationPipeline(policy.phases, eventBus, stateMachine, retryPolicy)
                stateMachine.transition(StartupStateMachine.State.RUNNING)

                val result = executionGuard.execute(policy) {
                    withTimeout(config.getPipelineTimeoutMs()) { pipeline.execute(initContext, policy) }
                }

                val duration = SystemClock.elapsedRealtime() - startTime
                if (config.getMetricsEnabled()) {
                    metricsCollector.record("startup_duration_ms", duration)
                    metricsCollector.record("startup_reason", reason.name)
                    metricsCollector.record("completed_phases", result.completedPhases.size)
                    metricsCollector.record("correlation_id", correlationId)
                }

                if (result.success) {
                    stateMachine.transition(StartupStateMachine.State.COMPLETED)
                    logger.logPipelineSuccess(duration, result.completedPhases, correlationId)
                    eventBus.emit(StartupEvent.PipelineCompleted(duration, result.completedPhases, correlationId))
                    Log.i(TAG, "✅ Pipeline completed in ${duration}ms | $correlationId")
                } else {
                    stateMachine.transition(StartupStateMachine.State.FAILED)
                    metricsCollector.record("failed_phase", result.failedPhase ?: "Unknown")
                    logger.logPipelineFailed(result.failedPhase ?: "Unknown", result.error ?: "Unknown", correlationId)
                    eventBus.emit(StartupEvent.PipelineFailed(result.failedPhase ?: "Unknown", result.error ?: "Unknown", correlationId))
                    Log.e(TAG, "❌ Pipeline failed at ${result.failedPhase}: ${result.error} | $correlationId")
                }
            } catch (e: TimeoutCancellationException) {
                stateMachine.transition(StartupStateMachine.State.FAILED)
                Log.e(TAG, "⏱️ Pipeline timeout | $correlationId")
                logger.logPipelineFailed("Coordinator", "Pipeline timeout", correlationId)
                eventBus.emit(StartupEvent.PipelineFailed("Coordinator", "Pipeline timeout", correlationId))
            } catch (e: CancellationException) {
                stateMachine.transition(StartupStateMachine.State.CANCELLED)
                Log.w(TAG, "🚫 Cancelled: $reason | $correlationId")
                logger.logCancelled(correlationId, e.message ?: "Unknown")
                eventBus.emit(StartupEvent.Cancelled(correlationId, e.message ?: "Unknown"))
            } catch (e: Exception) {
                stateMachine.transition(StartupStateMachine.State.FAILED)
                Log.e(TAG, "💥 Unexpected: ${e.message} | $correlationId", e)
                logger.logPipelineFailed("Coordinator", e.message ?: "Unknown", correlationId)
                eventBus.emit(StartupEvent.PipelineFailed("Coordinator", e.message ?: "Unknown", correlationId))
            } finally {
                cancellationRegistry.remove(correlationId)
                onComplete()
            }
        }
    }

    fun cancel(correlationId: String): Boolean = cancellationRegistry.cancel(correlationId)
    fun cancelAll() = cancellationRegistry.cancelAll()
    fun activeCount(): Int = cancellationRegistry.activeCount()

    private fun generateCorrelationId(): String = UUID.randomUUID().toString().substring(0, 8)
}
