package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import kotlinx.coroutines.delay

class DelayPhase : InitializationPhase {
    override val name = "BootDelay"
    override val isCritical = false

    override suspend fun execute(ctx: InitializationContext): PhaseResult {
        if (ctx.startupReason != StartupReason.BOOT) {
            return PhaseResult.Skipped("Delay only for BOOT")
        }
        val delayMs = ctx.config.getBootDelayMs()
        val steps = 10
        val stepDelay = delayMs / steps
        for (i in 0 until steps) {
            ctx.cancellationToken.throwIfCancelled()
            delay(stepDelay)
        }
        return PhaseResult.Success("Delay completed: ${delayMs}ms")
    }
}
