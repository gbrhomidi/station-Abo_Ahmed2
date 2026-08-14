package com.aistudio.dieselstationsms.kxmpzq.startup

import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase

interface StartupPolicy {
    val reason: StartupReason
    val allowParallelExecution: Boolean
    val continueOnFailure: Boolean
    val healthCheckEnabled: Boolean
    val metricsEnabled: Boolean
    val phases: List<InitializationPhase>
}
