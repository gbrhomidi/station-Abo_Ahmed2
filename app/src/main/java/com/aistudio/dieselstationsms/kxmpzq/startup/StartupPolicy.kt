package com.aistudio.dieselstationsms.kxmpzq.startup

import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase

/**

* ═══════════════════════════════════════════════════════════════

* سياسة بدء تشغيل التطبيق - StartupPolicy

* ═══════════════════════════════════════════════════════════════

* 

* تمثل هذه الواجهة العقد الأساسي الذي يحدد كيفية تنفيذ عملية

* تهيئة التطبيق لكل StartupReason.

* 

* المسؤولية:

* 

* 1. تحديد سبب بدء التشغيل.

* 2. تحديد ما إذا كان مسموحًا بتشغيل مراحل التهيئة المستقلة

* بالتوازي.

* 3. تحديد كيفية التعامل مع فشل المراحل غير الحرجة.

* 4. تحديد ما إذا كانت فحوصات الصحة Health Checks مطلوبة.

* 5. تحديد ما إذا كان تسجيل المقاييس Metrics مطلوبًا.

* 6. تحديد قائمة مراحل التهيئة وترتيب اعتمادها.

* 

* ملاحظة معمارية مهمة:

* 

* allowParallelExecution يخص المراحل المستقلة داخل

* InitializationPipeline فقط.

* 

* لا يعني ذلك السماح بتشغيل Startup Pipeline كامل بالتوازي

* مع Pipeline آخر؛ منع هذا التداخل مسؤولية

* StartupExecutionGuard.
  */
  interface StartupPolicy {
  
  /**
  
  * سبب بدء عملية التهيئة الحالية.
  * 
  * تستخدمه السياسة لتحديد مجموعة المراحل المناسبة
  * لطريقة بدء التشغيل الحالية.
    */
    val reason: StartupReason
  
  /**
  
  * السماح بتنفيذ مراحل التهيئة المستقلة بالتوازي.
  * 
  * عندما تكون true، يستطيع InitializationPipeline تنفيذ
  * المراحل الجاهزة ذات الاعتماديات المكتملة بالتوازي.
  * 
  * لا تستخدم هذه الخاصية للسماح بتشغيل Pipeline كامل
  * بالتوازي مع Pipeline آخر.
    */
    val allowParallelExecution: Boolean
  
  /**
  
  * تحديد ما إذا كان يجب الاستمرار بعد فشل مرحلة غير حرجة.
  * 
  * يجب أن يُحترم هذا الخيار بواسطة طبقة تنفيذ الـ Pipeline،
  * وليس بواسطة هذا العقد نفسه.
    */
    val continueOnFailure: Boolean
  
  /**
  
  * تحديد ما إذا كان فحص صحة النظام مطلوبًا ضمن سياسة
  * بدء التشغيل الحالية.
  * 
  * التنفيذ الفعلي لفحص الصحة مسؤولية HealthMonitor ومراحل
  * التهيئة التي تستخدمه.
    */
    val healthCheckEnabled: Boolean
  
  /**
  
  * تحديد ما إذا كان تسجيل مقاييس عملية بدء التشغيل مطلوبًا.
  * 
  * التنفيذ الفعلي للتسجيل مسؤولية MetricsCollector والـ
  * ApplicationInitializationCoordinator.
    */
    val metricsEnabled: Boolean
  
  /**
  
  * مراحل التهيئة التي تشكل Pipeline الخاص بهذه السياسة.
  * 
  * الاعتماديات بين المراحل تحددها InitializationPhase
  * ويتم التحقق منها وترتيبها بواسطة DagEngine.
    */
    val phases: List<InitializationPhase>
    }