package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun MonitoringSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "المراقبة والتشخيص") {
        SwitchSetting(
            title = "مراقبة الصحة",
            checked = settings.healthCheckEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(healthCheckEnabled = it)))
            }
        )
        SwitchSetting(
            title = "تفعيل Metrics",
            checked = settings.metricsEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(metricsEnabled = it)))
            }
        )
        SwitchSetting(
            title = "تفعيل السجلات",
            checked = settings.loggingEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(loggingEnabled = it)))
            }
        )
    }
}
