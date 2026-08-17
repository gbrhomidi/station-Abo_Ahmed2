package com.aistudio.dieselstationsms.kxmpzq.settings.reset

interface SettingsResetManager {
    suspend fun resetApplicationSettings()
    suspend fun resetDatabaseSettings()
    suspend fun resetAll()
}
