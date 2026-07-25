package com.aistudio.dieselstationsms.kxmpzq.settings.reset

import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository

/**
 * تنفيذ إعادة الضبط
 */
class SettingsResetManagerImpl(
    private val repository: SettingsRepository
) : SettingsResetManager {

    override suspend fun resetApplicationSettings() {
        repository.resetSettings()
    }

    override suspend fun resetDatabaseSettings() {
        // تنظيف الجداول المرتبطة
    }

    override suspend fun resetAll() {
        resetApplicationSettings()
        resetDatabaseSettings()
    }
}
