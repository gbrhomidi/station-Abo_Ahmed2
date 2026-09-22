package com.aistudio.dieselstationsms.kxmpzq.settings.database

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import kotlinx.serialization.json.Json

/** Mapper صريح بين سجل JSON والنموذج المركزي. */
object ApplicationSettingsMapper {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun ApplicationSettingsEntity.toModel(): ApplicationSettings =
        json.decodeFromString<ApplicationSettings>(jsonData)

    fun ApplicationSettings.toEntity(
        previous: ApplicationSettingsEntity? = null
    ): ApplicationSettingsEntity {
        val now = System.currentTimeMillis()
        return ApplicationSettingsEntity(
            id = previous?.id ?: 1,
            jsonData = json.encodeToString(ApplicationSettings.serializer(), this),
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            version = 1
        )
    }
}
