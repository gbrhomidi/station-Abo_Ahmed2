package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.NumberSetting
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SettingsCard
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun BackupSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "النسخ الاحتياطي") {
        NumberSetting("عدد النسخ المحتفظ بها", settings.backupRetentionCount.toLong()) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(backupRetentionCount = it.toInt().coerceIn(1, 100))))
        }
    }
}
