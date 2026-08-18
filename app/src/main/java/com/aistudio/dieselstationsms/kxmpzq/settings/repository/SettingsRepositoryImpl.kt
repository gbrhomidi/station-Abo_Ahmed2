package com.aistudio.dieselstationsms.kxmpzq.settings.repository

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.storage.SettingsStorage
import com.aistudio.dieselstationsms.kxmpzq.settings.validation.SettingsValidationResult
import com.aistudio.dieselstationsms.kxmpzq.settings.validation.SettingsValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/** Repository فعلي فوق SettingsStorage/DatabaseHelper. */
class SettingsRepositoryImpl(
    private val storage: SettingsStorage,
    private val validator: SettingsValidator = SettingsValidator()
) : SettingsRepository {

    private val _state = MutableStateFlow(runBlocking { storage.load() })

    override fun observeSettings(): Flow<ApplicationSettings> = _state.asStateFlow()

    override suspend fun getSettings(): ApplicationSettings = _state.value

    override suspend fun saveSettings(settings: ApplicationSettings) {
        when (val result = validator.validate(settings)) {
            SettingsValidationResult.Valid -> Unit
            is SettingsValidationResult.Invalid ->
                throw IllegalArgumentException(result.errors.joinToString("\n"))
        }
        storage.save(settings.copy(updatedAt = System.currentTimeMillis()))
        _state.value = storage.load()
    }

    override suspend fun resetSettings() {
        storage.clear()
        val defaults = ApplicationSettings()
        storage.save(defaults)
        _state.value = storage.load()
    }
}
