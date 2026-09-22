package com.aistudio.dieselstationsms.kxmpzq.settings.database

/**
 * سجل توافق لإعدادات module.
 *
 * لا يمثل جدول Room؛ المشروع يستخدم SQLite عبر DatabaseHelper، لذلك تحفظ
 * البيانات داخل المفتاح `settings_module.application_settings_json` في
 * جدول `system_settings` الموجود فعلياً.
 */
data class ApplicationSettingsEntity(
    val id: Int = 1,
    val jsonData: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int = 1
)
