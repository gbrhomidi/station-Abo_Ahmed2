package com.aistudio.dieselstationsms.kxmpzq.settings.validation

sealed class SettingsValidationResult {
    object Valid : SettingsValidationResult()
    data class Invalid(val errors: List<String>) : SettingsValidationResult()
}
