package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

/**
 * واجهة Repository للصيانة
 */
interface SettingsMaintenanceRepository {
    suspend fun createBackup(): String
    suspend fun restoreBackup(data: String)
    suspend fun clearLogs()
    suspend fun optimizeDatabase()
    suspend fun factoryReset()
}
