package com.aistudio.dieselstationsms.kxmpzq.settings.reset

/**
 * واجهة إعادة ضبط الإعدادات
 */
interface SettingsResetManager {
    suspend fun resetApplicationSettings()
    suspend fun resetDatabaseSettings()
    suspend fun resetAll()
}
