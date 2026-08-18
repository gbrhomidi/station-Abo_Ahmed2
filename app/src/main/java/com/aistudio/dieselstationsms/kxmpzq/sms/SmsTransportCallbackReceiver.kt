package com.aistudio.dieselstationsms.kxmpzq.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

/** يستقبل نتائج modem لكل جزء؛ لا ينفذ منطقاً تجارياً. */
class SmsTransportCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val messageId = intent.getStringExtra(SmsTransport.EXTRA_MESSAGE_ID) ?: return
        val partIndex = intent.getIntExtra(SmsTransport.EXTRA_PART_INDEX, -1)
        if (partIndex < 0) return

        val delivered = intent.action == SmsTransport.ACTION_DELIVERED
        val success = resultCode == Activity.RESULT_OK
        val code = if (success) "OK" else resultCode.toString()
        val reason = if (success) "" else intent.getStringExtra("error") ?: "SMS transport callback failed"

        runCatching {
            val db = DatabaseHelper.getInstance(context.applicationContext)
            SmsOutboxRepository.recordPartResult(
                db = db,
                messageId = messageId,
                partIndex = partIndex,
                delivered = delivered,
                success = success,
                resultCode = code,
                reason = reason
            )
        }.onFailure {
            Log.e("SmsTransportCallback", "Failed to persist SMS callback", it)
        }
    }
}
