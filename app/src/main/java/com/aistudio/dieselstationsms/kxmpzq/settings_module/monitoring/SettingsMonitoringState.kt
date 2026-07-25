package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

/**
 * حالة مراقبة النظام داخل شاشة الإعدادات
 */
data class SettingsMonitoringState(
    val serviceRunning: Boolean = false,
    val serviceHealthy: Boolean = false,
    val currentStartupState: String = "IDLE",
    val activePhase: String? = null,
    val completedPhases: List<String> = emptyList(),
    val failedPhases: List<String> = emptyList(),
    val eventsCount: Int = 0,
    val metrics: Map<String, Any> = emptyMap(),
    val logs: List<String> = emptyList(),
    val lastError: String? = null,
    val uptime: String = "00:00:00",
    val memoryUsage: String = "0MB",
    val cpuUsage: String = "0%",
    val batteryLevel: Int = 100
)
