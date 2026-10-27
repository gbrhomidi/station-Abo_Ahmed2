package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

data class SettingsMonitoringState(
    val serviceRunning: Boolean = false,
    val serviceHealthy: Boolean = false,
    val serviceHealthStatus: String = "UNAVAILABLE",
    val currentStartupState: String = "UNKNOWN",
    val activePhase: String? = null,
    val completedPhases: List<String> = emptyList(),
    val failedPhases: List<String> = emptyList(),
    /** Count of the most recent activity-log rows returned (bounded by the query limit), not a database-wide total. */
    val eventsCount: Int = 0,
    val metrics: Map<String, Any> = emptyMap(),
    val sourceStatus: Map<String, String> = emptyMap(),
    val collectionErrors: List<String> = emptyList(),
    val logs: List<String> = emptyList(),
    val lastError: String? = null,
    val serviceErrorCount: Int? = null,
    val uptime: String = "غير متاح",
    val memoryUsage: String = "غير متاح",
    val cpuUsage: String = "غير متاح",
    val batteryLevel: Int = -1,
    val databaseAvailable: Boolean = false,
    val databaseIntegrityOk: Boolean? = null,
    val capturedAtMillis: Long = 0L
)
