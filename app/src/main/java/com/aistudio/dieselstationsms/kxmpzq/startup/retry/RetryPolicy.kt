package com.aistudio.dieselstationsms.kxmpzq.startup.retry

interface RetryPolicy {
    val maxAttempts: Int
    val backoffMs: Long
    fun shouldRetry(attempt: Int, error: Throwable): Boolean
    fun shouldRetryResult(attempt: Int, error: String): Boolean
    fun getDelayMs(attempt: Int): Long
}
