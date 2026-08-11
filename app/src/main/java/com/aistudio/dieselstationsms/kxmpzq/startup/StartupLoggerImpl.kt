package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.os.Build
import com.aistudio.dieselstationsms.kxmpzq.utils.SystemEventLogger

/**
 * ═══════════════════════════════════════════════════════════════
 * التنفيذ الفعلي لـ StartupLogger
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 * - تسجيل أحداث دورة بدء تشغيل التطبيق.
 * - ربط جميع الأحداث بـ correlationId.
 * - تمرير الأحداث إلى SystemEventLogger.
 *
 * ملاحظات:
 * 1. هذا الصنف لا ينفذ أي منطق Startup.
 * 2. لا يقوم بتشغيل أو إيقاف الخدمات.
 * 3. لا يعدّل حالة State Machine.
 * 4. لا يتعامل مع قاعدة البيانات مباشرة.
 * 5. لا يحتوي على بيانات تجريبية أو افتراضية خاصة بالنظام.
 */
class StartupLoggerImpl(
    private val context: Context
) : StartupLogger {

    companion object {
        private const val COMPONENT = "InitPipeline"
        private const val UNKNOWN = "Unknown"

        private fun safe(value: String?): String {
            return value?.takeIf { it.isNotBlank() } ?: UNKNOWN
        }

        private fun formatCorrelationId(correlationId: String): String {
            return safe(correlationId)
        }
    }

    /**
     * تسجيل استقبال حدث بدء التشغيل.
     */
    override fun logBootReceived(
        reason: StartupReason,
        action: String?,
        correlationId: String
    ) {
        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | Reason=")
            append(reason.name)
            append(" | Action=")
            append(safe(action))
            append(" | SDK=")
            append(Build.VERSION.SDK_INT)
        }

        SystemEventLogger.recordBoot(
            context,
            "RECEIVED",
            details
        )
    }

    /**
     * تسجيل بداية تنفيذ مرحلة.
     */
    override fun logPhaseStarted(
        phase: String,
        correlationId: String
    ) {
        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | Phase=")
            append(safe(phase))
        }

        SystemEventLogger.recordBoot(
            context,
            "PHASE_STARTED",
            details
        )
    }

    /**
     * تسجيل نجاح مرحلة.
     */
    override fun logPhaseSuccess(
        phase: String,
        message: String?,
        correlationId: String
    ) {
        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | Phase=")
            append(safe(phase))
            append(" | Message=")
            append(safe(message))
        }

        SystemEventLogger.recordBoot(
            context,
            "PHASE_SUCCESS",
            details
        )
    }

    /**
     * تسجيل تخطي مرحلة.
     */
    override fun logPhaseSkipped(
        phase: String,
        reason: String,
        correlationId: String
    ) {
        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | Phase=")
            append(safe(phase))
            append(" | Reason=")
            append(safe(reason))
        }

        SystemEventLogger.recordBoot(
            context,
            "PHASE_SKIPPED",
            details
        )
    }

    /**
     * تسجيل فشل مرحلة.
     */
    override fun logPhaseFailed(
        phase: String,
        error: String,
        correlationId: String
    ) {
        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | Phase=")
            append(safe(phase))
            append(" | Error=")
            append(safe(error))
        }

        SystemEventLogger.recordError(
            context,
            COMPONENT,
            details
        )
    }

    /**
     * تسجيل نجاح Pipeline بالكامل.
     */
    override fun logPipelineSuccess(
        durationMs: Long,
        phases: List<String>,
        correlationId: String
    ) {
        val normalizedDuration = durationMs.coerceAtLeast(0L)

        val phaseSummary = if (phases.isEmpty()) {
            "None"
        } else {
            phases
                .map { safe(it) }
                .joinToString(", ")
        }

        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | DurationMs=")
            append(normalizedDuration)
            append(" | Phases=")
            append(phaseSummary)
        }

        SystemEventLogger.recordBoot(
            context,
            "PIPELINE_SUCCESS",
            details
        )
    }

    /**
     * تسجيل فشل Pipeline.
     */
    override fun logPipelineFailed(
        phase: String,
        error: String,
        correlationId: String
    ) {
        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | FailedAt=")
            append(safe(phase))
            append(" | Error=")
            append(safe(error))
        }

        SystemEventLogger.recordError(
            context,
            COMPONENT,
            details
        )
    }

    /**
     * تسجيل نتيجة Health Check.
     */
    override fun logHealthCheck(
        status: String,
        details: String?,
        correlationId: String
    ) {
        val eventDetails = buildString {
            append(formatCorrelationId(correlationId))
            append(" | Status=")
            append(safe(status))
            append(" | Details=")
            append(safe(details))
        }

        SystemEventLogger.recordBoot(
            context,
            "HEALTH_CHECK",
            eventDetails
        )
    }

    /**
     * تسجيل انتقال State Machine.
     */
    override fun logStateChanged(
        oldState: StartupStateMachine.State,
        newState: StartupStateMachine.State,
        correlationId: String
    ) {
        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | From=")
            append(oldState.name)
            append(" | To=")
            append(newState.name)
        }

        SystemEventLogger.recordBoot(
            context,
            "STATE_CHANGED",
            details
        )
    }

    /**
     * تسجيل إلغاء دورة Startup.
     */
    override fun logCancelled(
        correlationId: String,
        reason: String
    ) {
        val details = buildString {
            append(formatCorrelationId(correlationId))
            append(" | Reason=")
            append(safe(reason))
        }

        SystemEventLogger.recordBoot(
            context,
            "CANCELLED",
            details
        )
    }
}