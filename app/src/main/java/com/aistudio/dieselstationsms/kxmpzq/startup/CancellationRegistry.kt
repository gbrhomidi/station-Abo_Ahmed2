package com.aistudio.dieselstationsms.kxmpzq.startup

import java.util.concurrent.ConcurrentHashMap

class CancellationRegistry {
    private val tokens = ConcurrentHashMap<String, CancellationToken>()

    fun register(correlationId: String, token: CancellationToken) {
        tokens[correlationId] = token
    }

    fun cancel(correlationId: String): Boolean {
        return tokens[correlationId]?.let {
            it.cancel()
            tokens.remove(correlationId)
            true
        } ?: false
    }

    fun remove(correlationId: String) { tokens.remove(correlationId) }
    fun cancelAll() { tokens.values.forEach { it.cancel() }; tokens.clear() }
    fun activeCount(): Int = tokens.size
}
