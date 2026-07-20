# ═══════════════════════════════════════════════════════════════
#  محطة أبو أحمد - قواعد ProGuard/R8 (مُحسّنة وآمنة)
# ═══════════════════════════════════════════════════════════════
#
#  ⚠️ تحذير: هذا الملف يستخدم مع isMinifyEnabled=true
#  لا تستخدم قواعد عامة جداً (مثل ** { *; }) لأنها تبطل فائدة التعتيم
#
#  التحسينات:
#  1. قواعد أكثر تحديداً وصرامة
#  2. حماية الفئات الحساسة فقط
#  3. إضافة قواعد Compose
#  4. إضافة قواعد Biometric
#  5. إضافة قواعد EncryptedSharedPreferences
#  6. إزالة القواعد العامة الخطيرة
#  7. إضافة تعليقات توثيقية
#  8. ❌ إزالة قواعد NanoHTTPD لأن المكتبة لم تعد مستخدمة
#     (تم تعطيل الخادم المحلي نهائياً في المعمارية الجديدة)
# ═══════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════
#  القواعد العامة - General Rules
# ═══════════════════════════════════════════════════════════════

# الحفاظ على معلومات السطر (لتتبع الأخطاء)
-keepattributes SourceFile,LineNumberTable
# إعادة تسمية SourceFile إلى "SourceFile" لحماية المعلومات
-renamesourcefileattribute SourceFile

# الحفاظ على التعليقات التوضيحية
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ═══════════════════════════════════════════════════════════════
#  فئات التطبيق الأساسية - Application Classes
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات البيانات (Data Classes) المستخدمة في الـ Reflection
-keep class com.aistudio.dieselstationsms.kxmpzq.data.model.** {
    <fields>;
    <init>(...);
}

# الحفاظ على فئات الـ API Responses
-keep class com.aistudio.dieselstationsms.kxmpzq.data.api.response.** {
    <fields>;
    <init>(...);
}

# الحفاظ على فئات الـ Database Entities
-keep class com.aistudio.dieselstationsms.kxmpzq.data.db.entity.** {
    <fields>;
    <init>(...);
}

# ═══════════════════════════════════════════════════════════════
#  Moshi - JSON Serialization
# ═══════════════════════════════════════════════════════════════

# الحفاظ على محولات Moshi
-keep class * extends com.squareup.moshi.JsonAdapter {
    <init>(...);
    <methods>;
}

# الحفاظ على التعليقات التوضيحية للـ JSON
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# الحفاظ على فئات Moshi نفسها
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# ═══════════════════════════════════════════════════════════════
#  Retrofit - Networking
# ═══════════════════════════════════════════════════════════════

# الحفاظ على واجهات Retrofit
-keep interface com.aistudio.dieselstationsms.kxmpzq.data.api.** { *; }

# الحفاظ على التعليقات التوضيحية للـ HTTP
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}

# الحفاظ على فئات Retrofit
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# ═══════════════════════════════════════════════════════════════
#  Room - Database
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات Room Database
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
    <methods>;
}

# الحفاظ على الـ DAOs
-keep class com.aistudio.dieselstationsms.kxmpzq.data.db.dao.** {
    <methods>;
}

# الحفاظ على الـ TypeConverters
-keep class com.aistudio.dieselstationsms.kxmpzq.data.db.converter.** { *; }

# Room uses RuntimeExceptions
-dontwarn androidx.room.paging.**

# ═══════════════════════════════════════════════════════════════
#  Compose - UI Framework
# ═══════════════════════════════════════════════════════════════

# الحفاظ على دوال @Composable
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# الحفاظ على preview functions
-keepclassmembers class * {
    @androidx.compose.ui.tooling.preview.Preview <methods>;
}

# الحفاظ على فئات Compose الرئيسية
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ═══════════════════════════════════════════════════════════════
#  Biometric - Fingerprint/Face ID
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات Biometric
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# الحفاظ على فئات BiometricPrompt
-keepclassmembers class * {
    @androidx.biometric.BiometricPrompt.AuthenticationCallback <methods>;
}

# ═══════════════════════════════════════════════════════════════
#  Security - EncryptedSharedPreferences
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات AndroidX Security
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# الحفاظ على Tink (مستخدم في Security Crypto)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ═══════════════════════════════════════════════════════════════
#  ❌ تم إزالة قواعد NanoHTTPD - لم تعد المكتبة مستخدمة
#     (تم تعطيل الخادم المحلي نهائياً في المعمارية الجديدة)
# ═══════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════
#  OkHttp - HTTP Client
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ═══════════════════════════════════════════════════════════════
#  Kotlin Coroutines
# ═══════════════════════════════════════════════════════════════

# الحفاظ على Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ═══════════════════════════════════════════════════════════════
#  WorkManager - Background Tasks
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات Worker
-keep class * extends androidx.work.Worker {
    <init>(...);
}

-keep class * extends androidx.work.CoroutineWorker {
    <init>(...);
}

# ═══════════════════════════════════════════════════════════════
#  Navigation Component
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات Navigation
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# ═══════════════════════════════════════════════════════════════
#  AndroidX Core & Lifecycle
# ═══════════════════════════════════════════════════════════════

# الحفاظ على ViewModel
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
    <methods>;
}

# الحفاظ على LiveData
-keep class androidx.lifecycle.LiveData { *; }
-keep class androidx.lifecycle.MutableLiveData { *; }

# الحفاظ على StateFlow
-keep class kotlinx.coroutines.flow.StateFlow { *; }

# ═══════════════════════════════════════════════════════════════
#  WebView - JavaScript Interface
# ═══════════════════════════════════════════════════════════════

# الحفاظ على JavaScript Interface
-keepclassmembers class com.aistudio.dieselstationsms.kxmpzq.MainActivity$WebAppInterface {
    <methods>;
}

# ═══════════════════════════════════════════════════════════════
#  قواعد إزالة التحذيرات - Suppress Warnings
# ═══════════════════════════════════════════════════════════════

# إزالة التحذيرات المعروفة والآمنة
-dontwarn android.**
-dontwarn com.google.**
-dontwarn org.jetbrains.**
-dontwarn sun.misc.**

# ═══════════════════════════════════════════════════════════════
#  تحسين الأداء - Optimization
# ═══════════════════════════════════════════════════════════════

# إزالة التعليقات
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNullExpressionValue(...);
    static void checkReturnedValueIsNotNull(...);
    static void checkFieldIsNotNull(...);
}

# ═══════════════════════════════════════════════════════════════
#  نهاية الملف
# ═══════════════════════════════════════════════════════════════
