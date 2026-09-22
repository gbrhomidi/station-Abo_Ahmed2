package com.aistudio.dieselstationsms.kxmpzq.settings.maintenance

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.settings.backup.SettingsBackupManager
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** تنفيذ عمليات الصيانة على DatabaseHelper والمخزن الفعلي للإعدادات. */
class SettingsMaintenanceRepositoryImpl(
    private val repository: SettingsRepository,
    private val database: DatabaseHelper,
    private val backupManager: SettingsBackupManager
) : SettingsMaintenanceRepository {

    override suspend fun createBackup(): String = backupManager.createBackup()

    override suspend fun restoreBackup(data: String) {
        backupManager.restoreBackup(data)
    }

    override suspend fun clearLogs() {
        withContext(Dispatchers.IO) {
            val retentionDays = repository.getSettings().keepLogsDays.coerceIn(1, 3650)
            database.cleanupActivityLogs(retentionDays)
        }
    }

    override suspend fun optimizeDatabase() = withContext(Dispatchers.IO) {
        check(database.checkIntegrity()) { "فشل فحص سلامة قاعدة البيانات قبل التحسين" }
        database.vacuumDatabase()
    }

    override suspend fun factoryReset() {
        // Factory reset هنا يخص إعدادات module فقط، ولا يحذف بيانات المبيعات
        // أو المستخدمين أو أي جدول أعمال من DatabaseHelper.
        repository.resetSettings()
    }
}
