package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

/**

* ═══════════════════════════════════════════════════════════════

* نظام OTP مع حفظ آمن في قاعدة البيانات

* SmsSecurityOTP - Production Version

* ═══════════════════════════════════════════════════════════════

* 

* المسؤوليات:

* 1. توليد OTP.

* 2. حفظ OTP في جدول sms_otp_verifications.

* 3. التحقق من OTP.

* 4. إدارة مدة الصلاحية.

* 5. إدارة الحد الأقصى لمحاولات التحقق.

* 6. تنظيف OTP المنتهية.

* 

* قواعد الأمان:

* - OTP مكوّن من 4 أرقام.

* - مدة الصلاحية: 5 دقائق.

* - الحد الأقصى لمحاولات التحقق: 3.

* - لا يتم تسجيل OTP في Log.

* - يتم تطبيع رقم الهاتف قبل التخزين والتحقق.

* 

* ملكية الجدول:

* sms_otp_verifications

* 

* هذا الجدول مملوك لهذا المكوّن فقط.

* 

* ملاحظة معمارية:

* لا يحتوي هذا الملف على منطق الطلبات أو العملاء أو الأسعار

* أو الفواتير أو الأرصدة أو الكميات.
  */
  class SmsSecurityOTP(private val db: DatabaseHelper) {
  
  companion object {
  private const val TAG = "SmsSecurityOTP"
  
   /** مدة صلاحية OTP = 5 دقائق */
 private const val OTP_EXPIRY_MS = 300000L

 /** الحد الأقصى لمحاولات التحقق */
 private const val OTP_MAX_ATTEMPTS = 3

 /** طول رمز OTP */
 private const val OTP_LENGTH = 4

 /** بداية نطاق OTP */
 private const val OTP_MIN = 1000

 /** نهاية نطاق OTP */
 private const val OTP_MAX = 9999

 /** اسم جدول OTP */
 private const val OTP_TABLE = "sms_otp_verifications"

 /** مولد أرقام عشوائية آمن تشفيريًا */
 private val SECURE_RANDOM = SecureRandom()
  
  }
  
  /**
  
  * نموذج بيانات OTP.
    */
    data class OTPData(
    val code: String,
    val timestamp: Long = System.currentTimeMillis(),
    var attempts: Int = 0,
    val maxAttempts: Int = OTP_MAX_ATTEMPTS
    )
  
  // ═══════════════════════════════════════════════════════════════
  // Database helpers
  // ═══════════════════════════════════════════════════════════════
  
  private val writableDb: SQLiteDatabase
  get() = db.writableDatabase
  
  private val readableDb: SQLiteDatabase
  get() = db.readableDatabase
  
  // ═══════════════════════════════════════════════════════════════
  // Phone normalization
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * تطبيع رقم الهاتف قبل استخدامه كمفتاح OTP.
  
  * 
  
  * الهدف:
  
  * منع اختلاف صيغ الرقم من التسبب في إنشاء OTP لرقم
  
  * والتحقق منه باستخدام صيغة أخرى لنفس العميل.
    */
    private fun normalizePhone(phone: String?): String? {
    if (phone.isNullOrBlank()) {
    return null
    }
    
    return try {
    PhoneUtils.normalize(phone)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
    Log.e(
    TAG,
    "Phone normalization failed: ${e.javaClass.simpleName}"
    )
    null
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // OTP generation
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * توليد OTP جديد وحفظه في قاعدة البيانات.
  
  * 
  
  * كل طلب OTP جديد يلغي OTP السابق لنفس رقم الهاتف.
    */
    suspend fun generateOTP(phone: String): String = withContext(Dispatchers.IO) {
    
    val normalizedPhone =
    normalizePhone(phone)
    ?: throw IllegalArgumentException("Invalid phone number")
    
    val code = generateSecureOtp()
    val now = System.currentTimeMillis()
    val expiresAt = now + OTP_EXPIRY_MS
    
    val database = writableDb
    
    database.beginTransaction()
    
    try {
    /*
    * لا نسمح بوجود أكثر من OTP فعال منطقيًا
    * لنفس رقم الهاتف.
    */
    database.delete(
    OTP_TABLE,
    "phone = ?",
    arrayOf(normalizedPhone)
    )
    
     val values = ContentValues().apply {
     put("phone", normalizedPhone)
     put("otp_code", code)
     put("timestamp", now)
     put("attempts", 0)
     put("max_attempts", OTP_MAX_ATTEMPTS)
     put("expires_at", expiresAt)
 }

 val rowId =
     database.insert(
         OTP_TABLE,
         null,
         values
     )

 if (rowId == -1L) {
     throw IllegalStateException(
         "Failed to store OTP"
     )
 }

 database.setTransactionSuccessful()

 /*
  * لا نسجل الكود نفسه في Log.
  */
 Log.d(
     TAG,
     "OTP generated successfully for phone=${maskPhone(normalizedPhone)}"
 )

 code
    
    } finally {
    database.endTransaction()
    }
    }
  
  /**
  
  * توليد OTP باستخدام SecureRandom.
    */
    private fun generateSecureOtp(): String {
    val value =
    OTP_MIN + SECURE_RANDOM.nextInt(
    OTP_MAX - OTP_MIN + 1
    )
    
    return value
    .toString()
    .padStart(OTP_LENGTH, '0')
    }
  
  // ═══════════════════════════════════════════════════════════════
  // OTP verification
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * التحقق من OTP.
  
  * 
  
  * قواعد التحقق:
  
  * 
  
  * 1. الرقم يجب أن يكون صالحًا.
  
  * 2. OTP يجب أن يكون موجودًا.
  
  * 3. OTP يجب ألا يكون منتهي الصلاحية.
  
  * 4. يجب ألا يكون قد تجاوز عدد المحاولات.
  
  * 5. تتم مقارنة الرمز بطريقة لا تكشف اختلاف الطول/المحتوى
  
  * عبر مقارنة نصية عادية فقط.
  
  * 6. عند نجاح التحقق يتم حذف OTP مباشرة.
  
  * 7. عند فشل التحقق تزداد المحاولات.
  
  * 8. عند استنفاد المحاولات يتم حذف OTP.
  
  * 
  
  * العملية كلها داخل transaction لمنع سباق محاولات التحقق
  
  * المتزامنة.
    */
    suspend fun verifyOTP(
    phone: String,
    code: String
    ): Boolean = withContext(Dispatchers.IO) {
    
    val normalizedPhone =
    normalizePhone(phone)
    ?: return@withContext false
    
    val submittedCode =
    code.trim()
    
    if (
    submittedCode.length != OTP_LENGTH ||
    !submittedCode.all { it.isDigit() }
    ) {
    return@withContext false
    }
    
    val database = writableDb
    
    database.beginTransaction()
    
    try {
    
     var cursor: Cursor? = null

 try {
     cursor = database.rawQuery(
         """
         SELECT
             otp_code,
             timestamp,
             attempts,
             max_attempts,
             expires_at
         FROM $OTP_TABLE
         WHERE phone = ?
         LIMIT 1
         """.trimIndent(),
         arrayOf(normalizedPhone)
     )

     if (!cursor.moveToFirst()) {
         return@withContext false
     }

     val otpCode =
         cursor.getString(
             cursor.getColumnIndexOrThrow("otp_code")
         )

     val timestamp =
         cursor.getLong(
             cursor.getColumnIndexOrThrow("timestamp")
         )

     val attempts =
         cursor.getInt(
             cursor.getColumnIndexOrThrow("attempts")
         )

     val maxAttempts =
         cursor.getInt(
             cursor.getColumnIndexOrThrow("max_attempts")
         )

     val expiresAt =
         cursor.getLong(
             cursor.getColumnIndexOrThrow("expires_at")
         )

     val otpData =
         OTPData(
             code = otpCode,
             timestamp = timestamp,
             attempts = attempts,
             maxAttempts =
                 if (maxAttempts > 0) {
                     maxAttempts
                 } else {
                     OTP_MAX_ATTEMPTS
                 }
         )

     val now =
         System.currentTimeMillis()

     /*
      * التحقق من الصلاحية الزمنية.
      *
      * نستخدم >= حتى لا يبقى OTP صالحًا
      * في اللحظة الدقيقة لانتهاء صلاحيته.
      */
     if (now >= expiresAt) {

         database.delete(
             OTP_TABLE,
             "phone = ?",
             arrayOf(normalizedPhone)
         )

         database.setTransactionSuccessful()

         return@withContext false
     }

     /*
      * التأكد من عدم تجاوز عدد المحاولات.
      */
     if (
         otpData.attempts >=
         otpData.maxAttempts
     ) {

         database.delete(
             OTP_TABLE,
             "phone = ?",
             arrayOf(normalizedPhone)
         )

         database.setTransactionSuccessful()

         return@withContext false
     }

     /*
      * المقارنة الآمنة نسبيًا للرمز.
      *
      * لا نستخدم String.equals مباشرة للرمز الخام.
      */
     val isValid =
         secureCodeEquals(
             otpData.code,
             submittedCode
         )

     if (isValid) {

         /*
          * نجاح التحقق:
          * حذف OTP مباشرة حتى لا يمكن إعادة استخدامه.
          */
         database.delete(
             OTP_TABLE,
             "phone = ?",
             arrayOf(normalizedPhone)
         )

         database.setTransactionSuccessful()

         Log.d(
             TAG,
             "OTP verified successfully for phone=${maskPhone(normalizedPhone)}"
         )

         return@withContext true
     }

     /*
      * محاولة فاشلة:
      * زيادة العداد.
      */
     val newAttempts =
         otpData.attempts + 1

     if (
         newAttempts >=
         otpData.maxAttempts
     ) {

         /*
          * تم استنفاد المحاولات.
          * لا داعي للاحتفاظ بالرمز.
          */
         database.delete(
             OTP_TABLE,
             "phone = ?",
             arrayOf(normalizedPhone)
         )

     } else {

         val values =
             ContentValues().apply {
                 put(
                     "attempts",
                     newAttempts
                 )
             }

         database.update(
             OTP_TABLE,
             values,
             "phone = ?",
             arrayOf(normalizedPhone)
         )
     }

     database.setTransactionSuccessful()

     false

 } finally {
     cursor?.close()
 }
    
    } finally {
    database.endTransaction()
    }
    }
  
  /**
  
  * مقارنة رمز OTP دون الاعتماد على مقارنة نصية مباشرة.
    */
    private fun secureCodeEquals(
    expected: String,
    actual: String
    ): Boolean {
    
    val expectedBytes =
    expected.toByteArray(Charsets.UTF_8)
    
    val actualBytes =
    actual.toByteArray(Charsets.UTF_8)
    
    return MessageDigest.isEqual(
    expectedBytes,
    actualBytes
    )
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Active OTP
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * التحقق من وجود OTP صالح وقابل للاستخدام.
  
  * 
  
  * لا يعتبر OTP نشطًا إذا:
  
  * - انتهت صلاحيته.
  
  * - استنفد عدد المحاولات.
      */
      suspend fun hasActiveOTP(
      phone: String
      ): Boolean = withContext(Dispatchers.IO) {
    
    val normalizedPhone =
    normalizePhone(phone)
    ?: return@withContext false
    
    val now =
    System.currentTimeMillis()
    
    var cursor: Cursor? = null
    
    try {
    
     cursor = readableDb.rawQuery(
     """
     SELECT 1
     FROM $OTP_TABLE
     WHERE phone = ?
       AND expires_at > ?
       AND attempts < max_attempts
     LIMIT 1
     """.trimIndent(),
     arrayOf(
         normalizedPhone,
         now.toString()
     )
 )

 cursor.moveToFirst()
    
    } catch (e: Exception) {
    
     Log.e(
     TAG,
     "Failed to check active OTP: ${e.javaClass.simpleName}"
 )

 false
    
    } finally {
    cursor?.close()
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Cleanup expired OTPs
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * حذف OTP المنتهية أو التي استنفدت محاولاتها.
    */
    suspend fun cleanupExpiredOTPs() = withContext(Dispatchers.IO) {
    
    try {
    
     val now =
     System.currentTimeMillis()

 val deleted =
     writableDb.delete(
         OTP_TABLE,
         "expires_at <= ? OR attempts >= max_attempts",
         arrayOf(now.toString())
     )

 if (deleted > 0) {
     Log.d(
         TAG,
         "Cleaned up $deleted expired/exhausted OTP records"
     )
 }
    
    } catch (e: Exception) {
    
     Log.e(
     TAG,
     "Failed to cleanup OTP records: ${e.javaClass.simpleName}"
 )
    
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Compatibility alias
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * Alias للحفاظ على التوافق مع أي مستدعٍ قديم.
    */
    suspend fun cleanupExpired() =
    cleanupExpiredOTPs()
  
  // ═══════════════════════════════════════════════════════════════
  // Synchronization
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * مزامنة بيانات OTP.
  
  * 
  
  * لا توجد مزامنة خارجية فعلية هنا لأن OTP
  
  * بيانات محلية حساسة ومملوكة لقاعدة البيانات المحلية.
  
  * 
  
  * الوظيفة الحالية تقوم بالتنظيف والتحقق من سلامة
  
  * دورة حياة بيانات OTP فقط.
    */
    suspend fun syncData() = withContext(Dispatchers.IO) {
    
    try {
    
     cleanupExpiredOTPs()

 Log.d(
     TAG,
     "OTP data synced successfully"
 )
    
    } catch (e: Exception) {
    
     Log.e(
     TAG,
     "Failed to sync OTP data: ${e.javaClass.simpleName}",
     e
 )
    
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Security helpers
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * إخفاء جزء من رقم الهاتف عند التسجيل في Log.
  
  * 
  
  * يمنع ظهور رقم الهاتف كاملًا في سجل النظام.
    */
    private fun maskPhone(
    phone: String
    ): String {
    
    if (phone.length <= 4) {
    return "****"
    }
    
    val visibleDigits =
    minOf(3, phone.length)
    
    val prefix =
    phone.take(visibleDigits)
    
    return "$prefix****"
    }
    }