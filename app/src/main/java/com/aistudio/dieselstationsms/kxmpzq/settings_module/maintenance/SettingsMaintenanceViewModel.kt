package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel للصيانة
 */
class SettingsMaintenanceViewModel(
    private val repository: SettingsMaintenanceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsMaintenanceState())
    val state = _state.asStateFlow()

    fun onEvent(event: SettingsMaintenanceEvent) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isLoading = true, error = null) }

                when (event) {
                    SettingsMaintenanceEvent.CreateBackup -> {
                        repository.createBackup()
                        _state.update { it.copy(backupCreated = true) }
                    }
                    is SettingsMaintenanceEvent.RestoreBackup -> {
                        repository.restoreBackup(event.data)
                        _state.update { it.copy(restoreCompleted = true) }
                    }
                    SettingsMaintenanceEvent.ClearLogs -> {
                        repository.clearLogs()
                        _state.update { it.copy(logsDeleted = true) }
                    }
                    SettingsMaintenanceEvent.OptimizeDatabase -> {
                        repository.optimizeDatabase()
                        _state.update { it.copy(databaseOptimized = true) }
                    }
                    SettingsMaintenanceEvent.FactoryReset -> {
                        repository.factoryReset()
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
