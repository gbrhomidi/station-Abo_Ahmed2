package com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

sealed class ApplicationSettingsEvent {
    data object Save : ApplicationSettingsEvent()
    data object Reset : ApplicationSettingsEvent()
    data object ClearMessage : ApplicationSettingsEvent()
    data class Update(val settings: ApplicationSettings) : ApplicationSettingsEvent()
}
