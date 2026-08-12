package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ═══════════════════════════════════════════════════════════════
 * حارس تنفيذ تهيئة التطبيق - StartupExecutionGuard
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 *
 * 1. منع تشغيل أكثر من Startup Pipeline كامل في الوقت نفسه.
 * 2. ضمان عدم تداخل عمليات Startup المستقلة.
 * 3. السماح لـ InitializationPipeline بإدارة التوازي الداخلي
 *    بين مراحل التهيئة وفق StartupPolicy.allowParallelExecution.
 * 4. الحفاظ على سلوك Cancellation الخاص بالـ Coroutine.
 * 5. ضمان تحرير القفل تلقائيًا حتى عند حدوث Exception أو Cancellation.
 *
 * ملاحظة معمارية مهمة:
 *
 * allowParallelExecution في StartupPolicy لا يتحكم في تشغيل
 * أكثر من Startup Pipeline في الوقت نفسه.
 *
 * بل يتحكم فقط في إمكانية تنفيذ InitializationPhase مستقلة
 * بالتوازي داخل Pipeline واحد.
 *
 * لذلك فإن StartupExecutionGuard يقفل تنفيذ Startup Pipeline
 * الكامل دائمًا، بينما InitializationPipeline هو المسؤول عن
 * التوازي الداخلي للمراحل.
 */
class StartupExecutionGuard {

    companion object {
        private const val TAG = "StartupExecutionGuard"
    }

    /**
     * Mutex مشترك داخل هذا الحارس.
     *
     * Mutex مناسب لهذا الاستخدام لأنه:
     *
     * - لا يحجز Thread.
     * - يوقف Coroutine فقط حتى يصبح القفل متاحًا.
     * - يدعم Cancellation بصورة صحيحة.
     * - يضمن تحرير القفل تلقائيًا بعد انتهاء block.
     */
    private val mutex = Mutex()

    /**
     * تنفيذ Startup Pipeline بصورة محمية من التداخل.
     *
     * @param policy سياسة Startup الحالية.
     * @param block عملية Startup الفعلية.
     *
     * @return نتيجة block.
     *
     * ملاحظة:
     *
     * policy موجود في التوقيع للحفاظ على التوافق مع بقية
     * بنية Startup الحالية.
     *
     * لا نستخدم allowParallelExecution هنا عمدًا؛ لأن هذا
     * الخيار يخص المراحل داخل InitializationPipeline وليس
     * عمليات Startup الكاملة.
     */
    suspend fun <T> execute(
        policy: StartupPolicy,
        block: suspend () -> T
    ): T {

        /*
         * قراءة الخاصية هنا ليست مطلوبة لاتخاذ قرار القفل.
         *
         * التوازي الداخلي تتم إدارته داخل InitializationPipeline.
         *
         * الاحتفاظ بالـ parameter policy مهم للتوافق مع API
         * الحالي للمشروع.
         */
        @Suppress("UNUSED_VARIABLE")
        val startupPolicy = policy

        /*
         * withLock يضمن:
         *
         * 1. انتظار Coroutine حتى يصبح القفل متاحًا.
         * 2. تنفيذ block داخل القفل.
         * 3. تحرير القفل تلقائيًا بعد انتهاء block.
         * 4. تحرير القفل أيضًا عند Exception أو Cancellation.
         */
        return mutex.withLock {
            block()
        }
    }
}