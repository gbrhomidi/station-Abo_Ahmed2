package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.NumberSetting
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SettingsCard
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SwitchSetting
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun MonitoringSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "المراقبة والسجلات") {
        SwitchSetting("تفعيل المراقبة", settings.monitoringEnabled) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(monitoringEnabled = it)))
        }
        SwitchSetting("تسجيل أحداث التدقيق", settings.auditLoggingEnabled) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(auditLoggingEnabled = it)))
        }
        NumberSetting("الاحتفاظ بالسجلات بالأيام", settings.keepLogsDays.toLong()) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(keepLogsDays = it.toInt().coerceIn(1, 3650))))
        }
    }
}
