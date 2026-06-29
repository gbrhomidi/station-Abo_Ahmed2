package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val appContext = context.applicationContext
            val db = DatabaseHelper(appContext)

            for (sms in messages) {
                val sender = sms.displayOriginatingAddress ?: continue
                val msgBody = sms.displayMessageBody?.lowercase() ?: continue

                db.logSms(sender, msgBody, "received", "success")
                Log.d("SmsReceiver", "SMS from $sender: $msgBody")

                try {
                    when {
                        msgBody.contains("رصيد") || msgBody.contains("حساب") || msgBody.contains("balance") -> {
                            handleBalanceQuery(appContext, db, sender)
                        }
                        msgBody.contains("دفع") || msgBody.contains("تسديد") -> {
                            sendReply(appContext, db, sender, "شكراً لتواصلك. يرجى زيارة المحطة لإتمام عملية الدفع.")
                        }
                        msgBody.contains("استعلام") || msgBody.contains("help") -> {
                            sendReply(
                                appContext, db, sender,
                                "مرحباً بك في محطة أبو أحمد.\nالخدمات:\n1. الرصيد (رصيد)\n2. العروض (عروض)\n3. الموقع (موقع)"
                            )
                        }
                        msgBody.contains("عروض") || msgBody.contains("offer") -> {
                            sendReply(
                                appContext, db, sender,
                                "عروض اليوم:\n- سعر اللتر: 500 ريال\n- خصم الولاء: 5% للعملاء الذهبيين"
                            )
                        }
                        msgBody.contains("موقع") || msgBody.contains("location") -> {
                            sendReply(
                                appContext, db, sender,
                                "الموقع: بجانب مدرسة الاتحاد برأس وادي ثاة - الحميدة - العرش"
                            )
                        }
                        else -> {
                            sendReply(
                                appContext, db, sender,
                                "شكراً لتواصلك. أرسل 'استعلام' للمساعدة."
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Error processing message", e)
                    try {
                        sendReply(appContext, db, sender, "حدث خطأ، يرجى المحاولة مرة أخرى.")
                    } catch (ex: Exception) {
                        Log.e("SmsReceiver", "Failed to send error reply", ex)
                    }
                }
            }
        }
    }

    private fun handleBalanceQuery(context: Context, db: DatabaseHelper, sender: String) {
        try {
            val customers = db.getCustomers()
            var found = false
            val cleanSender = sender.replace("[^0-9+]".toRegex(), "")

            for (i in 0 until customers.length()) {
                val c = customers.getJSONObject(i)
                val cPhone = c.optString("phone", "").replace("[^0-9+]".toRegex(), "")
                if (cPhone.isNotEmpty() && (cleanSender.contains(cPhone) || cPhone.contains(cleanSender)
                            || cleanSender.endsWith(cPhone.takeLast(7)) || cPhone.endsWith(cleanSender.takeLast(7)))) {
                    val bal = c.optDouble("current_balance", 0.0)
                    val points = c.optInt("loyalty_points", 0)
                    val vip = c.optInt("vip_level", 0)
                    val vipText = when (vip) {
                        3 -> "ذهبي 🥇"
                        2 -> "فضي 🥈"
                        else -> "عادي"
                    }
                    val name = c.optString("full_name", "عميلنا العزيز")
                    val reply = "مرحباً $name,\nالرصيد الحالي: $bal ريال\nنقاط الولاء: $points\nالعضوية: $vipText"
                    sendReply(context, db, sender, reply)
                    found = true
                    break
                }
            }
            if (!found) {
                sendReply(context, db, sender, "لم يتم العثور على حساب مرتبط بهذا الرقم.")
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error in balance query", e)
            sendReply(context, db, sender, "حدث خطأ في الاستعلام، حاول مرة أخرى.")
        }
    }

    private fun sendReply(context: Context, db: DatabaseHelper, phone: String, message: String) {
        try {
            // ✅ استخدام ContextCompat للتحقق الآمن
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                val smsManager = SmsManager.getDefault()
                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(phone, null, message, null, null)
                }
                db.logSms(phone, message, "auto_reply", "sent")
                Log.d("SmsReceiver", "Reply sent to $phone")
            } else {
                Log.e("SmsReceiver", "SEND_SMS permission denied")
                db.logSms(phone, message, "auto_reply", "failed: permission denied")
            }
        } catch (e: SecurityException) {
            Log.e("SmsReceiver", "SecurityException", e)
            db.logSms(phone, message, "auto_reply", "failed: ${e.message}")
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Failed to send SMS", e)
            db.logSms(phone, message, "auto_reply", "failed: ${e.message}")
        }
    }
}
