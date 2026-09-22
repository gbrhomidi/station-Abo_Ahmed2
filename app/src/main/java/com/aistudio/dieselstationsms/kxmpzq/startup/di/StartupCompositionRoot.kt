package com.aistudio.dieselstationsms.kxmpzq.startup.di

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.startup.*
import com.aistudio.dieselstationsms.kxmpzq.startup.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.config.StaticConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.event.CoroutineEventBus
import com.aistudio.dieselstationsms.kxmpzq.startup.event.EventBus
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.health.SmsServiceHealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.metrics.InMemoryMetricsCollector
import com.aistudio.dieselstationsms.kxmpzq.startup.metrics.MetricsCollector
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.*
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.ExponentialBackoffRetryPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy
import com.aistudio.dieselstationsms.kxmpzq.service.SMSServiceHeartbeatProvider

class StartupCompositionRoot(private val context: Context) {

    private val config: ConfigurationProvider = StaticConfigurationProvider
    private val eventBus: EventBus = CoroutineEventBus()
    private val stateMachine: StartupStateMachine = StartupStateMachine()
    private val metricsCollector: MetricsCollector = InMemoryMetricsCollector()
    private val executionGuard: StartupExecutionGuard = StartupExecutionGuard()
    private val cancellationRegistry: CancellationRegistry = CancellationRegistry()
    private val statusRepository: ServiceStatusRepository = ServiceStatusRepository(context)
    private val phaseRegistry: PhaseRegistry = createPhaseRegistry()

    private fun createPhaseRegistry(): PhaseRegistry {
        return PhaseRegistry().apply {
            register(EnvironmentCheckPhase())
            register(DelayPhase())
            register(PermissionCheckPhase())
            register(ServiceLaunchPhase())
            register(HealthCheckPhase())
        }
    }

    fun createCoordinator(): ApplicationInitializationCoordinator {
        return ApplicationInitializationCoordinator(
            config = config,
            eventBus = eventBus,
            stateMachine = stateMachine,
            metricsCollector = metricsCollector,
            executionGuard = executionGuard,
            cancellationRegistry = cancellationRegistry,
            phaseRegistry = phaseRegistry,
            loggerFactory = { ctx -> StartupLoggerImpl(ctx) },
            launcherFactory = { ctx -> SmsServiceLauncher(ctx, statusRepository) },
            healthMonitorFactory = { createHealthMonitor() },
            retryPolicyFactory = { createRetryPolicy() }
        )
    }

    private fun createHealthMonitor(): HealthMonitor {
        return SmsServiceHealthMonitor(
            checkIntervalMs = config.getHealthCheckIntervalMs(),
            heartbeatProvider = SMSServiceHeartbeatProvider
        )
    }

    private fun createRetryPolicy(): RetryPolicy {
        return ExponentialBackoffRetryPolicy(
            maxAttempts = config.getMaxRetryAttempts(),
            backoffMs = config.getRetryBackoffMs()
        )
    }

    companion object {
        fun createCoordinator(context: Context): ApplicationInitializationCoordinator {
            return StartupCompositionRoot(context).createCoordinator()
        }
    }
}
