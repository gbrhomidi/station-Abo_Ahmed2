package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**

* ═══════════════════════════════════════════════════════════════

* حارس تنفيذ تهيئة التطبيق - StartupExecutionGuard

* ═══════════════════════════════════════════════════════════════

* 

* المسؤولية:

* 

* 1. منع تشغيل أكثر من Startup Pipeline كامل في الوقت نفسه.

* 2. ضمان أن مراحل التهيئة العامة لا تتداخل مع بعضها بطريقة

* قد تؤدي إلى حالات سباق أو تهيئة مزدوجة للخدمات.

* 3. السماح للـ InitializationPipeline نفسه بإدارة التوازي بين

* المراحل المستقلة وفق StartupPolicy.allowParallelExecution.

* 4. الحفاظ على الإلغاء Cancellation بشكل صحيح من خلال Mutex.

* 

* ملاحظة مهمة:

* 

* allowParallelExecution في StartupPolicy يخص التوازي بين مراحل

* Pipeline المستقلة، وليس السماح بتشغيل Pipeline كامل ثانٍ

* بالتوازي مع Pipeline أول.

* 

* لذلك فإن هذا الحارس يقفل تنفيذ الـ Pipeline الكامل دائمًا،

* بينما يبقى قرار تشغيل المراحل داخله بالتوازي مسؤولية

* InitializationPipeline.
  */
  class StartupExecutionGuard {
  
  /**
  
  * قفل وحيد مشترك بين جميع عمليات تنفيذ StartupExecutionGuard
  * الخاصة بهذا الكائن.
  * 
  * Mutex غير حاجب للخيط Thread ولا يسبب Block للـ Dispatcher،
  * بل يوقف Coroutine حتى يصبح القفل متاحًا.
    */
    private val mutex = Mutex()
  
  /**
  
  * تنفيذ Startup Pipeline بشكل محمي من التداخل.
  
  * 
  
  * @param policy سياسة التشغيل الحالية.
  
  * @param block عملية التهيئة الفعلية.
  
  * 
  
  * لا يتم استخدام allowParallelExecution لتجاوز هذا القفل؛
  
  * لأن هذا الخيار يتحكم في توازي InitializationPhase داخل
  
  * InitializationPipeline، وليس في توازي عمليات Startup الكاملة.
    /
    suspend fun <T> execute(
    policy: StartupPolicy,
    block: suspend () -> T
    ): T {
    /
    
    * الاحتفاظ بالـ policy في التوقيع ضروري للتوافق مع
    * الاستدعاءات الحالية وبنية Startup Pipeline.
    * 
    * لا نستخدم allowParallelExecution هنا عمدًا؛ لأن السماح
    * بتشغيل Pipeline كامل بالتوازي قد يؤدي إلى:
    * 
    * - تشغيل SMSService أكثر من مرة.
    * - تهيئة موارد مشتركة أكثر من مرة.
    * - تعارض في StartupStateMachine.
    * - تنفيذ مراحل تعتمد على حالة مشتركة في الوقت نفسه.
    * - تكرار عمليات التسجيل والمراقبة والـ health checks.
    * 
    * التوازي المطلوب تتم إدارته داخل InitializationPipeline.
      */
      policy.allowParallelExecution
    
    return mutex.withLock {
    block()
    }
    }
    }