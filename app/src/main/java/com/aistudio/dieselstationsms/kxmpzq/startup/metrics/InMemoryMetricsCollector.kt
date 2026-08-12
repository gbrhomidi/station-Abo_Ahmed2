package com.aistudio.dieselstationsms.kxmpzq.startup.metrics

import java.util.concurrent.ConcurrentHashMap

class InMemoryMetricsCollector : MetricsCollector {
    private val metrics = ConcurrentHashMap<String, Any>()
    override fun record(key: String, value: Any) { metrics[key] = value }
    override fun getMetrics(): Map<String, Any> = metrics.toMap()
    override fun clear() = metrics.clear()
}
