package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SettingsCard
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SwitchSetting
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun SecuritySettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "الأمان") {
        SwitchSetting("طلب المصادقة البيومترية للإعدادات", settings.requireBiometricForSettings) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(requireBiometricForSettings = it)))
        }
        SwitchSetting("تشفير النسخ الاحتياطية", settings.encryptBackup) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(encryptBackup = it)))
        }
    }
}
