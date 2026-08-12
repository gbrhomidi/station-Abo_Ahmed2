package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.startup.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy

/**
 * ═══════════════════════════════════════════════════════════════
 * سياق تهيئة التطبيق - InitializationContext
 * ═══════════════════════════════════════════════════════════════
 *
 * هذا الكائن يمثل السياق المشترك الذي يتم تمريره إلى جميع
 * InitializationPhase أثناء تنفيذ Startup Pipeline.
 *
 * يحتوي على:
 * 1. Context آمن على مستوى التطبيق.
 * 2. سبب بدء عملية التهيئة.
 * 3. نظام تسجيل الأحداث.
 * 4. مشغل خدمة SMS.
 * 5. مزود إعدادات التطبيق.
 * 6. مراقب صحة النظام.
 * 7. سياسة إعادة المحاولة.
 * 8. معرف ارتباط العملية Correlation ID.
 * 9. رمز إلغاء العملية.
 *
 * ملاحظة:
 * لا يحتوي هذا الكلاس على منطق تنفيذ للمراحل؛
 * وظيفته توفير Dependencies مشتركة للـ Pipeline.
 */
data class InitializationContext(

    /**
     * Application Context المستخدم أثناء عملية التهيئة.
     *
     * يجب أن يكون Context على مستوى التطبيق لتجنب الاحتفاظ
     * بـ Activity/Service Context لفترة أطول من اللازم.
     */
    val appContext: Context,

    /**
     * السبب الذي أدى إلى بدء عملية Startup.
     */
    val startupReason: StartupReason,

    /**
     * نظام تسجيل أحداث ومراحل Startup.
     */
    val logger: StartupLogger,

    /**
     * المسؤول عن تشغيل SMSService.
     */
    val serviceLauncher: ServiceLauncher,

    /**
     * مصدر إعدادات Startup والتطبيق.
     */
    val config: ConfigurationProvider,

    /**
     * مراقب صحة الخدمات والمكونات المطلوبة.
     */
    val healthMonitor: HealthMonitor,

    /**
     * سياسة إعادة المحاولة الخاصة بمراحل Startup.
     */
    val retryPolicy: RetryPolicy,

    /**
     * معرف فريد للعملية الحالية.
     *
     * يستخدم لربط جميع السجلات والأحداث والنتائج
     * بنفس عملية Startup.
     */
    val correlationId: String,

    /**
     * رمز الإلغاء الخاص بعملية Startup الحالية.
     *
     * يسمح بإيقاف التنفيذ بأمان عند طلب الإلغاء.
     */
    val cancellationToken: CancellationToken
)