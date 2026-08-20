package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SettingsCard
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.TextSetting
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun GeneralSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "الإعدادات العامة") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextSetting("اسم المحطة", settings.stationName) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(stationName = it)))
            }
            TextSetting("اللغة", settings.language) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(language = it)))
            }
            TextSetting("المظهر (system/light/dark)", settings.theme) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(theme = it)))
            }
        }
    }
}
