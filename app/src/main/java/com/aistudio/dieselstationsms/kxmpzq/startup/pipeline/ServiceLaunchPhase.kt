package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceLaunchResult

class ServiceLaunchPhase : InitializationPhase {
    override val name = "ServiceLaunch"
    override val isCritical = true

    override suspend fun execute(ctx: InitializationContext): PhaseResult {
        ctx.cancellationToken.throwIfCancelled()
        return when (val result = ctx.serviceLauncher.launch(ctx.startupReason)) {
            is ServiceLaunchResult.Success -> PhaseResult.Success(result.message)
            is ServiceLaunchResult.AlreadyRunning -> PhaseResult.Skipped(result.message ?: "Already running")
            is ServiceLaunchResult.Failure -> PhaseResult.Failure(result.error, result.retryable)
        }
    }
}
