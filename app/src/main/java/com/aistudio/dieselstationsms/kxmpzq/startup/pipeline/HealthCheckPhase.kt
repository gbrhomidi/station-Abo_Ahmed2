package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthStatus

class HealthCheckPhase : InitializationPhase {
    override val name = "HealthCheck"
    override val isCritical = false
    override val dependencies = listOf("ServiceLaunch")

    override suspend fun execute(ctx: InitializationContext): PhaseResult {
        ctx.cancellationToken.throwIfCancelled()
        val status = ctx.healthMonitor.check()
        return when (status) {
            is HealthStatus.Healthy -> {
                ctx.logger.logHealthCheck("HEALTHY", null, ctx.correlationId)
                PhaseResult.Success("Service healthy")
            }
            is HealthStatus.Unhealthy -> {
                ctx.logger.logHealthCheck("UNHEALTHY", status.reason, ctx.correlationId)
                PhaseResult.Failure("Health check failed: ${status.reason}", retryable = true)
            }
            is HealthStatus.Unknown -> {
                ctx.logger.logHealthCheck("UNKNOWN", status.reason, ctx.correlationId)
                PhaseResult.Skipped(status.reason)
            }
        }
    }
}
