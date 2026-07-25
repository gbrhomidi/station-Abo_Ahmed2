package com.aistudio.dieselstationsms.kxmpzq.settings.storage

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

/**
 * واجهة التخزين المجردة — يمكن استبدالها بـ Room/SQLite/SharedPreferences/DataStore
 */
interface SettingsStorage {
    suspend fun load(): ApplicationSettings
    suspend fun save(settings: ApplicationSettings)
    suspend fun clear()
}
