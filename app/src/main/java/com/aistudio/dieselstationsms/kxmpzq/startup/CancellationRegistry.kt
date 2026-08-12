package com.aistudio.dieselstationsms.kxmpzq.startup

import java.util.concurrent.ConcurrentHashMap

/**

* ═══════════════════════════════════════════════════════════════

* سجل عمليات الإلغاء - CancellationRegistry

* ═══════════════════════════════════════════════════════════════

* 

* المسؤولية:

* 

* 1. تسجيل CancellationToken لكل Startup Pipeline نشط.

* 2. الوصول إلى عملية محددة بواسطة correlationId.

* 3. إرسال طلب إلغاء إلى عملية محددة.

* 4. إرسال طلب إلغاء إلى جميع العمليات النشطة.

* 5. إزالة التسجيل عند انتهاء عملية التهيئة.

* 6. توفير عدد عمليات Startup المسجلة حاليًا.

* 

* ملاحظة معمارية مهمة:

* 

* هذا السجل لا يدير Coroutine/Job مباشرة.

* هو يدير CancellationToken فقط.

* 

* إزالة الـ token من السجل تتم بواسطة remove() عند انتهاء

* عملية التهيئة، بينما cancel() يرسل طلب الإلغاء ويُبقي

* التسجيل موجودًا حتى تنتهي العملية فعليًا.
  */
  class CancellationRegistry {
  
  /**
  
  * سجل آمن للتزامن يسمح بالوصول من عدة Threads/Coroutines
  * في الوقت نفسه.
  * 
  * المفتاح:
  * correlationId
  * 
  * القيمة:
  * CancellationToken الخاص بعملية Startup.
    */
    private val tokens = ConcurrentHashMap<String, CancellationToken>()
  
  /**
  
  * تسجيل عملية Startup جديدة.
  
  * 
  
  * إذا كان correlationId موجودًا مسبقًا، يتم استبدال الـ token
  
  * السابق بالـ token الجديد.
  
  * 
  
  * هذا السلوك يحافظ على ضمان وجود Token واحد فقط لكل
  
  * correlationId.
    */
    fun register(
    correlationId: String,
    token: CancellationToken
    ) {
    require(correlationId.isNotBlank()) {
    "correlationId must not be blank"
    }
    
    tokens[correlationId] = token
    }
  
  /**
  
  * إرسال طلب إلغاء إلى عملية Startup محددة.
  * 
  * لا تتم إزالة الـ token هنا.
  * 
  * السبب:
  * العملية قد تكون ما زالت تعمل، ويجب أن يبقى تسجيلها موجودًا
  * حتى تنتهي فعليًا، ثم يقوم الـ coordinator باستدعاء remove().
  * 
  * @return true إذا كانت العملية موجودة وتم إرسال طلب الإلغاء،
  *     وإلا false.
  
  */
  fun cancel(correlationId: String): Boolean {
  val token = tokens[correlationId] ?: return false
  token.cancel()
  return true
  }
  
  /**
  
  * إزالة عملية Startup من السجل.
  * 
  * تستخدم عند انتهاء العملية فعليًا، سواء بالنجاح أو الفشل
  * أو الإلغاء.
    */
    fun remove(correlationId: String) {
    if (correlationId.isBlank()) return
    tokens.remove(correlationId)
    }
  
  /**
  
  * إرسال طلب إلغاء إلى جميع عمليات Startup المسجلة.
  
  * 
  
  * لا نقوم بتعديل الـ Map أثناء المرور على values؛
  
  * ConcurrentHashMap تسمح بالمرور الآمن، ثم يتم تنظيف السجل
  
  * بالكامل بعد إرسال طلبات الإلغاء.
    */
    fun cancelAll() {
    tokens.values.forEach { token ->
    token.cancel()
    }
    
    tokens.clear()
    }
  
  /**
  
  * إرجاع عدد عمليات Startup المسجلة حاليًا.
    */
    fun activeCount(): Int {
    return tokens.size
    }
    }