package com.aistudio.dieselstationsms.kxmpzq.startup.policy

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase

class ManualStartupPolicy(override val phases: List<InitializationPhase>) : StartupPolicy {
    override val reason = StartupReason.MANUAL
    override val allowParallelExecution = true
    override val continueOnFailure = true
    override val healthCheckEnabled = false
    override val metricsEnabled = true
}
