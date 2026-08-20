package com.aistudio.dieselstationsms.kxmpzq.startup

sealed class StartupEvent {
    data class PipelineStarted(val reason: StartupReason, val correlationId: String) : StartupEvent()
    data class PhaseStarted(val phase: String, val correlationId: String) : StartupEvent()
    data class PhaseCompleted(val phase: String, val result: String, val correlationId: String) : StartupEvent()
    data class PhaseFailed(val phase: String, val error: String, val correlationId: String) : StartupEvent()
    data class PhaseSkipped(val phase: String, val reason: String, val correlationId: String) : StartupEvent()
    data class PipelineCompleted(val durationMs: Long, val phases: List<String>, val correlationId: String) : StartupEvent()
    data class PipelineFailed(val failedPhase: String, val error: String, val correlationId: String) : StartupEvent()
    data class StateChanged(val oldState: StartupStateMachine.State, val newState: StartupStateMachine.State, val correlationId: String) : StartupEvent()
    data class Cancelled(val correlationId: String, val reason: String) : StartupEvent()
}
