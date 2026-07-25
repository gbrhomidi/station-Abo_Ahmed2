package com.aistudio.dieselstationsms.kxmpzq.settings.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO لإعدادات التطبيق
 */
@Dao
interface ApplicationSettingsDao {

    @Query("SELECT * FROM application_settings WHERE id = 1")
    fun observe(): Flow<ApplicationSettingsEntity?>

    @Query("SELECT * FROM application_settings WHERE id = 1")
    suspend fun get(): ApplicationSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: ApplicationSettingsEntity)

    @Query("DELETE FROM application_settings")
    suspend fun clear()
}
