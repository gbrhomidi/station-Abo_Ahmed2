package com.aistudio.dieselstationsms.kxmpzq.settings.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.settings.backup.JsonSettingsBackupManager
import com.aistudio.dieselstationsms.kxmpzq.settings.backup.SettingsBackupManager
import com.aistudio.dieselstationsms.kxmpzq.settings.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.settings.config.DynamicConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.settings.maintenance.SettingsMaintenanceRepository
import com.aistudio.dieselstationsms.kxmpzq.settings.maintenance.SettingsMaintenanceRepositoryImpl
import com.aistudio.dieselstationsms.kxmpzq.settings.maintenance.SettingsMaintenanceViewModel
import com.aistudio.dieselstationsms.kxmpzq.settings.monitoring.MonitoringViewModel
import com.aistudio.dieselstationsms.kxmpzq.settings.monitoring.SettingsMonitoringRepository
import com.aistudio.dieselstationsms.kxmpzq.settings.monitoring.SettingsMonitoringRepositoryImpl
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepositoryImpl
import com.aistudio.dieselstationsms.kxmpzq.settings.security.AndroidKeystoreEncryption
import com.aistudio.dieselstationsms.kxmpzq.settings.security.SettingsEncryption
import com.aistudio.dieselstationsms.kxmpzq.settings.storage.SettingsStorage
import com.aistudio.dieselstationsms.kxmpzq.settings.storage.SharedPreferencesSettingsStorage
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsViewModel

/** Manual DI متوافق مع بنية التطبيق الحالية. */
class SettingsModule(context: Context) {
    private val appContext = context.applicationContext

    val database: DatabaseHelper by lazy { DatabaseHelper.getInstance(appContext) }

    val settingsStorage: SettingsStorage by lazy {
        SharedPreferencesSettingsStorage(appContext, database)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(settingsStorage)
    }

    val configurationProvider: ConfigurationProvider by lazy {
        DynamicConfigurationProvider(settingsRepository)
    }

    val settingsEncryption: SettingsEncryption by lazy {
        AndroidKeystoreEncryption()
    }

    val backupManager: SettingsBackupManager by lazy {
        JsonSettingsBackupManager(appContext, settingsRepository, settingsEncryption)
    }

    val monitoringRepository: SettingsMonitoringRepository by lazy {
        SettingsMonitoringRepositoryImpl(appContext, database)
    }

    val monitoringViewModel: MonitoringViewModel by lazy {
        MonitoringViewModel(monitoringRepository)
    }

    val maintenanceRepository: SettingsMaintenanceRepository by lazy {
        SettingsMaintenanceRepositoryImpl(settingsRepository, database, backupManager)
    }

    val maintenanceViewModel: SettingsMaintenanceViewModel by lazy {
        SettingsMaintenanceViewModel(maintenanceRepository)
    }

    val settingsViewModelFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ApplicationSettingsViewModel::class.java) ->
                    ApplicationSettingsViewModel(settingsRepository) as T
                modelClass.isAssignableFrom(MonitoringViewModel::class.java) ->
                    MonitoringViewModel(monitoringRepository) as T
                modelClass.isAssignableFrom(SettingsMaintenanceViewModel::class.java) ->
                    SettingsMaintenanceViewModel(maintenanceRepository) as T
                else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
            }
        }
    }
}
