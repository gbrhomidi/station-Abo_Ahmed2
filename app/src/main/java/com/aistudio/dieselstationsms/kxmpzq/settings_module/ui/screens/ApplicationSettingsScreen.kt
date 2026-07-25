package com.aistudio.dieselstationsms.kxmpzq.settings.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections.*
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationSettingsScreen(
    state: ApplicationSettingsState,
    onEvent: (ApplicationSettingsEvent) -> Unit,
    onNavigateToMonitoring: () -> Unit = {},
    onNavigateToMaintenance: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        "عام" to Icons.Default.Settings,
        "التشغيل" to Icons.Default.PlayArrow,
        "SMS" to Icons.Default.Sms,
        "المراقبة" to Icons.Default.MonitorHeart,
        "الأمان" to Icons.Default.Security,
        "النسخ" to Icons.Default.Backup
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("إعدادات التطبيق") },
                actions = {
                    IconButton(onClick = onNavigateToMonitoring) {
                        Icon(Icons.Default.Dashboard, contentDescription = "لوحة المراقبة")
                    }
                    IconButton(onClick = onNavigateToMaintenance) {
                        Icon(Icons.Default.Build, contentDescription = "الصيانة")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Tabs
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, (title, icon) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = null) },
                        text = { Text(title, maxLines = 1) }
                    )
                }
            }

            // Content
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    when (selectedTab) {
                        0 -> GeneralSettingsSection(state.settings, onEvent)
                        1 -> StartupSettingsSection(state.settings, onEvent)
                        2 -> SmsSettingsSection(state.settings, onEvent)
                        3 -> MonitoringSettingsSection(state.settings, onEvent)
                        4 -> SecuritySettingsSection(state.settings, onEvent)
                        5 -> BackupSettingsSection(state.settings, onEvent)
                    }
                }

                // Save Button
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onEvent(ApplicationSettingsEvent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isSaving
                    ) {
                        Text(if (state.isSaving) "جاري الحفظ..." else "حفظ الإعدادات")
                    }
                }

                // Reset Button
                item {
                    OutlinedButton(
                        onClick = { onEvent(ApplicationSettingsEvent.Reset) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("إعادة الضبط الافتراضي")
                    }
                }
            }

            // Error Snackbar
            if (state.errorMessage != null) {
                ErrorSnackbar(
                    message = state.errorMessage,
                    onDismiss = { onEvent(ApplicationSettingsEvent.ClearMessage) }
                )
            }

            // Success Indicator
            if (state.savedSuccessfully) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text("تم الحفظ بنجاح ✅")
                }
            }
        }
    }
}
