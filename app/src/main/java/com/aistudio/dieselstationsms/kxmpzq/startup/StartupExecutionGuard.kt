package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StartupExecutionGuard {
    private val mutex = Mutex()

    suspend fun <T> execute(policy: StartupPolicy, block: suspend () -> T): T {
        return if (policy.allowParallelExecution) block()
        else mutex.withLock { block() }
    }
}
