package com.aistudio.dieselstationsms.kxmpzq.settings.backup

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * تنفيذ النسخ الاحتياطي باستخدام JSON
 */
class JsonSettingsBackupManager(
    private val context: Context
) : SettingsBackupManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val backupDir: File
        get() = File(context.getExternalFilesDir(null), "settings_backups").apply {
            if (!exists()) mkdirs()
        }

    override suspend fun exportSettings(settings: ApplicationSettings): String {
        return json.encodeToString(settings)
    }

    override suspend fun importSettings(data: String): ApplicationSettings {
        return json.decodeFromString(data)
    }

    override suspend fun createBackup() {
        val timestamp = System.currentTimeMillis()
        val file = File(backupDir, "settings_backup_$timestamp.json")
        // يتم حفظ النسخة من Repository لاحقاً
        file.createNewFile()
    }

    override suspend fun restoreBackup() {
        // استعادة آخر نسخة
    }

    override suspend fun deleteBackup() {
        // حذف نسخة محددة
    }

    override suspend fun listBackups(): List<BackupEntry> {
        return backupDir.listFiles()?.map { file ->
            BackupEntry(
                id = file.name,
                createdAt = file.lastModified(),
                size = file.length(),
                isEncrypted = false
            )
        } ?: emptyList()
    }
}
