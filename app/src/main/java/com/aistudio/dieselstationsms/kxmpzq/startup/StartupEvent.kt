package com.aistudio.dieselstationsms.kxmpzq.startup

/**

* ═══════════════════════════════════════════════════════════════

* أحداث دورة بدء تشغيل التطبيق - StartupEvent

* ═══════════════════════════════════════════════════════════════

* 

* يمثل هذا النوع جميع الأحداث التي يمكن نشرها أثناء تنفيذ

* Startup Pipeline.

* 

* المسؤولية:

* 

* 1. إعلام EventBus ببدء Pipeline.

* 2. إعلام EventBus ببدء وانتهاء وفشل وتجاوز المراحل.

* 3. إعلام EventBus بانتهاء Pipeline أو فشله.

* 4. نشر تغيرات حالة StartupStateMachine.

* 5. نشر عمليات الإلغاء.

* 

* ملاحظة:

* 

* هذا الملف يعرّف الأحداث فقط ولا ينفذ أي منطق تشغيلي.

* تنفيذ الأحداث ومعالجتها مسؤولية EventBus والمشتركين فيه.
  */
  sealed class StartupEvent {
  
  /**
  
  * تم بدء Startup Pipeline.
  * 
  * @param reason سبب بدء التشغيل.
  * @param correlationId المعرف الفريد لعملية Startup الحالية.
    */
    data class PipelineStarted(
    val reason: StartupReason,
    val correlationId: String
    ) : StartupEvent()
  
  /**
  
  * بدأت مرحلة تهيئة محددة.
  * 
  * @param phase اسم المرحلة.
  * @param correlationId معرف عملية Startup.
    */
    data class PhaseStarted(
    val phase: String,
    val correlationId: String
    ) : StartupEvent()
  
  /**
  
  * اكتملت مرحلة تهيئة بنجاح.
  * 
  * @param phase اسم المرحلة.
  * @param result نتيجة أو وصف تنفيذ المرحلة.
  * @param correlationId معرف عملية Startup.
    */
    data class PhaseCompleted(
    val phase: String,
    val result: String,
    val correlationId: String
    ) : StartupEvent()
  
  /**
  
  * فشلت مرحلة تهيئة.
  * 
  * @param phase اسم المرحلة التي فشلت.
  * @param error وصف الخطأ.
  * @param correlationId معرف عملية Startup.
    */
    data class PhaseFailed(
    val phase: String,
    val error: String,
    val correlationId: String
    ) : StartupEvent()
  
  /**
  
  * تم تجاوز مرحلة دون تنفيذها.
  * 
  * @param phase اسم المرحلة المتجاوزة.
  * @param reason سبب التجاوز.
  * @param correlationId معرف عملية Startup.
    */
    data class PhaseSkipped(
    val phase: String,
    val reason: String,
    val correlationId: String
    ) : StartupEvent()
  
  /**
  
  * اكتمل Startup Pipeline بالكامل.
  * 
  * @param durationMs الزمن المستغرق في تنفيذ Pipeline
  *              بالميلي ثانية.
  * @param phases قائمة المراحل التي اكتملت.
  * @param correlationId معرف عملية Startup.
    */
    data class PipelineCompleted(
    val durationMs: Long,
    val phases: List<String>,
    val correlationId: String
    ) : StartupEvent()
  
  /**
  
  * فشل Startup Pipeline.
  * 
  * @param failedPhase المرحلة التي أدت إلى الفشل.
  * @param error وصف الخطأ.
  * @param correlationId معرف عملية Startup.
    */
    data class PipelineFailed(
    val failedPhase: String,
    val error: String,
    val correlationId: String
    ) : StartupEvent()
  
  /**
  
  * تغيرت حالة StartupStateMachine.
  * 
  * @param oldState الحالة السابقة.
  * @param newState الحالة الجديدة.
  * @param correlationId معرف عملية Startup التي تسببت
  *                 في تغيير الحالة.
  
  */
  data class StateChanged(
  val oldState: StartupStateMachine.State,
  val newState: StartupStateMachine.State,
  val correlationId: String
  ) : StartupEvent()
  
  /**
  
  * تم إلغاء عملية Startup.
  * 
  * @param correlationId معرف عملية Startup الملغاة.
  * @param reason سبب الإلغاء.
    */
    data class Cancelled(
    val correlationId: String,
    val reason: String
    ) : StartupEvent()
    }