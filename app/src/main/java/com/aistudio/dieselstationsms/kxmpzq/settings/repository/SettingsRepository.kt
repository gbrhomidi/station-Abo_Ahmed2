package com.aistudio.dieselstationsms.kxmpzq.settings.repository

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeSettings(): Flow<ApplicationSettings>
    suspend fun getSettings(): ApplicationSettings
    suspend fun saveSettings(settings: ApplicationSettings)
    suspend fun resetSettings()
}
