package com.aistudio.dieselstationsms.kxmpzq.settings.backup

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository
import com.aistudio.dieselstationsms.kxmpzq.settings.security.SettingsEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** مدير نسخ إعدادات JSON فعلي داخل مساحة التطبيق الخاصة. */
class JsonSettingsBackupManager(
    context: Context,
    private val repository: SettingsRepository,
    private val encryption: SettingsEncryption? = null
) : SettingsBackupManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val backupDir: File = File(
        context.applicationContext.filesDir,
        "settings_backups"
    ).apply { mkdirs() }

    override suspend fun exportSettings(settings: ApplicationSettings): String =
        json.encodeToString(ApplicationSettings.serializer(), settings)

    override suspend fun importSettings(data: String): ApplicationSettings =
        json.decodeFromString(data)

    override suspend fun createBackup(): String = withContext(Dispatchers.IO) {
        val settings = repository.getSettings()
        val plain = exportSettings(settings)
        val encrypted = settings.encryptBackup && encryption != null
        val payload = if (encrypted) encryption!!.encrypt(plain) else plain
        val suffix = if (encrypted) ".json.enc" else ".json"
        val file = File(backupDir, "settings_backup_${System.currentTimeMillis()}$suffix")
        file.writeText(payload, Charsets.UTF_8)
        file.name
    }

    override suspend fun restoreBackup(id: String): ApplicationSettings =
        withContext(Dispatchers.IO) {
            val file = safeFile(id)
            require(file.isFile) { "ملف النسخة الاحتياطية غير موجود" }
            val raw = file.readText(Charsets.UTF_8)
            val plain = if (file.name.endsWith(".enc")) {
                requireNotNull(encryption) { "مفتاح تشفير النسخة غير متاح" }.decrypt(raw)
            } else raw
            val settings = importSettings(plain)
            repository.saveSettings(settings)
            settings
        }

    override suspend fun deleteBackup(id: String): Boolean = withContext(Dispatchers.IO) {
        safeFile(id).takeIf { it.exists() }?.delete() == true
    }

    override suspend fun listBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        backupDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("settings_backup_") }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                BackupEntry(
                    id = it.name,
                    createdAt = it.lastModified(),
                    size = it.length(),
                    isEncrypted = it.name.endsWith(".enc")
                )
            }
            ?: emptyList()
    }

    private fun safeFile(id: String): File {
        require(id.isNotBlank()) { "معرف النسخة مطلوب" }
        val base = backupDir.canonicalFile
        val target = File(base, id).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) {
            "مسار النسخة غير صالح"
        }
        return target
    }
}
