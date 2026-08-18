package com.aistudio.dieselstationsms.kxmpzq.settings.backup

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

/** عقد النسخ الاحتياطي الفعلي لإعدادات module. */
interface SettingsBackupManager {
    suspend fun exportSettings(settings: ApplicationSettings): String
    suspend fun importSettings(data: String): ApplicationSettings
    suspend fun createBackup(): String
    suspend fun restoreBackup(id: String): ApplicationSettings
    suspend fun deleteBackup(id: String): Boolean
    suspend fun listBackups(): List<BackupEntry>
}

data class BackupEntry(
    val id: String,
    val createdAt: Long,
    val size: Long,
    val isEncrypted: Boolean
)
