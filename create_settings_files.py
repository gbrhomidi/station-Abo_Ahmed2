import os
import shutil

# المسار الأساسي للمشروع (افترض أن السكريبت يُشغَّل من جذر المشروع)
BASE_PATH = "mpzq"

# محتويات الملفات (كما وردت في المحتوى مع ضبط بسيط للتنسيق)

ENTITY_CONTENT = '''package com.aistudio.dieselstationsms.kxmpzq.settings.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "application_settings"
)
data class ApplicationSettingsEntity(

    @PrimaryKey
    val id: Int = 1,

    // =========================
    // Startup Settings
    // =========================
    val autoStartEnabled: Boolean = true,
    val bootDelayMs: Long = 10_000L,
    val startAfterUserUnlock: Boolean = true,
    val startAfterUpdate: Boolean = true,

    // =========================
    // SMS Service
    // =========================
    val smsServiceEnabled: Boolean = true,
    val autoRestartService: Boolean = true,

    // =========================
    // SMS Configuration
    // =========================
    val controllerPhoneNumber: String = "",
    val allowedSenderNumbers: String = "",
    val sendStartupSms: Boolean = false,
    val sendFailureSms: Boolean = true,
    val sendHealthReport: Boolean = false,

    // =========================
    // Health Monitoring
    // =========================
    val healthMonitorEnabled: Boolean = true,
    val healthCheckIntervalMs: Long = 60_000L,
    val heartbeatEnabled: Boolean = true,
    val heartbeatTimeoutMs: Long = 120_000L,
    val maxHealthFailures: Int = 3,

    // =========================
    // Retry Policy
    // =========================
    val retryEnabled: Boolean = true,
    val maxRetryAttempts: Int = 3,
    val retryBackoffMs: Long = 5_000L,
    val retryStrategy: String = "EXPONENTIAL",

    // =========================
    // Pipeline
    // =========================
    val allowParallelExecution: Boolean = false,
    val pipelineTimeoutMs: Long = 120_000L,
    val phaseTimeoutMs: Long = 30_000L,
    val stopOnCriticalFailure: Boolean = true,

    // =========================
    // Logging
    // =========================
    val loggingEnabled: Boolean = true,
    val logLevel: String = "INFO",
    val saveLogsToDatabase: Boolean = true,
    val logRetentionDays: Int = 30,

    // =========================
    // Metrics
    // =========================
    val metricsEnabled: Boolean = true,
    val collectStartupMetrics: Boolean = true,
    val collectPerformanceMetrics: Boolean = true,

    // =========================
    // Backup
    // =========================
    val backupEnabled: Boolean = true,
    val autoBackup: Boolean = false,
    val backupPath: String = "",

    // =========================
    // Security
    // =========================
    val settingsLockEnabled: Boolean = false,
    val dataEncryptionEnabled: Boolean = false,

    // =========================
    // Station
    // =========================
    val stationId: String = "",
    val stationName: String = "",

    // =========================
    // UI
    // =========================
    val language: String = "AR",
    val themeMode: String = "SYSTEM",

    // =========================
    // Metadata
    // =========================
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
'''

DAO_CONTENT = '''package com.aistudio.dieselstationsms.kxmpzq.settings.data.dao

import androidx.room.*
import com.aistudio.dieselstationsms.kxmpzq.settings.data.entity.ApplicationSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApplicationSettingsDao {

    @Query(
        """
        SELECT * 
        FROM application_settings
        WHERE id = 1
        LIMIT 1
        """
    )
    fun observeSettings(): Flow<ApplicationSettingsEntity?>

    @Query(
        """
        SELECT *
        FROM application_settings
        WHERE id = 1
        LIMIT 1
        """
    )
    suspend fun getSettings(): ApplicationSettingsEntity?

    @Insert(
        onConflict = OnConflictStrategy.REPLACE
    )
    suspend fun insert(settings: ApplicationSettingsEntity)

    @Update
    suspend fun update(settings: ApplicationSettingsEntity)

    @Query(
        """
        DELETE FROM application_settings
        """
    )
    suspend fun deleteAll()

    @Transaction
    suspend fun reset(default: ApplicationSettingsEntity) {
        deleteAll()
        insert(default)
    }
}
'''

DOMAIN_MODEL_CONTENT = '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.model

data class ApplicationSettings(
    val autoStartEnabled: Boolean,
    val bootDelayMs: Long,
    val smsServiceEnabled: Boolean,
    val autoRestartService: Boolean,
    val healthMonitorEnabled: Boolean,
    val healthCheckIntervalMs: Long,
    val heartbeatEnabled: Boolean,
    val heartbeatTimeoutMs: Long,
    val maxRetryAttempts: Int,
    val retryBackoffMs: Long,
    val pipelineTimeoutMs: Long,
    val phaseTimeoutMs: Long,
    val loggingEnabled: Boolean,
    val logLevel: String,
    val metricsEnabled: Boolean,
    val backupEnabled: Boolean,
    val backupPath: String,
    val stationId: String,
    val stationName: String,
    val language: String,
    val themeMode: String
)
'''

MAPPER_CONTENT = '''package com.aistudio.dieselstationsms.kxmpzq.settings.data.mapper

import com.aistudio.dieselstationsms.kxmpzq.settings.data.entity.ApplicationSettingsEntity
import com.aistudio.dieselstationsms.kxmpzq.settings.domain.model.ApplicationSettings

fun ApplicationSettingsEntity.toDomain(): ApplicationSettings {
    return ApplicationSettings(
        autoStartEnabled = autoStartEnabled,
        bootDelayMs = bootDelayMs,
        smsServiceEnabled = smsServiceEnabled,
        autoRestartService = autoRestartService,
        healthMonitorEnabled = healthMonitorEnabled,
        healthCheckIntervalMs = healthCheckIntervalMs,
        heartbeatEnabled = heartbeatEnabled,
        heartbeatTimeoutMs = heartbeatTimeoutMs,
        maxRetryAttempts = maxRetryAttempts,
        retryBackoffMs = retryBackoffMs,
        pipelineTimeoutMs = pipelineTimeoutMs,
        phaseTimeoutMs = phaseTimeoutMs,
        loggingEnabled = loggingEnabled,
        logLevel = logLevel,
        metricsEnabled = metricsEnabled,
        backupEnabled = backupEnabled,
        backupPath = backupPath,
        stationId = stationId,
        stationName = stationName,
        language = language,
        themeMode = themeMode
    )
}
'''

REPOSITORY_INTERFACE_CONTENT = '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.repository

import com.aistudio.dieselstationsms.kxmpzq.settings.domain.model.ApplicationSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observe(): Flow<ApplicationSettings>
    suspend fun get(): ApplicationSettings
    suspend fun save(settings: ApplicationSettings)
    suspend fun reset()
}
'''

REPOSITORY_IMPL_CONTENT = '''package com.aistudio.dieselstationsms.kxmpzq.settings.data.repository

import com.aistudio.dieselstationsms.kxmpzq.settings.data.dao.ApplicationSettingsDao
import com.aistudio.dieselstationsms.kxmpzq.settings.data.entity.ApplicationSettingsEntity
import com.aistudio.dieselstationsms.kxmpzq.settings.data.mapper.toDomain
import com.aistudio.dieselstationsms.kxmpzq.settings.domain.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(
    private val dao: ApplicationSettingsDao
) : SettingsRepository {

    override fun observe(): Flow<ApplicationSettings> {
        return dao.observeSettings()
            .map { (it ?: ApplicationSettingsEntity()).toDomain() }
    }

    override suspend fun get(): ApplicationSettings {
        return (dao.getSettings() ?: ApplicationSettingsEntity()).toDomain()
    }

    override suspend fun save(settings: ApplicationSettings) {
        dao.insert(
            ApplicationSettingsEntity(
                autoStartEnabled = settings.autoStartEnabled,
                bootDelayMs = settings.bootDelayMs,
                smsServiceEnabled = settings.smsServiceEnabled,
                autoRestartService = settings.autoRestartService,
                healthMonitorEnabled = settings.healthMonitorEnabled,
                healthCheckIntervalMs = settings.healthCheckIntervalMs,
                heartbeatEnabled = settings.heartbeatEnabled,
                heartbeatTimeoutMs = settings.heartbeatTimeoutMs,
                maxRetryAttempts = settings.maxRetryAttempts,
                retryBackoffMs = settings.retryBackoffMs,
                pipelineTimeoutMs = settings.pipelineTimeoutMs,
                phaseTimeoutMs = settings.phaseTimeoutMs,
                loggingEnabled = settings.loggingEnabled,
                logLevel = settings.logLevel,
                metricsEnabled = settings.metricsEnabled,
                backupEnabled = settings.backupEnabled,
                backupPath = settings.backupPath,
                stationId = settings.stationId,
                stationName = settings.stationName,
                language = settings.language,
                themeMode = settings.themeMode
            )
        )
    }

    override suspend fun reset() {
        dao.reset(ApplicationSettingsEntity())
    }
}
'''

# خريطة الملفات: (المسار النسبي من BASE_PATH، المحتوى)
FILES = [
    ("settings/data/entity/ApplicationSettingsEntity.kt", ENTITY_CONTENT),
    ("settings/data/dao/ApplicationSettingsDao.kt", DAO_CONTENT),
    ("settings/domain/model/ApplicationSettings.kt", DOMAIN_MODEL_CONTENT),
    ("settings/data/mapper/ApplicationSettingsMapper.kt", MAPPER_CONTENT),
    ("settings/domain/repository/SettingsRepository.kt", REPOSITORY_INTERFACE_CONTENT),
    ("settings/data/repository/SettingsRepositoryImpl.kt", REPOSITORY_IMPL_CONTENT),
]

def create_files():
    # إنشاء المجلدات والملفات
    for rel_path, content in FILES:
        full_path = os.path.join(BASE_PATH, rel_path)
        dir_name = os.path.dirname(full_path)
        if dir_name and not os.path.exists(dir_name):
            os.makedirs(dir_name, exist_ok=True)
            print(f"✅ تم إنشاء المجلد: {dir_name}")

        # كتابة الملف (مع تجاهل إذا كان موجوداً)
        with open(full_path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"✅ تم إنشاء الملف: {full_path}")

if __name__ == "__main__":
    # التحقق من وجود المسار الأساسي
    if not os.path.exists(BASE_PATH):
        print(f"⚠️  المجلد الأساسي غير موجود: {BASE_PATH}")
        print("سيتم إنشاؤه تلقائياً.")
        os.makedirs(BASE_PATH, exist_ok=True)

    create_files()
    print("\n🎉 تم إنشاء جميع ملفات طبقة البيانات والـ Domain الخاصة بالإعدادات بنجاح.")