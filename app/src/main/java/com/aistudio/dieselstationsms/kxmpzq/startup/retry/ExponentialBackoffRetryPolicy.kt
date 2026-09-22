package com.aistudio.dieselstationsms.kxmpzq.startup.retry

class ExponentialBackoffRetryPolicy(
    override val maxAttempts: Int,
    override val backoffMs: Long
) : RetryPolicy {
    override fun shouldRetry(attempt: Int, error: Throwable): Boolean = attempt < maxAttempts
    override fun shouldRetryResult(attempt: Int, error: String): Boolean = attempt < maxAttempts
    override fun getDelayMs(attempt: Int): Long = backoffMs * (1 shl attempt.coerceAtMost(10))
}
