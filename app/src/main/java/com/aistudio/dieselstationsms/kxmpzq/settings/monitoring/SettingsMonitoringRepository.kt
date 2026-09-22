package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

import kotlinx.coroutines.flow.Flow

interface SettingsMonitoringRepository {
    fun observeMonitoring(): Flow<SettingsMonitoringState>
    suspend fun refresh()
    suspend fun clearLogs()
    suspend fun clearMetrics()
}
