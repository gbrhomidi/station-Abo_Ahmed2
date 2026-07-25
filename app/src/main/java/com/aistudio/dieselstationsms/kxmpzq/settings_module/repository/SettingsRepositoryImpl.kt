package com.aistudio.dieselstationsms.kxmpzq.settings.repository

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.storage.SettingsStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * تنفيذ Repository مع StateFlow للتحديثات الحية
 */
class SettingsRepositoryImpl(
    private val storage: SettingsStorage
) : SettingsRepository {

    private val _state = MutableStateFlow(ApplicationSettings())

    init {
        runBlocking {
            _state.value = storage.load()
        }
    }

    override fun observeSettings(): Flow<ApplicationSettings> =
        _state.asStateFlow()

    override suspend fun getSettings(): ApplicationSettings =
        _state.value

    override suspend fun saveSettings(settings: ApplicationSettings) {
        storage.save(settings)
        _state.value = settings
    }

    override suspend fun resetSettings() {
        storage.clear()
        val defaults = ApplicationSettings()
        storage.save(defaults)
        _state.value = defaults
    }
}
