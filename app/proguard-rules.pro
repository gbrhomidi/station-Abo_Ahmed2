# ═══════════════════════════════════════════════════════════════
#  محطة أبو أحمد - قواعد ProGuard (إصدار آمن ومُحكم)
#  آخر تحديث: 2026-07-01
# ═══════════════════════════════════════════════════════════════

# ─── نقطة الدخول الرئيسية ───
-keep public class com.aistudio.dieselstationsms.kxmpzq.MyApplication {
    public <init>();
}

# ─── المكونات الأساسية (أنشطة، خدمات، مستقبلات، مزودات) ───
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgent
-keep public class * extends android.preference.Preference

# ─── النشاط الرئيسي (MainActivity) ───
-keep class com.aistudio.dieselstationsms.kxmpzq.MainActivity {
    public <init>();
    public void onCreate(android.os.Bundle);
}

# ─── واجهة JavaScript لـ WebView ───
-keepclassmembers class com.aistudio.dieselstationsms.kxmpzq.MainActivity$WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# ─── الحفاظ على حزم البيانات والنماذج الخاصة بالتطبيق (للتسلسل وعمليات JSON) ───
-keep class com.aistudio.dieselstationsms.kxmpzq.data.** { *; }
-keep class com.aistudio.dieselstationsms.kxmpzq.model.** { *; }

# ─── Moshi - Serialization (مع كود gen) ───
# نحتفظ فقط بالتعليقات التوضيحية الضرورية، لا بكل مكتبة Moshi
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>(...);
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.Json <methods>;
}
-keepnames @com.squareup.moshi.JsonClass class *
# نحذف السطر القديم الذي كان يحتفظ بكل محتوى Moshi:
# -keep class com.squareup.moshi.** { *; }  ← تمت إزالته لمنع الهندسة العكسية

# ─── Retrofit / OkHttp (ضروري للشبكة) ───
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions, *Annotation*
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.Interceptor { *; }
-keep class okhttp3.logging.HttpLoggingInterceptor { *; }
-dontwarn okhttp3.internal.**

# ─── Room - قاعدة البيانات ───
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { @androidx.room.PrimaryKey <fields>; }
-keepclassmembers @androidx.room.Entity class * {
    <init>(...);
}
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-dontwarn androidx.room.paging.**

# ─── NanoHTTPD (خادم محلي) ───
-keep class fi.iki.elonen.NanoHTTPD { *; }
-keep class fi.iki.elonen.NanoHTTPD$* { *; }
-dontwarn fi.iki.elonen.**

# ─── WorkManager ───
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ─── Biometric (توسيع القاعدة لتغطية الواجهة الجديدة) ───
-keep class androidx.biometric.** { *; }

# ─── Compose (قواعد أساسية) ───
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class * {
    @androidx.compose.ui.tooling.preview.Preview <methods>;
}
-dontwarn androidx.compose.**

# ─── Kotlin Coroutines ───
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ─── Kotlin Serialization ───
-keepclassmembers class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

# ─── AndroidX Core ───
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.core.app.NotificationCompat { *; }

# ─── إزالة جميع السجلات في الإصدار النهائي ───
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# ─── إزالة Log الخاص بالتطبيق ───
-assumenosideeffects class com.aistudio.dieselstationsms.kxmpzq.** {
    void log*(...);
    void debug*(...);
}

# ─── تحسينات الأداء والتشويش ───
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively

# ─── الحفاظ على معلومات الأخطاء (لتتبع الأعطال) ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ─── كتم التحذيرات غير المهمة ───
-dontnote
-dontwarn android.support.**
-dontwarn androidx.**

# ─── حماية من الهندسة العكسية ───
-repackageclasses 'a'
-flattenpackagehierarchy
-allowaccessmodification

# ─── إجراءات أمان إضافية ───
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify

# ─── الحفاظ على أسماء الفئات الرئيسية (للعمل مع النظام) ───
-keepnames class com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
-keepclassmembers class com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper {
    public <init>(android.content.Context);
}

-keepnames class com.aistudio.dieselstationsms.kxmpzq.SMSService
-keepnames class com.aistudio.dieselstationsms.kxmpzq.SmsReceiver
-keepnames class com.aistudio.dieselstationsms.kxmpzq.BackupWorker

# ─── JSON (للاستخدام مع SQLite) ───
-keepclassmembers class org.json.JSONObject {
    <init>(...);
    *** get*(...);
    *** opt*(...);
    *** put*(...);
}
-keepclassmembers class org.json.JSONArray {
    <init>(...);
    *** get*(...);
    *** opt*(...);
    *** put*(...);
}

# ─── حماية الـ Reflection للمستمعين (Listeners) ───
-keepclassmembers class * {
    *** *Callback;
    *** *Listener;
}

# ─── تجاهل التحذيرات من مكتبات التشفير الداخلية ───
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ─── قواعد Android الرسمية لـ WebView ───
-keep public class android.net.http.SslError
-keep public class android.webkit.WebViewClient

# ─── نهاية الملف ───
