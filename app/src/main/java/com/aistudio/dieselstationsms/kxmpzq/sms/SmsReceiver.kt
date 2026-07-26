package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import com.aistudio.dieselstationsms.kxmpzq.utils.SystemEventLogger
import kotlinx.coroutines.*

/**
 * ═══════════════════════════════════════════════════════════════
 * مستقبل الرسائل - SmsReceiver (الإصدار المُعاد تنظيمه)
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * - استقبال الرسائل فقط
 * - تمريرها إلى SmsProcessor
 * - إدارة goAsync lifecycle
 *
 * التحسينات:
 * 1. ملف رفيع (Thin) - لا يحتوي على منطق أعمال
 * 2. منع معالجة التكرار عبر Hash
 * 3. Wake Lock للمعالجة الآمنة
 * 4. Coroutines + goAsync
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()
        var wakeLock: PowerManager.WakeLock? = null

        scope.launch {
            try {

                val powerManager =
                    context.getSystemService(Context.POWER_SERVICE) as PowerManager

                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DieselStationSMS::SmsReceiver"
                ).apply {
                    acquire(30000L)
                }

                val db = DatabaseHelper.getInstance(context)
                val processor = SmsProcessor(context, db)

                val processed = processor.process(intent)

                Log.d(TAG, "SMS processed: $processed")

            } catch (e: Exception) {

                Log.e(TAG, "Error in async processing", e)

            } finally {

                wakeLock?.let {
                    if (it.isHeld) it.release()
                }

                pendingResult.finish()
                
                }
            }
        }
    }