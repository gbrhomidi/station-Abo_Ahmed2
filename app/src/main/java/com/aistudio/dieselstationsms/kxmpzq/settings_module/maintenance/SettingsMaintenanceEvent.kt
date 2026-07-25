package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

/**
 * أحداث صيانة الإعدادات
 */
sealed class SettingsMaintenanceEvent {
    object CreateBackup : SettingsMaintenanceEvent()
    data class RestoreBackup(val data: String) : SettingsMaintenanceEvent()
    object ClearLogs : SettingsMaintenanceEvent()
    object OptimizeDatabase : SettingsMaintenanceEvent()
    object FactoryReset : SettingsMaintenanceEvent()
}
