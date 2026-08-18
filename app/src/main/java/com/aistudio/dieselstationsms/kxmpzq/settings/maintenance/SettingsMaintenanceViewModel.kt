package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsMaintenanceViewModel(
    private val repository: SettingsMaintenanceRepository
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsMaintenanceState())
    val state: StateFlow<SettingsMaintenanceState> = _state.asStateFlow()

    fun onEvent(event: SettingsMaintenanceEvent) {
        when (event) {
            SettingsMaintenanceEvent.CreateBackup -> runOperation { repository.createBackup(); _state.update { it.copy(backupCreated = true) } }
            is SettingsMaintenanceEvent.RestoreBackup -> runOperation { repository.restoreBackup(event.id) }
            SettingsMaintenanceEvent.OptimizeDatabase -> runOperation { repository.optimizeDatabase(); _state.update { it.copy(databaseOptimized = true) } }
            SettingsMaintenanceEvent.ClearLogs -> runOperation { repository.clearLogs() }
            SettingsMaintenanceEvent.FactoryReset -> runOperation { repository.factoryReset() }
        }
    }

    private fun runOperation(operation: suspend () -> Unit) {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, backupCreated = false, databaseOptimized = false) }
            runCatching { operation() }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "تعذر تنفيذ العملية") } }
            _state.update { it.copy(isLoading = false) }
        }
    }
}
