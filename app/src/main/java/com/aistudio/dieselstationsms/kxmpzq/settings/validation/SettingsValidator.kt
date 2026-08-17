package com.aistudio.dieselstationsms.kxmpzq.settings.validation

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

class SettingsValidator {
    fun validate(settings: ApplicationSettings): SettingsValidationResult {
        val errors = buildList {
            if (settings.stationName.isBlank()) add("اسم المحطة مطلوب")
            if (settings.language.isBlank()) add("لغة التطبيق مطلوبة")
            if (settings.smsMaxParts !in 1..20) add("عدد أجزاء SMS يجب أن يكون بين 1 و20")
            if (settings.smsCostPerPart < 0.0) add("تكلفة جزء SMS لا يمكن أن تكون سالبة")
            if (settings.keepLogsDays !in 1..3650) add("مدة الاحتفاظ بالسجلات يجب أن تكون بين يوم و3650 يوماً")
            if (settings.backupRetentionCount !in 1..100) add("عدد النسخ الاحتياطية يجب أن يكون بين 1 و100")
        }
        return if (errors.isEmpty()) SettingsValidationResult.Valid
        else SettingsValidationResult.Invalid(errors)
    }
}
