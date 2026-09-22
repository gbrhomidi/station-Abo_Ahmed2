package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlin.coroutines.cancellation.CancellationException

class CancellationToken {
    @Volatile private var isCancelled = false
    fun cancel() { isCancelled = true }
    fun isCancelled(): Boolean = isCancelled
    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("Startup cancelled")
    }
}
