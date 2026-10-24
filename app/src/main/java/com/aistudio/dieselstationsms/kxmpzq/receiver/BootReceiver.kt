package com.aistudio.dieselstationsms.kxmpzq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.notifications.TaskNotificationManager
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.config.DatabaseConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.di.StartupCompositionRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Restores scheduled work and applies persisted startup policy after device boot. */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in SUPPORTED_ACTIONS) return
        val appContext = context.applicationContext
        TaskNotificationManager.reschedulePendingTasksAsync(appContext)

        val database = DatabaseHelper.getInstance(appContext)
        val config = DatabaseConfigurationProvider(appContext)
        val autoStart = database.getSetting("auto_start_enabled").ifBlank { database.getSetting("autoStartEnabled") }
        if (explicitlyDisabled(autoStart)) {
            Log.i(TAG, "Automatic startup disabled in SQLite settings")
            return
        }
        if (explicitlyDisabled(database.getSetting("sms_enabled"))) {
            Log.i(TAG, "SMS service disabled in SQLite settings")
            return
        }
        if (config.getStartupMode() == "MANUAL") {
            Log.i(TAG, "Startup mode is MANUAL; skipping boot launch")
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (config.isPipelineEnabled()) {
                    val completed = CompletableDeferred<Unit>()
                    StartupCompositionRoot.createCoordinator(appContext).execute(
                        context = appContext,
                        reason = StartupReason.BOOT,
                        action = intent?.action,
                        onComplete = { completed.complete(Unit) }
                    )
                    if (withTimeoutOrNull(110_000L) { completed.await(); true } != true) {
                        Log.e(TAG, "Startup pipeline did not finish before receiver timeout")
                    }
                } else {
                    delay(config.getBootDelayMs())
                    val serviceIntent = Intent(appContext, SMSService::class.java).apply {
                        putExtra("startup_reason", "BOOT")
                        putExtra("launch_time", System.currentTimeMillis())
                        putExtra("boot_action", intent?.action)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(appContext, serviceIntent)
                    } else {
                        appContext.startService(serviceIntent)
                    }
                    Log.i(TAG, "SMSService launch requested after configured boot delay")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to apply startup settings", error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun explicitlyDisabled(raw: String): Boolean =
        raw.trim().equals("0", true) || raw.trim().equals("false", true) ||
            raw.trim().equals("off", true) || raw.trim().equals("no", true)
}
