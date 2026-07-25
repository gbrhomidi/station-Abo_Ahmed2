package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun BackupSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "النسخ الاحتياطي") {
        SwitchSetting(
            title = "نسخ احتياطي تلقائي",
            checked = settings.autoBackupEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(autoBackupEnabled = it)))
            }
        )
        SwitchSetting(
            title = "تشفير النسخ",
            checked = settings.encryptBackup,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(encryptBackup = it)))
            }
        )
        NumberSetting(
            title = "فترة النسخ (ساعة)",
            value = settings.backupIntervalHours.toLong(),
            onValueChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(backupIntervalHours = it.toInt())))
            }
        )
    }
}
