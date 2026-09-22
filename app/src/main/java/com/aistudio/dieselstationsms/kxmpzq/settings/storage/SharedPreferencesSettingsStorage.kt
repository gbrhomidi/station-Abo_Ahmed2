package com.aistudio.dieselstationsms.kxmpzq.settings.storage

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * تخزين الإعدادات عبر SQLite/DatabaseHelper.
 *
 * اسم الصنف محفوظ للتوافق مع SettingsModule القديم، لكن لا توجد أي كتابة
 * إلى SharedPreferences ولا إلى جدول Room. المصدر الفعلي هو المفتاح الموجود
 * في جدول `system_settings` الذي تديره DatabaseHelper.
 */
class SharedPreferencesSettingsStorage(
    context: Context,
    private val database: DatabaseHelper = DatabaseHelper.getInstance(context.applicationContext)
) : SettingsStorage {

    companion object {
        const val STORAGE_KEY = "settings_module.application_settings_json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun load(): ApplicationSettings = withContext(Dispatchers.IO) {
        val raw = database.getSetting(STORAGE_KEY).trim()
        if (raw.isEmpty()) return@withContext ApplicationSettings()

        runCatching {
            json.decodeFromString<ApplicationSettings>(raw)
        }.getOrElse {
            ApplicationSettings()
        }
    }

    override suspend fun save(settings: ApplicationSettings) = withContext(Dispatchers.IO) {
        val stored = json.encodeToString(ApplicationSettings.serializer(), settings)
        check(database.setSetting(STORAGE_KEY, stored)) {
            "تعذر حفظ إعدادات التطبيق في system_settings"
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        // DatabaseHelper لا يعرّض حذفاً من system_settings عبر API؛ كتابة قيمة
        // فارغة تحفظ عقد المصدر ولا تحذف سجلات إعدادات النظام الأخرى.
        check(database.setSetting(STORAGE_KEY, "")) {
            "تعذر مسح إعدادات module"
        }
    }
}
