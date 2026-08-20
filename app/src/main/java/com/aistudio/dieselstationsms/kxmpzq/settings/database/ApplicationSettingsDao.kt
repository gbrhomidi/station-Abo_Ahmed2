package com.aistudio.dieselstationsms.kxmpzq.settings.database

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import kotlinx.coroutines.flow.Flow

/**
 * عقد DAO مستقل عن Room.
 * التنفيذ الفعلي موجود في SQLiteSettingsStorage عبر DatabaseHelper.
 */
interface ApplicationSettingsDao {
    fun observe(): Flow<ApplicationSettings?>
    suspend fun get(): ApplicationSettings?
    suspend fun save(settings: ApplicationSettings)
    suspend fun clear()
}
