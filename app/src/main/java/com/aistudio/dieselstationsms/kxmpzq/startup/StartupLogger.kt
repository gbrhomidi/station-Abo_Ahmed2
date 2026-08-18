package com.aistudio.dieselstationsms.kxmpzq.startup

interface StartupLogger {
    fun logBootReceived(reason: StartupReason, action: String?, correlationId: String)
    fun logPhaseStarted(phase: String, correlationId: String)
    fun logPhaseSuccess(phase: String, message: String?, correlationId: String)
    fun logPhaseSkipped(phase: String, reason: String, correlationId: String)
    fun logPhaseFailed(phase: String, error: String, correlationId: String)
    fun logPipelineSuccess(durationMs: Long, phases: List<String>, correlationId: String)
    fun logPipelineFailed(phase: String, error: String, correlationId: String)
    fun logHealthCheck(status: String, details: String?, correlationId: String)
    fun logStateChanged(oldState: StartupStateMachine.State, newState: StartupStateMachine.State, correlationId: String)
    fun logCancelled(correlationId: String, reason: String)
}
