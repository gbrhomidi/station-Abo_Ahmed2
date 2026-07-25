package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

/**
 * حالة صيانة الإعدادات
 */
data class SettingsMaintenanceState(
    val isLoading: Boolean = false,
    val backupCreated: Boolean = false,
    val restoreCompleted: Boolean = false,
    val logsDeleted: Boolean = false,
    val databaseOptimized: Boolean = false,
    val error: String? = null
)
