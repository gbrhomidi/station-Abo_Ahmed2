package com.aistudio.dieselstationsms.kxmpzq.settings.database

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Mapper بين Model و Entity
 */
object ApplicationSettingsMapper {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun ApplicationSettingsEntity.toModel(): ApplicationSettings {
        return json.decodeFromString(jsonData)
    }

    fun ApplicationSettings.toEntity(): ApplicationSettingsEntity {
        val now = System.currentTimeMillis()
        return ApplicationSettingsEntity(
            id = 1,
            jsonData = json.encodeToString(this),
            createdAt = if (this.updatedAt > 0) this.updatedAt else now,
            updatedAt = now,
            version = this.version
        )
    }
}
