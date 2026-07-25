package com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

/**
 * أحداث شاشة إعدادات التطبيق
 */
sealed class ApplicationSettingsEvent {
    data class UpdateSettings(val settings: ApplicationSettings) : ApplicationSettingsEvent()
    object Save : ApplicationSettingsEvent()
    object Reset : ApplicationSettingsEvent()
    object ClearMessage : ApplicationSettingsEvent()
}
