package com.aistudio.dieselstationsms.kxmpzq.startup.metrics

/**
 * Abstraction for collecting runtime metrics produced by the
 * application startup subsystem.
 *
 * Implementations may store metrics in memory, persist them locally,
 * or forward them to another monitoring system.
 */
interface MetricsCollector {

    /**
     * Records or updates a metric value.
     *
     * Implementations should define how repeated records using the
     * same key are handled.
     *
     * @param key metric identifier
     * @param value metric value
     */
    fun record(
        key: String,
        value: Any
    )

    /**
     * Returns a snapshot of all currently available metrics.
     *
     * The returned map should be treated as read-only by callers.
     */
    fun getMetrics(): Map<String, Any>

    /**
     * Removes all currently collected metrics.
     */
    fun clear()
}