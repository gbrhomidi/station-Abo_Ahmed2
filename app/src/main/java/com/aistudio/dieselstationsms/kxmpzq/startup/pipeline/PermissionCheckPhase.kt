package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext

class PermissionCheckPhase : InitializationPhase {
    override val name = "PermissionCheck"
    override val isCritical = true

    private val criticalPermissions = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS
    )

    override suspend fun execute(ctx: InitializationContext): PhaseResult {
        ctx.cancellationToken.throwIfCancelled()
        val missing = criticalPermissions.filter {
            ContextCompat.checkSelfPermission(ctx.appContext, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) {
            PhaseResult.Success("All critical permissions granted")
        } else {
            PhaseResult.Failure("Missing permissions: $missing", retryable = false)
        }
    }
}
