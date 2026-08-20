package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.os.Build
import com.aistudio.dieselstationsms.kxmpzq.utils.SystemEventLogger

class StartupLoggerImpl(private val context: Context) : StartupLogger {

    override fun logBootReceived(reason: StartupReason, action: String?, correlationId: String) {
        SystemEventLogger.recordBoot(context, "RECEIVED", "$correlationId | Action=$action, Reason=$reason, SDK=${Build.VERSION.SDK_INT}")
    }

    override fun logPhaseStarted(phase: String, correlationId: String) {
        SystemEventLogger.recordBoot(context, "PHASE_STARTED", "$correlationId | $phase")
    }

    override fun logPhaseSuccess(phase: String, message: String?, correlationId: String) {
        SystemEventLogger.recordBoot(context, "PHASE_SUCCESS", "$correlationId | $phase: $message")
    }

    override fun logPhaseSkipped(phase: String, reason: String, correlationId: String) {
        SystemEventLogger.recordBoot(context, "PHASE_SKIPPED", "$correlationId | $phase: $reason")
    }

    override fun logPhaseFailed(phase: String, error: String, correlationId: String) {
        SystemEventLogger.recordError(context, "InitPipeline", "$correlationId | $phase: $error")
    }

    override fun logPipelineSuccess(durationMs: Long, phases: List<String>, correlationId: String) {
        SystemEventLogger.recordBoot(context, "PIPELINE_SUCCESS", "$correlationId | Duration: ${durationMs}ms, Phases: ${phases.joinToString()}")
    }

    override fun logPipelineFailed(phase: String, error: String, correlationId: String) {
        SystemEventLogger.recordError(context, "InitPipeline", "$correlationId | Failed at $phase: $error")
    }

    override fun logHealthCheck(status: String, details: String?, correlationId: String) {
        SystemEventLogger.recordBoot(context, "HEALTH_CHECK", "$correlationId | $status: $details")
    }

    override fun logStateChanged(oldState: StartupStateMachine.State, newState: StartupStateMachine.State, correlationId: String) {
        SystemEventLogger.recordBoot(context, "STATE_CHANGED", "$correlationId | $oldState → $newState")
    }

    override fun logCancelled(correlationId: String, reason: String) {
        SystemEventLogger.recordBoot(context, "CANCELLED", "$correlationId | $reason")
    }
}
