package com.aistudio.dieselstationsms.kxmpzq.settings.backup

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.settings.security.SecuritySettingsStore
import org.json.JSONObject
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

    private val appContext = context.applicationContext
    private val securitySettings by lazy { SecuritySettingsStore.open(appContext) }
    private val database by lazy { DatabaseHelper.getInstance(appContext) }

    private val backupDir: File = File(
        appContext.filesDir,
        "settings_backups"
    ).apply { mkdirs() }

    override suspend fun exportSettings(settings: ApplicationSettings): String =
        json.encodeToString(ApplicationSettings.serializer(), settings)

    override suspend fun importSettings(data: String): ApplicationSettings =
        json.decodeFromString(data)

    private fun readSecuritySetting(key: String): String {
        val fallback = SecuritySettingsStore.DEFAULTS[key] ?: "0"
        return if (securitySettings.encryptionEnabled()) {
            securitySettings.getSetting(key) ?: fallback
        } else {
            database.getSetting(key).takeIf { it.isNotBlank() } ?: fallback
        }
    }

    override suspend fun createBackup(): String = withContext(Dispatchers.IO) {
        check(isTrue(readSecuritySetting("secureBackupEnabled"))) {
            "النسخ الاحتياطي الآمن معطل؛ لم يتم إنشاء نسخة غير مشفرة"
        }
        val engine = requireNotNull(encryption) {
            "مفتاح تشفير النسخة غير متاح؛ رُفض إنشاء نسخة غير مشفرة"
        }
        val settings = repository.getSettings()
        val securitySnapshot = JSONObject()
        SecuritySettingsStore.SETTING_KEYS.forEach { key ->
            val value = if (key == SecuritySettingsStore.KEY_ENCRYPT_STORAGE) {
                if (securitySettings.encryptionEnabled()) "1" else "0"
            } else {
                readSecuritySetting(key)
            }
            securitySnapshot.put(key, value)
        }
        val envelope = JSONObject()
            .put("backupFormat", "station-secure-settings")
            .put("schemaVersion", 2)
            .put("createdAt", System.currentTimeMillis())
            .put("applicationSettings", JSONObject(exportSettings(settings)))
            .put("securitySettings", securitySnapshot)
            .put("pinCredential", JSONObject(securitySettings.exportPinCredential()))
        val payload = engine.encrypt(envelope.toString())
        val file = File(backupDir, "settings_backup_${System.currentTimeMillis()}.json.enc")
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
            val root = runCatching { JSONObject(plain) }.getOrNull()
            val isEnvelope = root?.optString("backupFormat") == "station-secure-settings"
            val settings = if (isEnvelope) {
                importSettings(root!!.getJSONObject("applicationSettings").toString())
            } else {
                // Backward compatibility for existing plain/encrypted ApplicationSettings backups.
                importSettings(plain)
            }

            val securitySnapshot = if (isEnvelope) root!!.optJSONObject("securitySettings") else null
            val pinObject = if (isEnvelope) root!!.optJSONObject("pinCredential") else null
            val restoredSecurity = linkedMapOf<String, String>()
            if (securitySnapshot != null) {
                val keys = securitySnapshot.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    require(key in SecuritySettingsStore.SETTING_KEYS) { "مفتاح أمان غير مسموح داخل النسخة: $key" }
                    restoredSecurity[key] = securitySnapshot.optString(
                        key,
                        SecuritySettingsStore.DEFAULTS[key] ?: "0"
                    )
                }
            }
            val hasPinSalt = pinObject?.has("salt") == true
            val hasPinHash = pinObject?.has("hash") == true
            require(hasPinSalt == hasPinHash) { "بيانات PIN غير مكتملة داخل النسخة الاحتياطية" }
            val restoredPin = if (hasPinSalt && hasPinHash) {
                mapOf("salt" to pinObject!!.getString("salt"), "hash" to pinObject.getString("hash"))
            } else emptyMap()

            // Validate the sensitive payload before committing either settings collection.
            val targetEncrypted = isTrue(restoredSecurity[SecuritySettingsStore.KEY_ENCRYPT_STORAGE] ?: "1")
            restoredSecurity["autoLogoutMinutes"]?.let {
                val timeout = it.toIntOrNull()
                require(timeout != null && timeout in 0..1440) {
                    "مدة الخروج التلقائي في النسخة غير صالحة"
                }
            }
            if (restoredPin.isNotEmpty()) {
                require(restoredPin["salt"].orEmpty().isNotBlank() && restoredPin["hash"].orEmpty().isNotBlank()) {
                    "بيانات PIN في النسخة غير صالحة"
                }
            }

            repository.saveSettings(settings)
            if (securitySnapshot != null) {
                (SecuritySettingsStore.SETTING_KEYS - SecuritySettingsStore.KEY_ENCRYPT_STORAGE).forEach { key ->
                    val value = restoredSecurity[key] ?: SecuritySettingsStore.DEFAULTS[key] ?: "0"
                    if (targetEncrypted) {
                        securitySettings.putSetting(key, value)
                        database.deleteSetting(key)
                    } else {
                        check(database.setSetting(key, value)) { "تعذر استعادة إعداد الأمان: $key" }
                        securitySettings.removeSetting(key)
                    }
                }
                securitySettings.putSetting(
                    SecuritySettingsStore.KEY_ENCRYPT_STORAGE,
                    if (targetEncrypted) "1" else "0"
                )
                securitySettings.importPinCredential(restoredPin)
                runCatching { database.deleteSetting(SecuritySettingsStore.KEY_PIN_CODE) }
            }
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

    private fun isTrue(value: String): Boolean =
        value.trim().lowercase() in setOf("1", "true", "yes", "on")

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
