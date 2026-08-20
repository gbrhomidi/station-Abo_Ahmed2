package com.aistudio.dieselstationsms.kxmpzq.startup.metrics

interface MetricsCollector {
    fun record(key: String, value: Any)
    fun getMetrics(): Map<String, Any>
    fun clear()
}
