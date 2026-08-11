package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════
 * مستقبل الرسائل النصية - SmsReceiver
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 * 1. استقبال Broadcast الخاص بالرسائل النصية.
 * 2. قبول SMS_RECEIVED و SMS_DELIVER عند الحاجة.
 * 3. إبقاء عملية Broadcast حية باستخدام goAsync().
 * 4. تمرير Intent إلى SmsProcessor فقط.
 * 5. توفير WakeLock قصير المدة أثناء المعالجة.
 * 6. عدم تنفيذ منطق الأعمال داخل Receiver.
 * 7. عدم تسجيل محتوى الرسالة أو رقم المرسل في Log.
 *
 * ملاحظات:
 * - SmsProcessor هو المسؤول عن تحليل الرسالة وتنفيذ منطق الأعمال.
 * - SmsSecurity هو المسؤول عن طبقة الحماية ومنع التكرار وRate Limiting.
 * - Receiver يجب أن يبقى Thin قدر الإمكان.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"

        private const val WAKE_LOCK_TAG =
            "DieselStationSMS::SmsReceiver"

        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L

        /**
         * الإجراءات التي يمكن أن تصل إلى هذا المستقبل.
         *
         * SMS_RECEIVED:
         * الإجراء القياسي لاستقبال الرسائل الواردة للتطبيقات
         * التي تملك RECEIVE_SMS.
         *
         * SMS_DELIVER:
         * يستخدم في سياق تطبيق الرسائل الافتراضي Default SMS App.
         *
         * لا نعتمد على أحدهما فقط حتى لا يتم تجاهل الرسائل
         * عند اختلاف وضع التطبيق.
         */
        private val SUPPORTED_ACTIONS = setOf(
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            Telephony.Sms.Intents.SMS_DELIVER_ACTION
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {

        /*
         * لا يوجد Intent صالح => لا يوجد شيء يمكن معالجته.
         */
        if (intent == null) {
            Log.w(TAG, "Received null intent")
            return
        }

        /*
         * لا نعالج أي Broadcast غير متعلق بالرسائل
         * التي يدعمها هذا المستقبل.
         */
        val action = intent.action

        if (action !in SUPPORTED_ACTIONS) {
            Log.d(TAG, "Ignoring unsupported action")
            return
        }

        /*
         * نستخدم goAsync() لأن معالجة الرسالة قد تتجاوز
         * المدة القصيرة المسموح بها لـ onReceive().
         */
        val pendingResult = goAsync()

        /*
         * نستخدم Application Context لتجنب الاحتفاظ بمراجع
         * غير ضرورية إلى Context قصير العمر.
         */
        val appContext = context.applicationContext

        /*
         * إنشاء Scope خاص بهذه العملية.
         *
         * لا نستخدم CoroutineScope كخاصية دائمة داخل BroadcastReceiver،
         * لأن BroadcastReceiver قد يُنشأ ويُتخلص منه عدة مرات.
         */
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

        scope.launch {

            var wakeLock: PowerManager.WakeLock? = null

            try {

                /*
                 * محاولة الحصول على WakeLock.
                 *
                 * الفشل في الحصول عليه لا يعني إلغاء معالجة الرسالة؛
                 * يمكن للمعالجة أن تستمر بدون WakeLock.
                 */
                wakeLock = acquireWakeLock(appContext)

                /*
                 * الوصول إلى قاعدة البيانات.
                 *
                 * DatabaseHelper هو مصدر قاعدة البيانات المركزي
                 * في المشروع.
                 */
                val database = DatabaseHelper.getInstance(appContext)

                /*
                 * إنشاء Processor وتمرير Intent كما هو.
                 *
                 * لا نقوم باستخراج الرسالة هنا.
                 * لا نقوم بتحليلها هنا.
                 * لا نقوم بتنفيذ الأوامر هنا.
                 */
                val processor = SmsProcessor(
                    context = appContext,
                    db = database
                )

                /*
                 * تسليم الرسالة إلى طبقة المعالجة.
                 */
                val processed = processor.process(intent)

                Log.d(
                    TAG,
                    "SMS processing completed: result=$processed"
                )

            } catch (securityException: SecurityException) {

                /*
                 * أخطاء الصلاحيات أو الوصول إلى خدمات النظام.
                 */
                val errorId = generateErrorId()

                Log.e(
                    TAG,
                    "SMS processing failed [ErrorID=$errorId] " +
                        "type=SecurityException"
                )

            } catch (illegalArgumentException: IllegalArgumentException) {

                /*
                 * حماية إضافية من Intent غير متوقع أو بيانات
                 * غير صالحة يتم تمريرها إلى طبقات المعالجة.
                 */
                val errorId = generateErrorId()

                Log.e(
                    TAG,
                    "SMS processing failed [ErrorID=$errorId] " +
                        "type=IllegalArgumentException"
                )

            } catch (exception: Exception) {

                /*
                 * Catch-all لمنع خروج استثناء من Coroutine
                 * والتسبب في فقدان pendingResult.finish().
                 *
                 * لا نسجل:
                 * - رقم الهاتف
                 * - نص الرسالة
                 * - محتوى Intent
                 * - البيانات الحساسة
                 */
                val errorId = generateErrorId()

                Log.e(
                    TAG,
                    "SMS processing failed [ErrorID=$errorId] " +
                        "type=${exception.javaClass.simpleName}"
                )

            } finally {

                /*
                 * تحرير WakeLock دائماً.
                 */
                releaseWakeLock(wakeLock)

                /*
                 * إعلام Android بأن Broadcast تمت معالجته.
                 */
                try {
                    pendingResult.finish()
                } catch (exception: Exception) {
                    Log.e(
                        TAG,
                        "Failed to finish pending broadcast"
                    )
                }

                /*
                 * إلغاء الـ Scope الخاص بهذه العملية.
                 */
                scope.cancel()
            }
        }
    }

    /**
     * الحصول على WakeLock قصير المدة.
     *
     * لا نستخدم WakeLock دائم حتى لا يؤدي ذلك إلى استنزاف
     * البطارية في حال حدوث مشكلة في المعالجة.
     */
    private fun acquireWakeLock(
        context: Context
    ): PowerManager.WakeLock? {

        return try {

            val powerManager =
                context.getSystemService(Context.POWER_SERVICE)
                    as? PowerManager

            if (powerManager == null) {
                Log.w(
                    TAG,
                    "PowerManager is unavailable"
                )
                return null
            }

            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {

                /*
                 * timeout إلزامي كطبقة أمان إضافية.
                 */
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }

        } catch (exception: SecurityException) {

            Log.e(
                TAG,
                "Unable to acquire WakeLock: SecurityException"
            )

            null

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Unable to acquire WakeLock"
            )

            null
        }
    }

    /**
     * تحرير WakeLock بأمان.
     */
    private fun releaseWakeLock(
        wakeLock: PowerManager.WakeLock?
    ) {

        if (wakeLock == null) {
            return
        }

        try {

            if (wakeLock.isHeld) {
                wakeLock.release()
            }

        } catch (exception: RuntimeException) {

            /*
             * حماية من حالات release غير المتوقعة.
             */
            Log.w(
                TAG,
                "WakeLock release failed"
            )
        }
    }

    /**
     * إنشاء معرف خطأ قصير للاستخدام في Diagnostics.
     *
     * لا يحتوي على أي بيانات من الرسالة أو المرسل.
     */
    private fun generateErrorId(): String {
        return UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
            .uppercase()
    }
}