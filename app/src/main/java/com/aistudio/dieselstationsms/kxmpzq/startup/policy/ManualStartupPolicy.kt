package com.aistudio.dieselstationsms.kxmpzq.startup.policy

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase

/**

* ═══════════════════════════════════════════════════════════════

* سياسة بدء التشغيل غير المرتبط بالإقلاع - ManualStartupPolicy

* ═══════════════════════════════════════════════════════════════

* 

* هذه السياسة تستخدم لمسارات startup التي لا تنتمي إلى مسار

* BOOT، ويتم اختيارها حاليًا بواسطة StartupPolicyFactory.

* 

* أمثلة:

* 

* MANUAL

* CRASH_RECOVERY

* APP_UPDATED

* TIME_CHANGED

* TIMEZONE_CHANGED

* ALARM

* SCHEDULED

* USER_UNLOCKED

* 

* مبدأ معماري مهم:

* ───────────────────────────────────────────────────────────────

* اسم ManualStartupPolicy لا يعني أن سبب التشغيل يجب أن يكون

* MANUAL.

* 

* السبب الحقيقي يتم تمريره من StartupPolicyFactory إلى constructor

* حتى تبقى:

* 

* policy.reason == startupReason

* 

* صحيحة دائمًا.

* 

* هذه الفئة لا تنفذ أي Phase بنفسها؛ بل تصف فقط سياسة تنفيذ

* InitializationPipeline.
  */
  class ManualStartupPolicy(
  override val phases: List<InitializationPhase>,
  override val reason: StartupReason
  ) : StartupPolicy {
  
  /**
  
  * يسمح هذا المسار بتنفيذ المراحل الجاهزة بصورة متوازية عندما
  * يسمح DAG بذلك.
  * 
  * ملاحظة:
  * وجود dependencies بين المراحل يمنع تنفيذ المراحل المرتبطة
  * قبل اكتمال متطلباتها، حتى مع السماح بالتوازي.
    */
    override val allowParallelExecution: Boolean = true
  
  /**
  
  * الفشل في مرحلة غير حرجة لا يمنع استمرار المسار.
  * 
  * لكن InitializationPipeline يظل يحترم:
  * 
  * InitializationPhase.isCritical
  * 
  * وبالتالي فإن فشل مرحلة حرجة سيؤدي إلى فشل الـ pipeline
  * وفق المنطق الحالي للـ InitializationPipeline.
    */
    override val continueOnFailure: Boolean = true
  
  /**
  
  * يتم تفعيل HealthCheck إذا كانت سياسة startup تحتوي فعليًا
  * على مرحلة HealthCheck.
  * 
  * هذا يمنع وجود تناقض بين:
  * 
  * healthCheckEnabled
  * 
  * وقائمة:
  * 
  * phases
  
  */
  override val healthCheckEnabled: Boolean =
  phases.any { it.name == "HealthCheck" }
  
  /**
  
  * جمع المقاييس مفعل للمسارات غير الخاصة بـ BOOT أيضًا.
  * 
  * ApplicationInitializationCoordinator يتحقق بالإضافة إلى
  * ذلك من ConfigurationProvider.getMetricsEnabled().
    */
    override val metricsEnabled: Boolean = true
    }