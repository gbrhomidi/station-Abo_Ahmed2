package com.aistudio.dieselstationsms.kxmpzq.startup.di

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.service.SMSServiceHeartbeatProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.ApplicationInitializationCoordinator
import com.aistudio.dieselstationsms.kxmpzq.startup.CancellationRegistry
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceLauncher
import com.aistudio.dieselstationsms.kxmpzq.startup.ServiceStatusRepository
import com.aistudio.dieselstationsms.kxmpzq.startup.SmsServiceLauncher
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupExecutionGuard
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupLoggerImpl
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupStateMachine
import com.aistudio.dieselstationsms.kxmpzq.startup.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.config.StaticConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.startup.event.CoroutineEventBus
import com.aistudio.dieselstationsms.kxmpzq.startup.event.EventBus
import com.aistudio.dieselstationsms.kxmpzq.startup.health.HealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.health.SmsServiceHealthMonitor
import com.aistudio.dieselstationsms.kxmpzq.startup.metrics.InMemoryMetricsCollector
import com.aistudio.dieselstationsms.kxmpzq.startup.metrics.MetricsCollector
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.EnvironmentCheckPhase
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.HealthCheckPhase
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.DelayPhase
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.PermissionCheckPhase
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.PhaseRegistry
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.ServiceLaunchPhase
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.ExponentialBackoffRetryPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.retry.RetryPolicy

/**
 * Composition root for the application startup subsystem.
 *
 * Responsible for constructing and wiring all startup-related
 * dependencies into ApplicationInitializationCoordinator.
 *
 * This class intentionally keeps dependency creation in one place
 * so that startup components remain independently testable.
 */
class StartupCompositionRoot(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Central startup configuration.
     */
    private val config: ConfigurationProvider =
        StaticConfigurationProvider

    /**
     * Startup event dispatcher.
     */
    private val eventBus: EventBus =
        CoroutineEventBus()

    /**
     * Startup state machine.
     */
    private val stateMachine: StartupStateMachine =
        StartupStateMachine()

    /**
     * In-memory startup metrics collector.
     */
    private val metricsCollector: MetricsCollector =
        InMemoryMetricsCollector()

    /**
     * Prevents concurrent startup execution when the selected
     * startup policy does not allow parallel execution.
     */
    private val executionGuard: StartupExecutionGuard =
        StartupExecutionGuard()

    /**
     * Tracks active startup executions and their cancellation tokens.
     */
    private val cancellationRegistry: CancellationRegistry =
        CancellationRegistry()

    /**
     * Stores and validates the persistent SMS service status.
     */
    private val statusRepository: ServiceStatusRepository =
        ServiceStatusRepository(appContext)

    /**
     * Registry containing all startup initialization phases.
     */
    private val phaseRegistry: PhaseRegistry =
        createPhaseRegistry()

    /**
     * Creates and registers every initialization phase used by
     * StartupPolicyFactory.
     */
    private fun createPhaseRegistry(): PhaseRegistry {
        return PhaseRegistry().apply {
            register(EnvironmentCheckPhase())
            register(DelayPhase())
            register(PermissionCheckPhase())
            register(ServiceLaunchPhase())
            register(HealthCheckPhase())
        }
    }

    /**
     * Creates the fully wired startup coordinator.
     */
    fun createCoordinator(): ApplicationInitializationCoordinator {
        return ApplicationInitializationCoordinator(
            config = config,
            eventBus = eventBus,
            stateMachine = stateMachine,
            metricsCollector = metricsCollector,
            executionGuard = executionGuard,
            cancellationRegistry = cancellationRegistry,
            phaseRegistry = phaseRegistry,
            loggerFactory = { ctx ->
                StartupLoggerImpl(ctx.applicationContext)
            },
            launcherFactory = { ctx ->
                createServiceLauncher(ctx)
            },
            healthMonitorFactory = {
                createHealthMonitor()
            },
            retryPolicyFactory = {
                createRetryPolicy()
            }
        )
    }

    /**
     * Creates the SMS service launcher.
     *
     * ServiceStatusRepository is shared by the composition root
     * so startup decisions and service state tracking use the same
     * persistent status source.
     */
    private fun createServiceLauncher(context: Context): ServiceLauncher {
        return SmsServiceLauncher(
            context = context.applicationContext,
            statusRepository = statusRepository
        )
    }

    /**
     * Creates the SMS service health monitor.
     */
    private fun createHealthMonitor(): HealthMonitor {
        return SmsServiceHealthMonitor(
            checkIntervalMs = config.getHealthCheckIntervalMs(),
            heartbeatProvider = SMSServiceHeartbeatProvider
        )
    }

    /**
     * Creates the retry policy from the centralized configuration.
     */
    private fun createRetryPolicy(): RetryPolicy {
        return ExponentialBackoffRetryPolicy(
            maxAttempts = config.getMaxRetryAttempts(),
            backoffMs = config.getRetryBackoffMs()
        )
    }

    companion object {

        /**
         * Convenience factory for callers that only have an Android Context.
         */
        fun createCoordinator(
            context: Context
        ): ApplicationInitializationCoordinator {
            return StartupCompositionRoot(context).createCoordinator()
        }
    }
}