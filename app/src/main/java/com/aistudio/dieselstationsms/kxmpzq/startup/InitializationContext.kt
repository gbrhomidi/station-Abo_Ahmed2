package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.startup.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy

data class InitializationContext(
    val appContext: Context,
    val startupReason: StartupReason,
    val logger: StartupLogger,
    val serviceLauncher: ServiceLauncher,
    val config: ConfigurationProvider,
    val healthMonitor: HealthMonitor,
    val retryPolicy: RetryPolicy,
    val correlationId: String,
    val cancellationToken: CancellationToken
)
