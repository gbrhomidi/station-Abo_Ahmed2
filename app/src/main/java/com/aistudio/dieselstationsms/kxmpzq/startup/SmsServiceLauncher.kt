package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService

/**
 * ═══════════════════════════════════════════════════════════════
 * مُشغّل خدمة SMS - SmsServiceLauncher
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 * 1. التحقق من حالة الخدمة قبل محاولة تشغيلها.
 * 2. تشغيل SMSService بالطريقة المناسبة لإصدار Android.
 * 3. تمرير سبب بدء التشغيل إلى SMSService.
 * 4. تسجيل وقت محاولة التشغيل.
 * 5. تحديث مستودع حالة الخدمة بعد قبول طلب التشغيل.
 * 6. تحويل أخطاء الأمان إلى نتيجة غير قابلة لإعادة المحاولة.
 * 7. تحويل بقية أخطاء التشغيل إلى نتيجة قابلة لإعادة المحاولة.
 *
 * ملاحظة معمارية:
 * نجاح startForegroundService()/startService() يعني أن طلب
 * تشغيل الخدمة قُبل من Android، وليس بالضرورة أن تهيئة
 * SMSService الداخلية اكتملت بالفعل. لذلك لا ينبغي تفسير
 * ServiceLaunchResult.Success على أنه تقرير صحة داخلي كامل
 * للخدمة؛ فهو يمثل نجاح إرسال طلب التشغيل إلى نظام Android.
 */
class SmsServiceLauncher(
    private val context: Context,
    private val statusRepository: ServiceStatusRepository
) : ServiceLauncher {

    companion object {
        private const val TAG = "SmsServiceLauncher"

        private const val EXTRA_STARTUP_REASON = "startup_reason"
        private const val EXTRA_LAUNCH_TIME = "launch_time"
    }

    override fun launch(reason: StartupReason): ServiceLaunchResult {
        /*
         * إذا كانت الحالة المسجلة ما زالت صالحة، فلا نقوم بإرسال
         * طلب تشغيل إضافي للخدمة.
         *
         * ServiceStatusRepository نفسه يتولى التحقق من انتهاء
         * صلاحية الحالة القديمة وفق STALE_TIMEOUT_MS.
         */
        if (statusRepository.isRunning()) {
            Log.d(
                TAG,
                "SMSService launch skipped: service is already marked as running"
            )

            return ServiceLaunchResult.AlreadyRunning(
                "Service already running (persistent)"
            )
        }

        val launchTime = System.currentTimeMillis()

        return try {
            val serviceIntent = Intent(context, SMSService::class.java).apply {
                putExtra(EXTRA_STARTUP_REASON, reason.name)
                putExtra(EXTRA_LAUNCH_TIME, launchTime)
            }

            /*
             * Android 8.0+ يفرض تشغيل الخدمة الخلفية كـ
             * Foreground Service عند بدءها من خارج الخدمة نفسها.
             *
             * SMSService ستقوم لاحقًا باستدعاء startForeground()
             * ضمن دورة حياتها الداخلية.
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            /*
             * لا نعتبر هذه النقطة "جاهزية داخلية كاملة" للخدمة.
             * إنما نسجل أن طلب التشغيل قُبل وأُرسل بنجاح.
             *
             * لا يجوز حذف هذا التحديث في البنية الحالية دون تعديل
             * ServiceStatusRepository / SMSService أيضًا، لأن
             * SmsServiceLauncher هو نقطة التسجيل الحالية لحالة
             * التشغيل في منظومة startup.
             */
            statusRepository.setRunning(true)

            Log.i(
                TAG,
                "SMSService launch request accepted. " +
                    "reason=${reason.name}, launchTime=$launchTime"
            )

            ServiceLaunchResult.Success(
                "SMSService launch request accepted"
            )

        } catch (e: SecurityException) {
            /*
             * أخطاء الأمان/الأذونات لا ينبغي أن تدخل في حلقة Retry
             * تلقائية؛ يجب معالجة سبب الصلاحية أولًا.
             */
            Log.e(
                TAG,
                "SMSService launch rejected by security policy: ${e.message}",
                e
            )

            ServiceLaunchResult.Failure(
                error = "Security: ${e.message ?: "Permission denied"}",
                retryable = false
            )

        } catch (e: Exception) {
            /*
             * بقية أخطاء الإطلاق قد تكون مؤقتة، ولذلك تبقى قابلة
             * لإعادة المحاولة من منظومة Startup/Recovery.
             */
            Log.e(
                TAG,
                "SMSService launch failed: ${e.message}",
                e
            )

            ServiceLaunchResult.Failure(
                error = "Launch failed: ${e.message ?: e.javaClass.simpleName}",
                retryable = true
            )
        }
    }
}