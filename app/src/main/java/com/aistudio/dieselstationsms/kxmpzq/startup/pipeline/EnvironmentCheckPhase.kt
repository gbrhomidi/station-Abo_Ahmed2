package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext

/**
 * ═══════════════════════════════════════════════════════════════
 * فحص بيئة التشغيل - EnvironmentCheckPhase
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 *
 * 1. التحقق من أن بيئة Android مناسبة لبدء startup الطبيعي.
 * 2. التعامل مع حالة Direct Boot قبل فتح المستخدم للجهاز.
 * 3. منع المراحل اللاحقة من التنفيذ عندما تكون بيانات المستخدم
 *    غير متاحة بعد.
 * 4. احترام CancellationToken.
 *
 * مبدأ معماري مهم:
 * ───────────────────────────────────────────────────────────────
 *
 * هذه المرحلة لا تقوم بتشغيل SMSService.
 *
 * كما أنها لا تنتظر USER_UNLOCKED داخل نفس الـ pipeline.
 *
 * إذا كان الجهاز في حالة Direct Boot، يتم إنهاء دورة startup
 * الحالية بفشل غير قابل لإعادة المحاولة، بحيث يمكن لدورة startup
 * جديدة أن تبدأ عند وصول USER_UNLOCKED.
 */
class EnvironmentCheckPhase : InitializationPhase {

    companion object {
        private const val TAG = "EnvironmentCheckPhase"

        private const val DIRECT_BOOT_MESSAGE =
            "Direct Boot: user is locked; waiting for USER_UNLOCKED"
    }

    override val name: String = "EnvironmentCheck"

    /**
     * هذه المرحلة حرجة بالنسبة لمسار startup.
     *
     * إذا كانت بيئة المستخدم غير متاحة، يجب عدم السماح للمراحل
     * التالية مثل PermissionCheck وServiceLaunch بالاستمرار.
     */
    override val isCritical: Boolean = true

    override suspend fun execute(
        ctx: InitializationContext
    ): PhaseResult {

        /*
         * احترام الإلغاء قبل إجراء أي فحص.
         */
        ctx.cancellationToken.throwIfCancelled()

        /*
         * Direct Boot أصبح متاحًا ابتداءً من Android N (API 24).
         *
         * قبل فتح المستخدم للجهاز، قد لا تكون البيانات المحمية
         * الخاصة بالمستخدم متاحة، وبالتالي لا ينبغي بدء دورة
         * startup الطبيعية لخدمة SMS.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            val userManager = ctx.appContext.getSystemService(
                Context.USER_SERVICE
            ) as? UserManager

            /*
             * إذا كان UserManager متاحًا ويؤكد أن المستخدم لم يُفتح
             * بعد، يجب إيقاف هذه الدورة من الـ pipeline.
             *
             * نستخدم Failure بدل Skipped لأن InitializationPipeline
             * يتعامل مع Skipped باعتبارها مرحلة مكتملة ويمكنه الانتقال
             * إلى المراحل التالية.
             *
             * أما Failure مع isCritical=true فيمنع ServiceLaunchPhase
             * من محاولة تشغيل SMSService أثناء Direct Boot.
             *
             * retryable=false لأن إعادة المحاولة داخل نفس الدورة
             * لن تغيّر حالة الجهاز. دورة USER_UNLOCKED اللاحقة
             * هي المسؤولة عن إعادة التشغيل.
             */
            if (userManager?.isUserUnlocked == false) {

                Log.w(
                    TAG,
                    "$DIRECT_BOOT_MESSAGE | reason=${ctx.startupReason.name}"
                )

                return PhaseResult.Failure(
                    error = DIRECT_BOOT_MESSAGE,
                    retryable = false
                )
            }
        }

        /*
         * فحص إلغاء نهائي قبل الإعلان عن نجاح المرحلة.
         */
        ctx.cancellationToken.throwIfCancelled()

        Log.d(
            TAG,
            "Environment check passed | reason=${ctx.startupReason.name}"
        )

        return PhaseResult.Success(
            "Environment check passed"
        )
    }
}