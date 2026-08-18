package com.aistudio.dieselstationsms.kxmpzq.settings.security

interface SettingsEncryption {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
