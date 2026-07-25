package com.aistudio.dieselstationsms.kxmpzq.settings.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aistudio.dieselstationsms.kxmpzq.settings.maintenance.SettingsMaintenanceEvent
import com.aistudio.dieselstationsms.kxmpzq.settings.maintenance.SettingsMaintenanceState
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(
    state: SettingsMaintenanceState,
    onEvent: (SettingsMaintenanceEvent) -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("أدوات الصيانة") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Backup Section
            item {
                SettingsCard(title = "النسخ الاحتياطي") {
                    ActionButton(
                        text = "إنشاء نسخة احتياطية",
                        icon = Icons.Default.Backup,
                        onClick = { onEvent(SettingsMaintenanceEvent.CreateBackup) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ActionButton(
                        text = "استعادة نسخة",
                        icon = Icons.Default.Restore,
                        onClick = { onEvent(SettingsMaintenanceEvent.RestoreBackup("")) }
                    )
                }
            }

            // Database Section
            item {
                SettingsCard(title = "قاعدة البيانات") {
                    ActionButton(
                        text = "تحسين قاعدة البيانات",
                        icon = Icons.Default.Storage,
                        onClick = { onEvent(SettingsMaintenanceEvent.OptimizeDatabase) }
                    )
                }
            }

            // Logs Section
            item {
                SettingsCard(title = "السجلات") {
                    ActionButton(
                        text = "مسح السجلات القديمة",
                        icon = Icons.Default.Delete,
                        onClick = { onEvent(SettingsMaintenanceEvent.ClearLogs) }
                    )
                }
            }

            // Danger Zone
            item {
                SettingsCard(title = "منطقة الخطر ⚠️") {
                    DangerButton(
                        text = "إعادة ضبط المصنع",
                        icon = Icons.Default.Warning,
                        onClick = { onEvent(SettingsMaintenanceEvent.FactoryReset) }
                    )
                }
            }

            // Status
            if (state.backupCreated) {
                item {
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text("تم إنشاء النسخة الاحتياطية ✅")
                    }
                }
            }

            if (state.databaseOptimized) {
                item {
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text("تم تحسين قاعدة البيانات ✅")
                    }
                }
            }

            if (state.error != null) {
                item {
                    ErrorSnackbar(
                        message = state.error,
                        onDismiss = { /* dismiss */ }
                    )
                }
            }
        }

        // Loading Overlay
        if (state.isLoading) {
            LoadingOverlay(isLoading = true)
        }
    }
}
