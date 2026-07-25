package com.aistudio.dieselstationsms.kxmpzq.settings.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity لإعدادات التطبيق في Room
 * تخزن الإعدادات كـ JSON لتجنب تعديل Schema باستمرار
 */
@Entity(tableName = "application_settings")
data class ApplicationSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val jsonData: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int = 1
)
