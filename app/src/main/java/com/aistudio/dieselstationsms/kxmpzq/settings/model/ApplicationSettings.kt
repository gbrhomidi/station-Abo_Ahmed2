package com.aistudio.dieselstationsms.kxmpzq.settings.model

import kotlinx.serialization.Serializable

/** إعدادات التطبيق الدائمة المستخدمة من واجهة الإعدادات وجسر WebView. */
@Serializable
data class ApplicationSettings(
    val stationName: String = "محطة أبو أحمد",
    val language: String = "ar",
    val theme: String = "system",
    val smsServiceEnabled: Boolean = true,
    val smsReceiveEnabled: Boolean = true,
    val smsSendEnabled: Boolean = true,
    val smsRetryEnabled: Boolean = true,
    val autoStartEnabled: Boolean = true,
    val startOnBoot: Boolean = true,
    val runInBackground: Boolean = true,
    val deliveryReportsEnabled: Boolean = true,
    val smsMaxParts: Int = 6,
    val smsCostPerPart: Double = 1.0,
    val monitoringEnabled: Boolean = true,
    val auditLoggingEnabled: Boolean = true,
    val keepLogsDays: Int = 30,
    val requireBiometricForSettings: Boolean = false,
    val encryptBackup: Boolean = true,
    val backupRetentionCount: Int = 10,
    val updatedAt: Long = 0L
)
