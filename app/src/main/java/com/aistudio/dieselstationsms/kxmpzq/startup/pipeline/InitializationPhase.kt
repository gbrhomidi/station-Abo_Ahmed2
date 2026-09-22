package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext

interface InitializationPhase {
    val name: String
    val isCritical: Boolean get() = true
    val timeoutMs: Long get() = 30_000L
    val dependencies: List<String> get() = emptyList()
    suspend fun execute(ctx: InitializationContext): PhaseResult
}

sealed class PhaseResult {
    data class Success(val message: String? = null) : PhaseResult()
    data class Failure(val error: String, val retryable: Boolean = true) : PhaseResult()
    data class Skipped(val reason: String) : PhaseResult()
}
