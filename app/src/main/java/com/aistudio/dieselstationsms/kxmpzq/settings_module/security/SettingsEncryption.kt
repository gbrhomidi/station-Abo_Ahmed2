package com.aistudio.dieselstationsms.kxmpzq.settings.security

/**
 * واجهة تشفير الإعدادات
 */
interface SettingsEncryption {
    fun encrypt(data: String): String
    fun decrypt(data: String): String
}
