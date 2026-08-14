package com.aistudio.dieselstationsms.kxmpzq.startup.policy

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase

class BootStartupPolicy(override val phases: List<InitializationPhase>) : StartupPolicy {
    override val reason = StartupReason.BOOT
    override val allowParallelExecution = false
    override val continueOnFailure = false
    override val healthCheckEnabled = true
    override val metricsEnabled = true
}
