package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.startup.*
import com.aistudio.dieselstationsms.kxmpzq.startup.event.EventBus
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy
import kotlinx.coroutines.*

class InitializationPipeline(
    private val phases: List<InitializationPhase>,
    private val eventBus: EventBus,
    private val stateMachine: StartupStateMachine,
    private val retryPolicy: RetryPolicy
) {
    companion object { private const val TAG = "InitPipeline" }

    data class PipelineResult(
        val success: Boolean,
        val completedPhases: List<String>,
        val skippedPhases: List<String>,
        val failedPhase: String? = null,
        val error: String? = null
    )

    suspend fun execute(ctx: InitializationContext, policy: StartupPolicy): PipelineResult {
        val dag = DagEngine(phases)
        val dagResult = dag.validateAndSort()

        if (!dagResult.isValid) {
            return PipelineResult(false, emptyList(), emptyList(), "DAG", dagResult.error)
        }

        val completed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val executed = mutableSetOf<String>()

        eventBus.emit(StartupEvent.PipelineStarted(ctx.startupReason, ctx.correlationId))

        while (executed.size < phases.size) {
            ctx.cancellationToken.throwIfCancelled()
            val ready = dag.getReadyPhases(executed)

            if (ready.isEmpty() && executed.size < phases.size) {
                return PipelineResult(false, completed, skipped, "DAG", "Stuck: ${phases.map { it.name } - executed}")
            }

            val results = if (policy.allowParallelExecution) {
                ready.map { async { executePhase(ctx, it) } }.awaitAll()
            } else {
                ready.map { executePhase(ctx, it) }
            }

            for ((phase, result) in ready.zip(results)) {
                executed.add(phase.name)
                when (result) {
                    is PhaseResult.Success -> {
                        completed.add(phase.name)
                        eventBus.emit(StartupEvent.PhaseCompleted(phase.name, result.message ?: "OK", ctx.correlationId))
                    }
                    is PhaseResult.Skipped -> {
                        skipped.add(phase.name)
                        eventBus.emit(StartupEvent.PhaseSkipped(phase.name, result.reason, ctx.correlationId))
                    }
                    is PhaseResult.Failure -> {
                        eventBus.emit(StartupEvent.PhaseFailed(phase.name, result.error, ctx.correlationId))
                        if (phase.isCritical) {
                            return PipelineResult(false, completed, skipped, phase.name, result.error)
                        } else skipped.add(phase.name)
                    }
                }
            }
        }
        return PipelineResult(true, completed, skipped)
    }

    private suspend fun executePhase(ctx: InitializationContext, phase: InitializationPhase): PhaseResult {
        ctx.logger.logPhaseStarted(phase.name, ctx.correlationId)
        eventBus.emit(StartupEvent.PhaseStarted(phase.name, ctx.correlationId))
        return try {
            withTimeout(phase.timeoutMs) { executeWithRetry(ctx, phase) }
        } catch (e: TimeoutCancellationException) {
            PhaseResult.Failure("Timeout after ${phase.timeoutMs}ms", retryable = true)
        }
    }

    private suspend fun executeWithRetry(ctx: InitializationContext, phase: InitializationPhase): PhaseResult {
        for (attempt in 0 until retryPolicy.maxAttempts) {
            try {
                val result = phase.execute(ctx)
                if (result is PhaseResult.Failure && result.retryable && attempt < retryPolicy.maxAttempts - 1) {
                    Log.w(TAG, "${phase.name} retry ${attempt + 1}/${retryPolicy.maxAttempts}")
                    delay(retryPolicy.getDelayMs(attempt))
                    continue
                }
                return result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt < retryPolicy.maxAttempts - 1 && retryPolicy.shouldRetry(attempt, e)) {
                    delay(retryPolicy.getDelayMs(attempt))
                } else return PhaseResult.Failure(e.message ?: "Unknown", retryable = false)
            }
        }
        return PhaseResult.Failure("All retries exhausted", retryable = false)
    }
}
