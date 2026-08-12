package com.aistudio.dieselstationsms.kxmpzq.startup.recovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.di.StartupCompositionRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**

* ═══════════════════════════════════════════════════════════════

* عامل استرداد بدء التشغيل - StartupRecoveryWorker

* ═══════════════════════════════════════════════════════════════

* 

* المسؤولية:

* 

* 1. إعادة تشغيل مسار startup عند الحاجة إلى recovery.

* 2. إنشاء ApplicationInitializationCoordinator من

* StartupCompositionRoot باستخدام applicationContext.

* 3. تشغيل coordinator باستخدام StartupReason.CRASH_RECOVERY.

* 4. انتظار اكتمال الـ startup pipeline بطريقة آمنة.

* 5. إعادة Result.success() عند اكتمال العملية.

* 6. إعادة Result.retry() عند الفشل أو عدم اكتمال العملية ضمن

* المهلة المحددة.

* 

* مبدأ معماري مهم:

* ───────────────────────────────────────────────────────────────

* هذا الـ Worker لا يقوم بتشغيل SMSService مباشرة.

* 

* المسار هو:

* 

* WorkManager

*  ↓

* StartupRecoveryWorker

*  ↓

* StartupCompositionRoot

*  ↓

* ApplicationInitializationCoordinator

*  ↓

* StartupPolicyFactory

*  ↓

* CRASH_RECOVERY policy

*  ↓

* InitializationPipeline

*  ↓

* ServiceLaunchPhase

*  ↓

* ServiceLauncher

* 

* وبذلك تبقى مسؤوليات تشغيل الخدمة منفصلة عن WorkManager.
  */
  class StartupRecoveryWorker(
  context: Context,
  params: WorkerParameters
  ) : CoroutineWorker(context, params) {
  
  companion object {
  
   /**
  * الاسم الثابت المستخدم لجدولة/إدارة مهمة recovery
  * عبر WorkManager.
  */
 const val WORK_NAME = "startup_recovery_work"

 /**
  * الحد الأقصى لانتظار اكتمال ApplicationInitializationCoordinator.
  *
  * يجب أن يكون هذا الحد متوافقًا مع حدود pipeline الحالية.
  */
 private const val COMPLETION_TIMEOUT_MS = 60_000L
  
  }
  
  /**
  
  * تنفيذ مهمة استرداد startup.
  
  * 
  
  * يتم تشغيل الـ coordinator بصورة asynchronous، لذلك نستخدم
  
  * CompletableDeferred بدل متغير Boolean مشترك لانتظار اكتمال
  
  * callback بطريقة coroutine-safe.
    */
    override suspend fun doWork(): Result {
    
    /*
    
    * إنشاء coordinator باستخدام applicationContext يمنع
    * الاحتفاظ بمرجع إلى Context قصير العمر.
      */
      val coordinator = StartupCompositionRoot.createCoordinator(
      applicationContext
      )
    
    /*
    
    * CompletableDeferred تمثل إشارة اكتمال pipeline.
    * 
    * false = لم يكتمل بعد
    * true  = تم استدعاء onComplete
      */
      val completed = CompletableDeferred<Unit>()
    
    return try {
    
     /*
  * مهم جدًا:
  *
  * هذا Worker مخصص للاسترداد، لذلك يجب تمرير
  * CRASH_RECOVERY وليس SCHEDULED.
  *
  * StartupPolicyFactory يستخدم هذا السبب لاختيار
  * مسار recovery المناسب.
  */
 coordinator.execute(
     context = applicationContext,
     reason = StartupReason.CRASH_RECOVERY,
     action = WORK_NAME,
     onComplete = {
         completed.complete(Unit)
     }
 )

 /*
  * coordinator.execute() لا ينتظر انتهاء الـ pipeline
  * بنفسه؛ لذلك ننتظر callback الخاص بـ onComplete.
  *
  * إذا انتهت المهلة قبل وصول callback، نطلب من WorkManager
  * إعادة المحاولة.
  */
 val finished = withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
     completed.await()
     true
 } ?: false

 if (finished) {
     Result.success()
 } else {
     Result.retry()
 }
    
    } catch (e: CancellationException) {
    
     /*
  * لا نبتلع CancellationException.
  *
  * WorkManager قد يلغي Worker بسبب إيقاف/إعادة جدولة
  * العمل، ويجب أن يصل الإلغاء إلى coroutine lifecycle
  * بصورة صحيحة.
  */
 throw e
    
    } catch (e: Exception) {
    
     /*
  * أي استثناء غير متوقع أثناء إنشاء coordinator أو بدء
  * pipeline يجعل المهمة قابلة لإعادة المحاولة.
  *
  * لا نعتبر recovery ناجحًا عند حدوث استثناء.
  */
 Result.retry()
    
    }
    }
    }