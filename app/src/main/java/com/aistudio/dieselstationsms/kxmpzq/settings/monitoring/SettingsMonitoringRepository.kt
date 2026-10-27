package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

import kotlinx.coroutines.flow.Flow

interface SettingsMonitoringRepository {
    fun observeMonitoring(): Flow<SettingsMonitoringState>
    suspend fun refresh()
    /** Delete only operational log rows older than the configured retention period. Audit logs are preserved. */
    suspend fun clearLogs(): Int
    /** Delete only old SMS metric rows older than the configured retention period. */
    suspend fun clearMetrics(): Int
}
