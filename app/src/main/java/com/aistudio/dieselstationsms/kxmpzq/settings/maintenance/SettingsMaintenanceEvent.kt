package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

sealed class SettingsMaintenanceEvent {
    data object CreateBackup : SettingsMaintenanceEvent()
    data class RestoreBackup(val id: String) : SettingsMaintenanceEvent()
    data object OptimizeDatabase : SettingsMaintenanceEvent()
    data object ClearLogs : SettingsMaintenanceEvent()
    data object FactoryReset : SettingsMaintenanceEvent()
}
