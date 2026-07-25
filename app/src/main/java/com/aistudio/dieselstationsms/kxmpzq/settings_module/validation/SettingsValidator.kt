package com.aistudio.dieselstationsms.kxmpzq.settings.validation

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

/**
 * مدقق الإعدادات — يتحقق من صحة القيم قبل الحفظ
 */
class SettingsValidator {

    fun validate(settings: ApplicationSettings): SettingsValidationResult {
        val errors = mutableListOf<String>()

        if (settings.bootDelayMs < 0) {
            errors.add("تأخير الإقلاع لا يمكن أن يكون سالباً")
        }
        if (settings.bootDelayMs > 300_000) {
            errors.add("تأخير الإقلاع لا يمكن أن يتجاوز 5 دقائق")
        }
        if (settings.pipelineTimeoutMs < 1_000) {
            errors.add("مهلة Pipeline يجب أن تكون أكبر من ثانية")
        }
        if (settings.phaseTimeoutMs < 1_000) {
            errors.add("مهلة المرحلة يجب أن تكون أكبر من ثانية")
        }
        if (settings.maxRetryAttempts < 0 || settings.maxRetryAttempts > 10) {
            errors.add("عدد المحاولات يجب أن يكون بين 0 و 10")
        }
        if (settings.retryBackoffMs < 0) {
            errors.add("قيمة Backoff غير صحيحة")
        }
        if (settings.healthCheckIntervalMs < 1_000) {
            errors.add("فترة Health Check صغيرة جداً")
        }
        if (settings.heartbeatTimeoutMs < 5_000) {
            errors.add("مهلة Heartbeat صغيرة جداً")
        }
        if (settings.smsProcessingIntervalMs < 100) {
            errors.add("فترة معالجة SMS غير صالحة")
        }
        if (settings.smsQueueSize < 10 || settings.smsQueueSize > 10_000) {
            errors.add("حجم Queue يجب أن يكون بين 10 و 10000")
        }
        if (settings.keepLogsDays < 1 || settings.keepLogsDays > 365) {
            errors.add("مدة الاحتفاظ بالسجلات يجب أن تكون بين 1 و 365 يوم")
        }
        if (settings.maxLogSizeMb < 1 || settings.maxLogSizeMb > 500) {
            errors.add("حجم السجل يجب أن يكون بين 1 و 500 ميجابايت")
        }
        if (settings.backupIntervalHours < 1 || settings.backupIntervalHours > 168) {
            errors.add("فترة النسخ يجب أن تكون بين 1 و 168 ساعة")
        }
        if (settings.autoLogoutMinutes < 1 || settings.autoLogoutMinutes > 120) {
            errors.add("مدة الخروج التلقائي يجب أن تكون بين 1 و 120 دقيقة")
        }
        if (settings.cleanupIntervalDays < 1 || settings.cleanupIntervalDays > 90) {
            errors.add("فترة التنظيف يجب أن تكون بين 1 و 90 يوم")
        }
        if (settings.metricsRetentionDays < 1 || settings.metricsRetentionDays > 365) {
            errors.add("مدة الاحتفاظ بالمقاييس يجب أن تكون بين 1 و 365 يوم")
        }
        if (settings.eventBufferSize < 1 || settings.eventBufferSize > 1024) {
            errors.add("حجم Buffer يجب أن يكون بين 1 و 1024")
        }
        if (settings.maxParallelPhases < 1 || settings.maxParallelPhases > 10) {
            errors.add("عدد المراحل المتوازية يجب أن يكون بين 1 و 10")
        }
        if (settings.maxHealthFailures < 1 || settings.maxHealthFailures > 10) {
            errors.add("عدد الفشل الأقصى يجب أن يكون بين 1 و 10")
        }

        return if (errors.isEmpty()) {
            SettingsValidationResult.Valid
        } else {
            SettingsValidationResult.Invalid(errors)
        }
    }
}
