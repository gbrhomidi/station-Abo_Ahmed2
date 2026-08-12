package com.aistudio.dieselstationsms.kxmpzq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.di.StartupCompositionRoot

/**
 * ═══════════════════════════════════════════════════════════════
 * BootReceiver – بوابة إقلاع الجهاز (الإصدار 5.0 النهائية)
 * ═══════════════════════════════════════════════════════════════
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val VALID_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.USER_UNLOCKED"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        if (action !in VALID_ACTIONS) {
            Log.d(TAG, "Ignored: $action")
            return
        }

        val pendingResult = goAsync()

        val coordinator = StartupCompositionRoot.createCoordinator(context.applicationContext)
        val reason = when (action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> StartupReason.BOOT
            "android.intent.action.MY_PACKAGE_REPLACED" -> StartupReason.APP_UPDATED
            "android.intent.action.USER_UNLOCKED" -> StartupReason.USER_UNLOCKED
            else -> StartupReason.BOOT
        }

        coordinator.execute(
            context = context.applicationContext,
            reason = reason,
            action = action,
            onComplete = {
                pendingResult.finish()
                Log.d(TAG, "Done: $action")
            }
        )
    }
}
