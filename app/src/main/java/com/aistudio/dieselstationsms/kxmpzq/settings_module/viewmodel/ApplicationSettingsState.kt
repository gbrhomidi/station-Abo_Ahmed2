package com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

/**
 * حالة شاشة إعدادات التطبيق
 */
data class ApplicationSettingsState(
    val settings: ApplicationSettings = ApplicationSettings(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val errorMessage: String? = null
)
