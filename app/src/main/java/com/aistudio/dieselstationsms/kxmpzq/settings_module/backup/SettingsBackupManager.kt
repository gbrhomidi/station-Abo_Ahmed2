package com.aistudio.dieselstationsms.kxmpzq.settings.backup

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

/**
 * واجهة إدارة النسخ الاحتياطي للإعدادات
 */
interface SettingsBackupManager {
    suspend fun exportSettings(settings: ApplicationSettings): String
    suspend fun importSettings(json: String): ApplicationSettings
    suspend fun createBackup()
    suspend fun restoreBackup()
    suspend fun deleteBackup()
    suspend fun listBackups(): List<BackupEntry>
}

data class BackupEntry(
    val id: String,
    val createdAt: Long,
    val size: Long,
    val isEncrypted: Boolean
)
