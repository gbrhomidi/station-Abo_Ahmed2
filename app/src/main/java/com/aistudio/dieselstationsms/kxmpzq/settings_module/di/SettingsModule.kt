package com.aistudio.dieselstationsms.kxmpzq.settings.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aistudio.dieselstationsms.kxmpzq.settings.config.ConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.settings.config.DynamicConfigurationProvider
import com.aistudio.dieselstationsms.kxmpzq.settings.maintenance.*
import com.aistudio.dieselstationsms.kxmpzq.settings.monitoring.*
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepositoryImpl
import com.aistudio.dieselstationsms.kxmpzq.settings.storage.SettingsStorage
import com.aistudio.dieselstationsms.kxmpzq.settings.storage.SharedPreferencesSettingsStorage
import com.aistudio.dieselstationsms.kxmpzq.settings.viewmodel.ApplicationSettingsViewModel

/**
 * وحدة DI لنظام الإعدادات
 * Manual DI — قابل للتحويل إلى Hilt لاحقاً
 */
class SettingsModule(context: Context) {

    // ── Storage ───────────────────────────────────────────
    val settingsStorage: SettingsStorage by lazy {
        SharedPreferencesSettingsStorage(context)
    }

    // ── Repository ──────────────────────────────────────
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(settingsStorage)
    }

    // ── Configuration ───────────────────────────────────
    val configurationProvider: ConfigurationProvider by lazy {
        DynamicConfigurationProvider(settingsRepository)
    }

    // ── Monitoring ──────────────────────────────────────
    val monitoringRepository: SettingsMonitoringRepository by lazy {
        SettingsMonitoringRepositoryImpl()
    }

    val monitoringViewModel: MonitoringViewModel by lazy {
        MonitoringViewModel(monitoringRepository)
    }

    // ── Maintenance ─────────────────────────────────────
    val maintenanceRepository: SettingsMaintenanceRepository by lazy {
        SettingsMaintenanceRepositoryImpl(settingsRepository)
    }

    val maintenanceViewModel: SettingsMaintenanceViewModel by lazy {
        SettingsMaintenanceViewModel(maintenanceRepository)
    }

    // ── Settings ViewModel Factory ─────────────────────
    val settingsViewModelFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
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
}
