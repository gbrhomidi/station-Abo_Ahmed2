package com.aistudio.dieselstationsms.kxmpzq.startup.retry

/**

* سياسة إعادة المحاولة باستخدام Exponential Backoff.

* 

* هذه السياسة هي التنفيذ الفعلي لعقد RetryPolicy.

* 

* تعتمد على زيادة مدة الانتظار تدريجيًا بين المحاولات بدل إعادة

* التنفيذ بصورة متتالية وسريعة.

* 

* عند:

* 

* backoffMs = 5_000

* 

* تكون فترات الانتظار:

* 

* بعد المحاولة 0 → 5,000 ms

* بعد المحاولة 1 → 10,000 ms

* بعد المحاولة 2 → 20,000 ms

* بعد المحاولة 3 → 40,000 ms

* 

* ويستمر ذلك حتى الحد الأقصى لمعامل exponential المحدد داخليًا.

* 

* ملاحظة:

* ───────────────────────────────────────────────────────────────

* 

* InitializationPipeline يستخدم:

* 

* for (attempt in 0 until retryPolicy.maxAttempts)

* 

* ولذلك فإن:

* 

* maxAttempts = 3

* 

* تعني:

* 

* attempt 0 → المحاولة الأولى

* attempt 1 → المحاولة الثانية

* attempt 2 → المحاولة الثالثة والأخيرة

* 

* ولا توجد محاولة رابعة.
  */
  class ExponentialBackoffRetryPolicy(
  override val maxAttempts: Int,
  override val backoffMs: Long
  ) : RetryPolicy {
  
  init {
  require(maxAttempts > 0) {
  "maxAttempts must be greater than 0"
  }
  
   require(backoffMs >= 0L) {
     "backoffMs must not be negative"
 }
  
  }
  
  /**
  
  * تحديد ما إذا كان الاستثناء الحالي يستحق إعادة المحاولة.
  * 
  * لا يتم تحليل نوع الاستثناء هنا؛ تحديد قابلية إعادة المحاولة
  * من ناحية نوع الخطأ يمكن أن يتم في طبقة أعلى عند الحاجة.
  * 
  * هذه السياسة مسؤولة فقط عن التأكد من عدم تجاوز الحد الأقصى
  * للمحاولات.
  * 
  * مثال عند maxAttempts = 3:
  * 
  * attempt 0 → true
  * attempt 1 → true
  * attempt 2 → false
  
  */
  override fun shouldRetry(
  attempt: Int,
  error: Throwable
  ): Boolean {
  return attempt >= 0 && attempt < maxAttempts - 1
  }
  
  /**
  
  * تحديد ما إذا كانت نتيجة فشل نصية قابلة لإعادة المحاولة.
  * 
  * يتم الاحتفاظ بهذه الدالة لتطبيق عقد RetryPolicy بصورة كاملة.
  * 
  * لا يتم اتخاذ قرار بناءً على محتوى النص نفسه في هذه السياسة؛
  * القرار هنا يعتمد على عدد المحاولات المتبقية.
    */
    override fun shouldRetryResult(
    attempt: Int,
    error: String
    ): Boolean {
    return attempt >= 0 && attempt < maxAttempts - 1
    }
  
  /**
  
  * حساب مدة التأخير باستخدام Exponential Backoff.
  
  * 
  
  * بما أن attempt يبدأ من 0، فإننا نستخدم:
  
  * 
  
  * backoffMs × 2^attempt
  
  * 
  
  * وبالتالي:
  
  * 
  
  * attempt 0 → backoffMs
  
  * attempt 1 → backoffMs × 2
  
  * attempt 2 → backoffMs × 4
  
  * 
  
  * هذا يتوافق مع طريقة استخدام InitializationPipeline:
  
  * 
  
  * delay(retryPolicy.getDelayMs(attempt))
  
  * 
  
  * بعد فشل محاولة قابلة لإعادة المحاولة.
  
  * 
  
  * توجد حماية من:
  
  * 
  
  * 1. attempt السالب.
  
  * 2. overflow في عملية الإزاحة.
  
  * 3. overflow في عملية الضرب.
       */
       override fun getDelayMs(
       attempt: Int
       ): Long {
    
    /*
    
    * لا يوجد معنى لتأخير سلبي.
    * 
    * كما أن attempt السالب لا يمثل حالة صحيحة في
    * InitializationPipeline.
      */
      if (attempt < 0 || backoffMs == 0L) {
      return 0L
      }
    
    /*
    
    * الحد الأعلى لمعامل الإزاحة.
    * 
    * 2^10 = 1024
    * 
    * وهذا يمنع تكوين معاملات ضخمة جدًا عند تمرير attempt
    * غير متوقع أو عند وجود عدد كبير من المحاولات.
      */
      val safeAttempt = attempt.coerceAtMost(MAX_BACKOFF_SHIFT)
    
    val multiplier = 1L shl safeAttempt
    
    /*
    
    * منع Long overflow أثناء:
    * 
    * backoffMs * multiplier
    * 
    * إذا كان الضرب سيتجاوز Long.MAX_VALUE، نعيد الحد
    * الأقصى بدل إنتاج قيمة سالبة بسبب overflow.
      */
      return if (backoffMs > Long.MAX_VALUE / multiplier) {
      Long.MAX_VALUE
      } else {
      backoffMs * multiplier
      }
      }
  
  companion object {
  
   /**
  * الحد الأعلى لمعامل exponential.
  *
  * 1L shl 10 = 1024
  */
 private const val MAX_BACKOFF_SHIFT = 10
  
  }
  }