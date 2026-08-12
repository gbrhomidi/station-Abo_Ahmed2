package com.aistudio.dieselstationsms.kxmpzq.startup.metrics

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory metrics collector used by the startup subsystem.
 *
 * The collector stores the latest value for each metric key.
 * It is intentionally non-persistent: all metrics are lost when the
 * collector instance is discarded or [clear] is called.
 */
class InMemoryMetricsCollector : MetricsCollector {

    private val metrics = ConcurrentHashMap<String, Any>()

    /**
     * Records or replaces the current value of a metric.
     *
     * If the same key is recorded more than once, the newest value
     * replaces the previous value.
     */
    override fun record(key: String, value: Any) {
        val normalizedKey = key.trim()

        if (normalizedKey.isEmpty()) {
            return
        }

        metrics[normalizedKey] = value
    }

    /**
     * Returns a stable snapshot of the currently recorded metrics.
     *
     * Changes made to the collector after this call do not modify
     * the returned map.
     */
    override fun getMetrics(): Map<String, Any> {
        return metrics.toMap()
    }

    /**
     * Removes all currently recorded metrics.
     */
    override fun clear() {
        metrics.clear()
    }
}