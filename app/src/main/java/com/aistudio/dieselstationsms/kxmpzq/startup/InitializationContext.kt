package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.startup.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy

/**
 * ═══════════════════════════════════════════════════════════════
 * سياق التهيئة - InitializationContext
 * ═══════════════════════════════════════════════════════════════
 *
 * التحديثات:
 * 1. ✅ إضافة DatabaseHelper (لتهيئة الجداول والوصول للبيانات)
 * 2. ✅ دعم SchemaInitializationPhase
 */
data class InitializationContext(
    val appContext: Context,
    val startupReason: StartupReason,
    val logger: StartupLogger,
    val serviceLauncher: ServiceLauncher,
    val config: ConfigurationProvider,
    val healthMonitor: HealthMonitor,
    val retryPolicy: RetryPolicy,
    val correlationId: String,
    val cancellationToken: CancellationToken,
    val databaseHelper: DatabaseHelper  // ✅ جديد
)