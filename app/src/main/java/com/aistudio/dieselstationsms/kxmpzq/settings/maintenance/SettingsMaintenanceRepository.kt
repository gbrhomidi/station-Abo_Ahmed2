package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

interface SettingsMaintenanceRepository {
    suspend fun createBackup(): String
    suspend fun restoreBackup(data: String)
    suspend fun clearLogs()
    suspend fun optimizeDatabase()
    suspend fun factoryReset()
}
