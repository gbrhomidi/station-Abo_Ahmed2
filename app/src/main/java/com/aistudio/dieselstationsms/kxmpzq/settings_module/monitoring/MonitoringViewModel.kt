package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel للمراقبة
 */
class MonitoringViewModel(
    private val repository: SettingsMonitoringRepository
) : ViewModel() {

    val state = repository.observeMonitoring()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsMonitoringState()
        )

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun clearMetrics() {
        viewModelScope.launch {
            repository.clearMetrics()
        }
    }
}
