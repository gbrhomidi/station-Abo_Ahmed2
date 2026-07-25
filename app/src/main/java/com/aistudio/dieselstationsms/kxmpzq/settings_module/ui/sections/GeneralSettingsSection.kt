package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun GeneralSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "الإعدادات العامة") {
        SwitchSetting(
            title = "تشغيل التطبيق",
            checked = settings.appEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(appEnabled = it)))
            }
        )
        SwitchSetting(
            title = "الوضع الداكن",
            checked = settings.darkMode,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(darkMode = it)))
            }
        )
        SwitchSetting(
            title = "وضع التصحيح",
            checked = settings.debugMode,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(debugMode = it)))
            }
        )
    }
}
