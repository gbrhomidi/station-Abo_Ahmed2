package com.aistudio.dieselstationsms.kxmpzq.startup

import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.InitializationPhase

interface StartupPolicy {
    val reason: StartupReason
    val allowParallelExecution: Boolean
    val continueOnFailure: Boolean
    val healthCheckEnabled: Boolean
    val metricsEnabled: Boolean
    val allowPhaseSkipping: Boolean get() = false
    val criticalPhaseFailureStopsPipeline: Boolean get() = true
    val maxParallelPhases: Int get() = 1
    val dagValidationEnabled: Boolean get() = true
    val dagAutoRecoveryEnabled: Boolean get() = true
    val phases: List<InitializationPhase>
}
