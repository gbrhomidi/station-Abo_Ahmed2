import os

# المسار المطلق الذي حددته
BASE_PATH = "/storage/emulated/0/Download/station-Abo_Ahmed-akeer/Fuel2_stations2/app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/settings"

# محتويات الملفات (بدون بادئة settings/ لأن BASE_PATH يشملها)
FILES = [
    {
        "path": "presentation/ApplicationSettingsContract.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.presentation

object ApplicationSettingsContract {
    sealed interface Action {
        data object LoadSettings : Action
        data class ToggleAutoStart(val enabled: Boolean) : Action
        data class UpdateBootDelay(val delayMs: Long) : Action
        data class ToggleSmsService(val enabled: Boolean) : Action
        data object RestartSmsService : Action
        data object StopSmsService : Action
        data object StartSmsService : Action
        data object RunHealthCheck : Action
        data object BackupDatabase : Action
        data object RestoreDatabase : Action
        data object ResetSettings : Action
        data class UpdateStationInfo(val stationId: String, val stationName: String) : Action
        data class ChangeTheme(val mode: String) : Action
        data object RefreshMetrics : Action
        data object ClearMetrics : Action
    }
}
'''
    },
    {
        "path": "presentation/ApplicationSettingsState.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.presentation

import com.aistudio.dieselstationsms.kxmpzq.settings.domain.model.ApplicationSettings

data class ApplicationSettingsState(
    val isLoading: Boolean = false,
    val settings: ApplicationSettings? = null,
    val serviceRunning: Boolean = false,
    val currentStartupState: String = "IDLE",
    val currentPhase: String = "",
    val healthStatus: String = "UNKNOWN",
    val heartbeatAlive: Boolean = false,
    val metrics: Map<String, Any> = emptyMap(),
    val errorMessage: String? = null,
    val isSaving: Boolean = false
)
'''
    },
    {
        "path": "presentation/ApplicationSettingsEffect.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.presentation

sealed interface ApplicationSettingsEffect {
    data class ShowMessage(val message: String) : ApplicationSettingsEffect
    data object OpenBackupPicker : ApplicationSettingsEffect
    data object OpenRestorePicker : ApplicationSettingsEffect
    data object RestartCompleted : ApplicationSettingsEffect
    data class Error(val message: String) : ApplicationSettingsEffect
}
'''
    },
    {
        "path": "presentation/ApplicationSettingsViewModel.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.dieselstationsms.kxmpzq.settings.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ApplicationSettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ApplicationSettingsState())
    val state: StateFlow<ApplicationSettingsState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<ApplicationSettingsEffect>()
    val effect: SharedFlow<ApplicationSettingsEffect> = _effect.asSharedFlow()

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            repository.observe()
                .collect { settings ->
                    _state.update {
                        it.copy(
                            settings = settings,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun onAction(action: ApplicationSettingsContract.Action) {
        when (action) {
            is ApplicationSettingsContract.Action.LoadSettings -> load()
            is ApplicationSettingsContract.Action.ResetSettings -> reset()
            // باقي الأحداث ستُربط مع UseCases لاحقاً
            else -> {
                // مؤقتاً: يمكن إضافة معالجة بسيطة
            }
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true) }
    }

    private fun reset() {
        viewModelScope.launch {
            try {
                repository.reset()
                _effect.emit(ApplicationSettingsEffect.ShowMessage("تمت إعادة الإعدادات الافتراضية"))
            } catch (e: Exception) {
                _effect.emit(ApplicationSettingsEffect.Error(e.message ?: "Unknown error"))
            }
        }
    }
}
'''
    },
    # UseCases
    {
        "path": "domain/usecase/GetApplicationSettingsUseCase.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.usecase

import com.aistudio.dieselstationsms.kxmpzq.settings.domain.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

class GetApplicationSettingsUseCase(
    private val repository: SettingsRepository
) {
    operator fun invoke(): Flow<ApplicationSettings> = repository.observe()
}
'''
    },
    {
        "path": "domain/usecase/SaveApplicationSettingsUseCase.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.usecase

import com.aistudio.dieselstationsms.kxmpzq.settings.domain.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.domain.repository.SettingsRepository

class SaveApplicationSettingsUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(settings: ApplicationSettings) {
        repository.save(settings)
    }
}
'''
    },
    {
        "path": "domain/usecase/ResetApplicationSettingsUseCase.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.usecase

import com.aistudio.dieselstationsms.kxmpzq.settings.domain.repository.SettingsRepository

class ResetApplicationSettingsUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke() {
        repository.reset()
    }
}
'''
    },
    {
        "path": "domain/usecase/RestartSmsServiceUseCase.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.usecase

// هذا الـ UseCase يحتاج إلى تفاعل مع SMSService
// سنفترض وجود واجهة SmsServiceManager يمكن حقنها
interface SmsServiceManager {
    suspend fun restart()
}

class RestartSmsServiceUseCase(
    private val smsServiceManager: SmsServiceManager
) {
    suspend operator fun invoke() {
        smsServiceManager.restart()
    }
}
'''
    },
    {
        "path": "domain/usecase/StopSmsServiceUseCase.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.usecase

interface SmsServiceManager {
    suspend fun stop()
}

class StopSmsServiceUseCase(
    private val smsServiceManager: SmsServiceManager
) {
    suspend operator fun invoke() {
        smsServiceManager.stop()
    }
}
'''
    },
    {
        "path": "domain/usecase/RunHealthCheckUseCase.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.usecase

interface HealthMonitor {
    suspend fun runCheck(): Boolean
}

class RunHealthCheckUseCase(
    private val healthMonitor: HealthMonitor
) {
    suspend operator fun invoke(): Boolean = healthMonitor.runCheck()
}
'''
    },
    {
        "path": "domain/usecase/BackupDatabaseUseCase.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.usecase

interface DatabaseBackup {
    suspend fun backup(): Result<String>
}

class BackupDatabaseUseCase(
    private val databaseBackup: DatabaseBackup
) {
    suspend operator fun invoke(): Result<String> = databaseBackup.backup()
}
'''
    },
    {
        "path": "domain/usecase/RestoreDatabaseUseCase.kt",
        "content": '''package com.aistudio.dieselstationsms.kxmpzq.settings.domain.usecase

interface DatabaseBackup {
    suspend fun restore(): Result<Unit>
}

class RestoreDatabaseUseCase(
    private val databaseBackup: DatabaseBackup
) {
    suspend operator fun invoke(): Result<Unit> = databaseBackup.restore()
}
'''
    },
]

def create_files():
    # التأكد من وجود المسار الأساسي
    if not os.path.exists(BASE_PATH):
        print(f"⚠️ المسار الأساسي غير موجود: {BASE_PATH}")
        print("سيتم إنشاؤه.")
        os.makedirs(BASE_PATH, exist_ok=True)

    for file in FILES:
        full_path = os.path.join(BASE_PATH, file["path"])
        dir_name = os.path.dirname(full_path)
        if dir_name and not os.path.exists(dir_name):
            os.makedirs(dir_name, exist_ok=True)
            print(f"✅ تم إنشاء المجلد: {dir_name}")

        # كتابة الملف (استبدال إذا كان موجوداً)
        with open(full_path, 'w', encoding='utf-8') as f:
            f.write(file["content"])
        print(f"✅ تم إنشاء/تحديث الملف: {full_path}")

if __name__ == "__main__":
    create_files()
    print("\n🎉 تمت إضافة جميع ملفات Presentation Layer و UseCases بنجاح في المسار المحدد.")