package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class MonitoringEvent {
    data object Refresh : MonitoringEvent()
    data object ClearLogs : MonitoringEvent()
    data object ClearMetrics : MonitoringEvent()
}

class MonitoringViewModel(
    private val repository: SettingsMonitoringRepository
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsMonitoringState())
    val state: StateFlow<SettingsMonitoringState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeMonitoring().collect { value -> _state.value = value }
        }
        refresh()
    }

    fun onEvent(event: MonitoringEvent) {
        when (event) {
            MonitoringEvent.Refresh -> refresh()
            MonitoringEvent.ClearLogs -> run { viewModelScope.launch { repository.clearLogs() } }
            MonitoringEvent.ClearMetrics -> run { viewModelScope.launch { repository.clearMetrics() } }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            runCatching { repository.refresh() }
                .onFailure { error -> _state.update { it.copy(lastError = error.message) } }
        }
    }
}
