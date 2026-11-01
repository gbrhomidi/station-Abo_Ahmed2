package com.aistudio.dieselstationsms.kxmpzq.startup.recovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.startup.DatabaseInitializationCoordinator
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceLaunchResult
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceStatusRepository
import com.aistudio.dieselstationsms.kxmpzq.startup.SmsServiceLauncher
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.config.DatabaseConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.di.StartupCompositionRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class StartupRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "startup_recovery_work"
        const val INPUT_STARTUP_REASON = "startup_reason"
    }

    override suspend fun doWork(): Result {
        return try {
            val reason = inputData.getString(INPUT_STARTUP_REASON)
                ?.let { runCatching { StartupReason.valueOf(it) }.getOrNull() }
                ?: StartupReason.SCHEDULED
            val database = DatabaseInitializationCoordinator.awaitDatabase(applicationContext)
            val config = DatabaseConfigurationProvider(applicationContext)

            if (reason == StartupReason.BOOT) {
                val autoStart = database.getSetting("auto_start_enabled")
                    .ifBlank { database.getSetting("autoStartEnabled") }
                if (explicitlyDisabled(autoStart) || explicitlyDisabled(database.getSetting("sms_enabled"))) {
                    return Result.success()
                }
                if (config.getStartupMode() == "MANUAL") return Result.success()

                if (!config.isPipelineEnabled()) {
                    delay(config.getBootDelayMs())
                    return when (val result = SmsServiceLauncher(
                        applicationContext,
                        ServiceStatusRepository(applicationContext)
                    ).launch(StartupReason.BOOT)) {
                        is ServiceLaunchResult.Success,
                        is ServiceLaunchResult.AlreadyRunning -> Result.success()
                        is ServiceLaunchResult.Failure -> Result.retry()
                    }
                }
            }

            val completed = CompletableDeferred<Unit>()
            StartupCompositionRoot.createCoordinator(applicationContext).execute(
                context = applicationContext,
                reason = reason,
                onComplete = { completed.complete(Unit) }
            )
            if (withTimeoutOrNull(60_000L) { completed.await(); true } == true) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (error: Exception) {
            Result.retry()
        }
    }

    private fun explicitlyDisabled(raw: String): Boolean =
        raw.trim().equals("0", true) || raw.trim().equals("false", true) ||
            raw.trim().equals("off", true) || raw.trim().equals("no", true)
}
