package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

data class SettingsMaintenanceState(
    val isLoading: Boolean = false,
    val backupCreated: Boolean = false,
    val databaseOptimized: Boolean = false,
    val error: String? = null
)
