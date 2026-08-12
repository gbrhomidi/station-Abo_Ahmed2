package com.aistudio.dieselstationsms.kxmpzq.startup

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**

* ═══════════════════════════════════════════════════════════════

* رمز إلغاء عملية بدء التشغيل - CancellationToken

* ═══════════════════════════════════════════════════════════════

* 

* يستخدم هذا الكائن كإشارة مشتركة لإلغاء عملية Startup Pipeline

* أثناء تنفيذ مراحل التهيئة.

* 

* المسؤولية:

* 

* 1. تسجيل طلب الإلغاء.

* 2. توفير حالة الإلغاء الحالية.

* 3. إيقاف التنفيذ عند وصول الـ Pipeline إلى نقطة فحص الإلغاء.

* 

* ملاحظة:

* 

* CancellationToken لا يلغي Coroutine مباشرة.

* الإلغاء الفعلي للـ Coroutine تتم إدارته بواسطة Coroutine/Job

* نفسه، بينما هذا الكائن يوفر إشارة إلغاء تعاونية Cooperative

* Cancellation يمكن لمراحل التهيئة فحصها.
  */
  class CancellationToken {
  
  /**
  
  * AtomicBoolean لضمان رؤية وتحديث حالة الإلغاء بشكل آمن
  * عند الوصول إليها من أكثر من Coroutine أو Thread.
    */
    private val cancelled = AtomicBoolean(false)
  
  /**
  
  * تسجيل طلب إلغاء عملية التهيئة.
  * 
  * العملية idempotent:
  * استدعاء cancel() عدة مرات لا يسبب أي مشكلة ولا يعيد
  * حالة الإلغاء إلى false.
    */
    fun cancel() {
    cancelled.set(true)
    }
  
  /**
  
  * التحقق مما إذا كان قد تم طلب إلغاء عملية التهيئة.
    */
    fun isCancelled(): Boolean {
    return cancelled.get()
    }
  
  /**
  
  * إيقاف التنفيذ عند اكتشاف أن عملية التهيئة قد أُلغيت.
  * 
  * يتم استخدام CancellationException حتى تتعامل معها
  * coroutines باعتبارها عملية إلغاء طبيعية، وليس خطأً تشغيليًا.
    */
    fun throwIfCancelled() {
    if (cancelled.get()) {
    throw CancellationException("Startup cancelled")
    }
    }
    }