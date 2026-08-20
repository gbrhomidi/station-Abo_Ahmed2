package com.aistudio.dieselstationsms.kxmpzq.settings.reset

import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository

/** إعادة ضبط غير هدّامة لإعدادات التطبيق فقط. */
class SettingsResetManagerImpl(
    private val repository: SettingsRepository
) : SettingsResetManager {
    override suspend fun resetApplicationSettings() {
        repository.resetSettings()
    }

    override suspend fun resetDatabaseSettings() {
        // لا توجد عملية آمنة معتمدة لمسح جداول الأعمال هنا؛ لا ننفذ حذفاً.
        // إعادة ضبط إعدادات module يتم عبر repository فقط.
    }

    override suspend fun resetAll() {
        resetApplicationSettings()
        resetDatabaseSettings()
    }
}
