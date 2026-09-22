package com.aistudio.dieselstationsms.kxmpzq.startup

sealed class ServiceLaunchResult {
    data class Success(val message: String? = null) : ServiceLaunchResult()
    data class AlreadyRunning(val message: String? = null) : ServiceLaunchResult()
    data class Failure(val error: String, val retryable: Boolean = true) : ServiceLaunchResult()
}

interface ServiceLauncher {
    fun launch(reason: StartupReason): ServiceLaunchResult
}
