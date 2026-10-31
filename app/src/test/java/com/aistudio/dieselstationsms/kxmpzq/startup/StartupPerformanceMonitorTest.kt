package com.aistudio.dieselstationsms.kxmpzq.startup

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupPerformanceMonitorTest {

    @Test
    fun snapshotContainsCompletedSpanAndDiagnosticSections() {
        val application: Application = RuntimeEnvironment.getApplication()
        StartupPerformanceMonitor.initialize(application)

        val span = StartupPerformanceMonitor.beginSpan("test.performance.monitor.snapshot")
        StartupPerformanceMonitor.endSpan(span)

        StartupPerformanceMonitor.recordWebPerformanceMetrics(
            """{"page":"fuel-sales.html","domContentLoadedMs":120.5,"loadEventMs":145.0,"longTaskCount":2,"longTaskMaximumMs":72.0,"interactionMaximumMs":84.0}"""
        )

        val report = JSONObject(StartupPerformanceMonitor.snapshotJson())
        assertEquals(1, report.getInt("schemaVersion"))
        assertTrue(report.has("events"))
        assertTrue(report.has("frames"))
        assertTrue(report.has("memory"))

        val metric = report.getJSONObject("aggregates")
            .getJSONObject("test.performance.monitor.snapshot")
        assertEquals(1L, metric.getLong("count"))
        assertTrue(metric.getDouble("maximumMs") >= 0.0)

        val events = report.getJSONArray("events")
        val webMetricsFound = (0 until events.length()).any { index ->
            val event = events.getJSONObject(index)
            event.optString("type") == "web_metrics" &&
                event.optString("name") == "webview.metrics.fuel-sales.html"
        }
        assertTrue(webMetricsFound)
    }
}
