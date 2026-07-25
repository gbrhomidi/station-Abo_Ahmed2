package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.*
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun SmsSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "إعدادات SMS") {
        SwitchSetting(
            title = "تفعيل خدمة SMS",
            checked = settings.smsServiceEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(smsServiceEnabled = it)))
            }
        )
        SwitchSetting(
            title = "استقبال الرسائل",
            checked = settings.smsReceiveEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(smsReceiveEnabled = it)))
            }
        )
        SwitchSetting(
            title = "إرسال الرسائل",
            checked = settings.smsSendEnabled,
            onCheckedChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(smsSendEnabled = it)))
            }
        )
        NumberSetting(
            title = "فترة المعالجة (ms)",
            value = settings.smsProcessingIntervalMs,
            onValueChange = {
                onEvent(ApplicationSettingsEvent.UpdateSettings(settings.copy(smsProcessingIntervalMs = it)))
            }
        )
    }
}
