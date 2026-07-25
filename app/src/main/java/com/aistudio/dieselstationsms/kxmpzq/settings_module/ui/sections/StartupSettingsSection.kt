package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun StartupSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "التشغيل التلقائي") {
        SwitchSetting(
            title = "التشغيل بعد إعادة التشغيل",
            checked = settings.autoStartEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(autoStartEnabled = it)))
            }
        )
        NumberSetting(
            title = "تأخير الإقلاع (ms)",
            value = settings.bootDelayMs,
            onValueChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(bootDelayMs = it)))
            }
        )
        NumberSetting(
            title = "مهلة Pipeline (ms)",
            value = settings.pipelineTimeoutMs,
            onValueChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(pipelineTimeoutMs = it)))
            }
        )
    }
}
