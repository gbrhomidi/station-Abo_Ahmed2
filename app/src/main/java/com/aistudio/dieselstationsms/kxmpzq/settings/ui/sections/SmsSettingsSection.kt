package com.aistudio.dieselstationsms.kxmpzq.settings.ui.sections

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.NumberSetting
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SettingsCard
import com.aistudio.dieselstationsms.kxmpzq.settings.ui.components.SwitchSetting
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsEvent

@Composable
fun SmsSettingsSection(
    settings: ApplicationSettings,
    onEvent: (ApplicationSettingsEvent) -> Unit
) {
    SettingsCard(title = "إعدادات الرسائل النصية") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchSetting("السماح باستقبال SMS", settings.smsReceiveEnabled) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(smsReceiveEnabled = it)))
            }
            SwitchSetting("السماح بإرسال SMS", settings.smsSendEnabled) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(smsSendEnabled = it)))
            }
            SwitchSetting("إعادة المحاولة عند الفشل", settings.smsRetryEnabled) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(smsRetryEnabled = it)))
            }
            SwitchSetting("تقارير التسليم", settings.deliveryReportsEnabled) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(deliveryReportsEnabled = it)))
            }
            NumberSetting("الحد الأقصى لأجزاء الرسالة", settings.smsMaxParts.toLong()) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(smsMaxParts = it.toInt().coerceIn(1, 20))))
            }
            NumberSetting("تكلفة الجزء", settings.smsCostPerPart.toLong()) {
                onEvent(ApplicationSettingsEvent.Update(settings.copy(smsCostPerPart = it.toDouble().coerceAtLeast(0.0))))
            }
        }
    }
}
