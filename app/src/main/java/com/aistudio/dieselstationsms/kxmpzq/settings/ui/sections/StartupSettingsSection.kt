package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SettingsCard
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SwitchSetting
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun StartupSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "التشغيل والخلفية") {
        SwitchSetting("تشغيل خدمة SMS", settings.smsServiceEnabled) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(smsServiceEnabled = it)))
        }
        SwitchSetting("التشغيل عند استلام SMS", settings.smsReceiveEnabled) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(smsReceiveEnabled = it)))
        }
        SwitchSetting("التشغيل عند إقلاع الجهاز", settings.startOnBoot) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(startOnBoot = it, autoStartEnabled = it)))
        }
        SwitchSetting("السماح بالعمل في الخلفية", settings.runInBackground) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(runInBackground = it)))
        }
        SwitchSetting("التشغيل التلقائي للخدمة", settings.autoStartEnabled) {
            onEvent(ApplicationSettingsEvent.Update(settings.copy(autoStartEnabled = it)))
        }
    }
}
