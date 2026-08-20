package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import android.os.Build
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext

class EnvironmentCheckPhase : InitializationPhase {
    override val name = "EnvironmentCheck"
    override val isCritical = true

    override suspend fun execute(ctx: InitializationContext): PhaseResult {
        ctx.cancellationToken.throwIfCancelled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = ctx.appContext.getSystemService(android.content.Context.USER_SERVICE) as? android.os.UserManager
            if (um?.isUserUnlocked == false) {
                return PhaseResult.Skipped("Direct Boot: waiting for unlock")
            }
        }
        Log.d(name, "Environment check passed")
        return PhaseResult.Success()
    }
}
