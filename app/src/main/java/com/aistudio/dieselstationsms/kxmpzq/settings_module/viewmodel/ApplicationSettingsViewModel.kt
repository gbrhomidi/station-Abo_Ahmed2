package com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel لشاشة إعدادات التطبيق
 */
class ApplicationSettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ApplicationSettingsState())
    val state: StateFlow<ApplicationSettingsState> = _state.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            repository.observeSettings()
                .collect { settings ->
                    _state.update { it.copy(settings = settings, isLoading = false) }
                }
        }
    }

    fun onEvent(event: ApplicationSettingsEvent) {
        when (event) {
            is ApplicationSettingsEvent.UpdateSettings -> {
                _state.update { it.copy(settings = event.settings, savedSuccessfully = false) }
            }
            ApplicationSettingsEvent.Save -> saveSettings()
            ApplicationSettingsEvent.Reset -> resetSettings()
            ApplicationSettingsEvent.ClearMessage -> {
                _state.update { it.copy(errorMessage = null, savedSuccessfully = false) }
            }
        }
    }

    private fun saveSettings() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isSaving = true) }
                repository.saveSettings(_state.value.settings)
                _state.update { it.copy(isSaving = false, savedSuccessfully = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, errorMessage = e.message) }
            }
        }
    }

    private fun resetSettings() {
        viewModelScope.launch {
            repository.resetSettings()
        }
    }
}
