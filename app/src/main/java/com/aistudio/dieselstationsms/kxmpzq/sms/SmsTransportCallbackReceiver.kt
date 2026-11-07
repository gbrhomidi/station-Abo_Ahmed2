package com.aistudio.dieselstationsms.kxmpzq.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.startup.DatabaseInitializationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** يستقبل نتائج modem لكل جزء؛ لا ينفذ منطقاً تجارياً. */
class SmsTransportCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val appContext = context.applicationContext
        val callbackResultCode = resultCode
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DatabaseInitializationCoordinator.awaitDatabase(appContext)
                handleCallback(appContext, intent, callbackResultCode)
            } catch (error: Exception) {
                Log.e("SmsTransportCallback", "SQLite callback processing failed (${error.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Shared callback path. An explicit result code allows tests to exercise modem outcomes
     * without pretending a direct onReceive call is an ordered broadcast.
     */
    internal fun handleCallback(context: Context, intent: Intent?, callbackResultCode: Int) {
        if (intent == null) return
        val messageId = intent.getStringExtra(SmsTransport.EXTRA_MESSAGE_ID) ?: return
        val attemptToken = intent.getStringExtra(SmsTransport.EXTRA_ATTEMPT_TOKEN)
            ?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: return
        val partIndex = intent.getIntExtra(SmsTransport.EXTRA_PART_INDEX, -1)
        if (partIndex < 0) return

        val action = intent.action
        if (action != SmsTransport.ACTION_SENT && action != SmsTransport.ACTION_DELIVERED) return
        val delivered = action == SmsTransport.ACTION_DELIVERED
        val success = callbackResultCode == Activity.RESULT_OK
        val code = if (success) "OK" else when (callbackResultCode) {
            android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
            android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
            android.telephony.SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
            android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
            else -> "ANDROID_RESULT_${callbackResultCode}"
        }

        runCatching {
            val db = DatabaseHelper.getInstance(context.applicationContext)
            SmsOutboxRepository.recordPartResult(
                db = db, messageId = messageId, partIndex = partIndex,
                delivered = delivered, success = success, resultCode = code,
                attemptToken = attemptToken
            )
            SmsFailureNotificationPublisher.publishForMessage(context.applicationContext, db, messageId)
        }.onFailure { error ->
            Log.e("SmsTransportCallback", "Callback persistence failed (${error.javaClass.simpleName})")
        }
    }
}