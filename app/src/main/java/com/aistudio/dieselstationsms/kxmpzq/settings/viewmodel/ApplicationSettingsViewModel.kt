package com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ApplicationSettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ApplicationSettingsState())
    val state: StateFlow<ApplicationSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSettings().collect { settings ->
                _state.update { current -> current.copy(settings = settings) }
            }
        }
    }

    fun onEvent(event: ApplicationSettingsEvent) {
        when (event) {
            is ApplicationSettingsEvent.Update -> _state.update {
                it.copy(settings = event.settings, savedSuccessfully = false, errorMessage = null)
            }
            ApplicationSettingsEvent.Save -> save()
            ApplicationSettingsEvent.Reset -> reset()
            ApplicationSettingsEvent.ClearMessage -> _state.update {
                it.copy(errorMessage = null, savedSuccessfully = false)
            }
        }
    }

    private fun save() {
        if (_state.value.isSaving) return
        val settings = _state.value.settings
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null, savedSuccessfully = false) }
            runCatching { repository.saveSettings(settings) }
                .onSuccess { _state.update { it.copy(isSaving = false, savedSuccessfully = true) } }
                .onFailure { error ->
                    _state.update {
                        it.copy(isSaving = false, errorMessage = error.message ?: "تعذر حفظ الإعدادات")
                    }
                }
        }
    }

    private fun reset() {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null, savedSuccessfully = false) }
            runCatching { repository.resetSettings() }
                .onSuccess { _state.update { it.copy(isSaving = false, savedSuccessfully = true) } }
                .onFailure { error ->
                    _state.update {
                        it.copy(isSaving = false, errorMessage = error.message ?: "تعذر إعادة ضبط الإعدادات")
                    }
                }
        }
    }
}
