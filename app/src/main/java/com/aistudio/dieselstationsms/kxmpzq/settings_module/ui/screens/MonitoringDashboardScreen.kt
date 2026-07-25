package com.aistudio.dieselstationsms.kxmpzq.settings.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aistudio.dieselstationsms.kxmpzq.settings.monitoring.SettingsMonitoringState
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringDashboardScreen(
    state: SettingsMonitoringState,
    onRefresh: () -> Unit,
    onClearLogs: () -> Unit,
    onClearMetrics: () -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("لوحة المراقبة") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
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
            // Service Status
            item {
                SettingsCard(title = "حالة الخدمة") {
                    StatusIndicator(
                        status = if (state.serviceRunning) "تعمل" else "متوقفة",
                        isHealthy = state.serviceRunning
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusIndicator(
                        status = if (state.serviceHealthy) "صحية" else "غير معروفة",
                        isHealthy = state.serviceHealthy
                    )
                }
            }

            // Pipeline Status
            item {
                SettingsCard(title = "حالة Pipeline") {
                    InfoRow("الحالة الحالية", state.currentStartupState)
                    InfoRow("المرحلة النشطة", state.activePhase ?: "-")
                    InfoRow("المراحل المكتملة", state.completedPhases.size.toString())
                    InfoRow("المراحل الفاشلة", state.failedPhases.size.toString())
                }
            }

            // System Info
            item {
                SettingsCard(title = "معلومات النظام") {
                    InfoRow("وقت التشغيل", state.uptime)
                    InfoRow("استخدام الذاكرة", state.memoryUsage)
                    InfoRow("استخدام CPU", state.cpuUsage)
                    InfoRow("البطارية", "${state.batteryLevel}%")
                }
            }

            // Events
            item {
                SettingsCard(title = "الأحداث") {
                    InfoRow("عدد الأحداث", state.eventsCount.toString())
                }
            }

            // Metrics
            item {
                SettingsCard(title = "المقاييس") {
                    state.metrics.forEach { (key, value) ->
                        InfoRow(key, value.toString())
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    ActionButton(
                        text = "مسح المقاييس",
                        onClick = onClearMetrics
                    )
                }
            }

            // Logs
            item {
                SettingsCard(title = "السجلات") {
                    state.logs.takeLast(10).forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    ActionButton(
                        text = "مسح السجلات",
                        onClick = onClearLogs
                    )
                }
            }

            // Last Error
            if (state.lastError != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "آخر خطأ",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(state.lastError)
                        }
                    }
                }
            }
        }
    }
}
