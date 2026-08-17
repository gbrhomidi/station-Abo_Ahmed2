package com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

data class ApplicationSettingsState(
    val settings: ApplicationSettings = ApplicationSettings(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedSuccessfully: Boolean = false
)
