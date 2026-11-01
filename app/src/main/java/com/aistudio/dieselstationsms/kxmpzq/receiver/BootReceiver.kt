package com.aistudio.dieselstationsms.kxmpzq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aistudio.dieselstationsms.kxmpzq.startup.DatabaseInitializationCoordinator
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.config.DatabaseConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.di.StartupCompositionRoot
import com.aistudio.dieselstationsms.kxmpzq.startup.recovery.StartupRecoveryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val DATABASE_READY_TIMEOUT_MS = 8_000L
        private val bootWorkInFlight = AtomicBoolean(false)
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        if (!bootWorkInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "Ignoring duplicate boot action while startup is already being coordinated")
            return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        var releaseBootClaimOnExit = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = withTimeoutOrNull(DATABASE_READY_TIMEOUT_MS) {
                    DatabaseInitializationCoordinator.awaitDatabase(appContext)
                }
                if (database == null) {
                    enqueueBootRecovery(appContext)
                    Log.w(TAG, "SQLite did not become ready within the receiver budget; deferred startup to WorkManager")
                    return@launch
                }

                val config = DatabaseConfigurationProvider(appContext)
                val autoStart = database.getSetting("auto_start_enabled")
                    .ifBlank { database.getSetting("autoStartEnabled") }
                if (explicitlyDisabled(autoStart) || explicitlyDisabled(database.getSetting("sms_enabled"))) {
                    Log.i(TAG, "Automatic SMS startup disabled in SQLite settings")
                    return@launch
                }
                if (config.getStartupMode() == "MANUAL") {
                    Log.i(TAG, "Startup mode is MANUAL; skipping boot launch")
                    return@launch
                }

                if (config.isPipelineEnabled()) {
                    releaseBootClaimOnExit = false
                    StartupCompositionRoot.createCoordinator(appContext).execute(
                        context = appContext,
                        reason = StartupReason.BOOT,
                        action = action,
                        onComplete = { bootWorkInFlight.set(false) }
                    )
                } else {
                    enqueueBootRecovery(appContext)
                    Log.i(TAG, "Pipeline disabled; deferred configured boot launch to WorkManager")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to apply startup settings", error)
            } finally {
                pending.finish()
                if (releaseBootClaimOnExit) bootWorkInFlight.set(false)
            }
        }
    }

    private fun enqueueBootRecovery(context: Context) {
        val request = OneTimeWorkRequestBuilder<StartupRecoveryWorker>()
            .setInputData(workDataOf(StartupRecoveryWorker.INPUT_STARTUP_REASON to StartupReason.BOOT.name))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            StartupRecoveryWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun explicitlyDisabled(raw: String): Boolean =
        raw.trim().equals("0", true) || raw.trim().equals("false", true) ||
            raw.trim().equals("off", true) || raw.trim().equals("no", true)
}
