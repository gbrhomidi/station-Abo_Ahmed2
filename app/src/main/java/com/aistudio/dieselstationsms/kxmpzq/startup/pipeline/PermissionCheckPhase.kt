package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext
import kotlinx.coroutines.CancellationException

/**

* ═══════════════════════════════════════════════════════════════

* مرحلة التحقق من صلاحيات SMS - PermissionCheckPhase

* ═══════════════════════════════════════════════════════════════

* 

* المسؤولية:

* 1. التحقق من توفر الصلاحيات المطلوبة لتشغيل منظومة SMS.

* 2. عدم طلب الصلاحيات من داخل startup pipeline.

* 3. إيقاف المرحلة بصورة صحيحة عند طلب الإلغاء.

* 4. إعادة Failure واضح عند وجود صلاحيات مفقودة.

* 5. اعتبار فشل الصلاحيات غير قابل لإعادة المحاولة تلقائيًا.

* 

* مبدأ معماري مهم:

* ───────────────────────────────────────────────────────────────

* هذه المرحلة مسؤولة عن CHECK فقط.

* 

* لا تقوم هذه المرحلة باستدعاء:

* 

* - ActivityCompat.requestPermissions()

* - requestPermissions()

* - أي واجهة UI

* 

* لأن مرحلة startup قد تعمل من BroadcastReceiver أو Worker أو

* سياق لا يملك واجهة Activity.

* 

* منح الصلاحيات يجب أن يتم في طبقة UI/Permission Manager المناسبة،

* ثم تعاد محاولة startup بعد منح الصلاحيات.
  */
  class PermissionCheckPhase : InitializationPhase {
  
  companion object {
  private const val TAG = "PermissionCheckPhase"
  }
  
  override val name: String = "PermissionCheck"
  
  /**
  
  * التحقق من الصلاحيات حرج بالنسبة لمسار SMS.
  * 
  * إذا كانت الصلاحيات الأساسية مفقودة، فلا ينبغي للـ startup
  * اعتبار الخدمة جاهزة للعمل.
    */
    override val isCritical: Boolean = true
  
  /**
  
  * الصلاحيات الأساسية المطلوبة لمنظومة SMS.
  
  * 
  
  * RECEIVE_SMS:
  
  * مطلوب لاستقبال رسائل SMS.
  
  * 
  
  * SEND_SMS:
  
  * مطلوب إذا كانت منظومة التطبيق تقوم بإرسال SMS.
    */
    private val criticalPermissions: Array<String>
    get() = buildList {
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.SEND_SMS)
    
     /*
  * READ_SMS تتم إضافتها فقط إذا كان التطبيق يحتاجها
  * فعليًا لقراءة الرسائل الموجودة في صندوق SMS.
  *
  * لا نضيفها افتراضيًا هنا حتى لا نوسع نطاق الصلاحيات
  * المطلوبة دون دليل من منطق التطبيق.
  */
    
    }.toTypedArray()
  
  override suspend fun execute(
  ctx: InitializationContext
  ): PhaseResult {
  
   /*
  * يجب التحقق من الإلغاء قبل بدء أي عملية.
  */
 ctx.cancellationToken.throwIfCancelled()

 return try {

     val appContext = ctx.appContext

     /*
      * التأكد من أن Context صالح.
      *
      * InitializationContext يفترض أصلًا وجود Context صالح،
      * لذلك هذا مجرد حارس دفاعي ولا يغير المسار الطبيعي.
      */
     if (appContext.applicationContext == null) {
         Log.e(TAG, "Application context is unavailable")

         return PhaseResult.Failure(
             error = "Application context is unavailable",
             retryable = false
         )
     }

     val missingPermissions = criticalPermissions.filter { permission ->

         /*
          * إعادة فحص الإلغاء أثناء المرور على الصلاحيات.
          */
         ctx.cancellationToken.throwIfCancelled()

         /*
          * ContextCompat يوفر فحصًا متوافقًا مع إصدارات Android
          * المختلفة.
          */
         ContextCompat.checkSelfPermission(
             appContext,
             permission
         ) != PackageManager.PERMISSION_GRANTED
     }

     if (missingPermissions.isEmpty()) {

         Log.i(
             TAG,
             "All critical SMS permissions are granted"
         )

         PhaseResult.Success(
             "All critical SMS permissions granted"
         )

     } else {

         val readablePermissions = missingPermissions
             .joinToString(separator = ", ")

         Log.e(
             TAG,
             "Missing critical SMS permissions: $readablePermissions"
         )

         /*
          * لا نعيد المحاولة تلقائيًا.
          *
          * إعادة تشغيل pipeline لن تمنح الصلاحيات المفقودة.
          * يجب أن يمنحها المستخدم أولًا، ثم يمكن إعادة تشغيل
          * startup.
          */
         PhaseResult.Failure(
             error = "Missing critical permissions: $readablePermissions",
             retryable = false
         )
     }

 } catch (e: CancellationException) {

     /*
      * لا يجب ابتلاع CancellationException.
      *
      * يجب أن تصل إلى InitializationPipeline/Coordinator حتى
      * ينتقل startup إلى حالة CANCELLED بصورة صحيحة.
      */
     Log.i(
         TAG,
         "Permission check cancelled"
     )

     throw e

 } catch (e: SecurityException) {

     /*
      * حماية إضافية من أي SecurityException صادر من Android
      * أثناء فحص الصلاحيات.
      */
     Log.e(
         TAG,
         "Security exception while checking permissions",
         e
     )

     PhaseResult.Failure(
         error = "Security error while checking permissions: " +
             (e.message ?: e.javaClass.simpleName),
         retryable = false
     )

 } catch (e: Exception) {

     /*
      * أي خطأ غير متوقع أثناء الفحص لا يجب أن يتحول إلى Success.
      *
      * نسمح بإعادة المحاولة لأن الخطأ قد يكون مؤقتًا أو بيئيًا.
      */
     Log.e(
         TAG,
         "Unexpected error while checking permissions",
         e
     )

     PhaseResult.Failure(
         error = "Unexpected permission check error: " +
             (e.message ?: e.javaClass.simpleName),
         retryable = true
     )
 }
  
  }
  }