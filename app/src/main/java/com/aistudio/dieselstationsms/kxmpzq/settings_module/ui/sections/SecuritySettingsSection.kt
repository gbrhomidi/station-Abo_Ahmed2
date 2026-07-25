package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun SecuritySettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "الأمان والحماية") {
        SwitchSetting(
            title = "تشفير التخزين",
            checked = settings.encryptStorage,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(encryptStorage = it)))
            }
        )
        SwitchSetting(
            title = "طلب فتح الجهاز",
            checked = settings.requireDeviceUnlock,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(requireDeviceUnlock = it)))
            }
        )
        SwitchSetting(
            title = "منع لقطات الشاشة",
            checked = settings.preventScreenshots,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(preventScreenshots = it)))
            }
        )
    }
}
