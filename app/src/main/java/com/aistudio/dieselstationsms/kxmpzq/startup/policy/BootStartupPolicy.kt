package com.aistudio.dieselstationsms.kxmpzq.startup.policy

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase
import com.aistudio.dieselstationsms.kxmpzq.startup.config.ConfigurationProvider

class BootStartupPolicy(override val phases: List<InitializationPhase>, private val config: ConfigurationProvider) : StartupPolicy {
    override val reason = StartupReason.BOOT
    override val allowParallelExecution = config.getParallelExecutionEnabled()
    override val continueOnFailure = config.getContinueOnFailure()
    override val healthCheckEnabled = true
    override val metricsEnabled = config.getMetricsEnabled()
    override val allowPhaseSkipping = config.getAllowPhaseSkipping()
    override val criticalPhaseFailureStopsPipeline = config.getCriticalPhaseFailureStopsPipeline()
    override val maxParallelPhases = config.getMaxParallelPhases()
    override val dagValidationEnabled = config.getDagValidationEnabled()
    override val dagAutoRecoveryEnabled = config.getDagAutoRecoveryEnabled()
}
