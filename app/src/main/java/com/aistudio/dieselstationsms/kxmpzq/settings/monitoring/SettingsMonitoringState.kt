package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

data class SettingsMonitoringState(
    val serviceRunning: Boolean = false,
    val serviceHealthy: Boolean = false,
    val currentStartupState: String = "UNKNOWN",
    val activePhase: String? = null,
    val completedPhases: List<String> = emptyList(),
    val failedPhases: List<String> = emptyList(),
    val eventsCount: Int = 0,
    val metrics: Map<String, Any> = emptyMap(),
    val logs: List<String> = emptyList(),
    val lastError: String? = null,
    val uptime: String = "00:00:00",
    val memoryUsage: String = "غير متاح",
    val cpuUsage: String = "غير متاح",
    val batteryLevel: Int = -1
)
