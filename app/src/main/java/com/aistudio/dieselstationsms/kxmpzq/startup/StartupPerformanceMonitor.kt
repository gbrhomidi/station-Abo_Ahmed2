package com.aistudio.dieselstationsms.kxmpzq.startup

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight process-local performance instrumentation.
 *
 * Measurements use elapsedRealtimeNanos (monotonic time), never wall-clock time.
 * Reports are written asynchronously to private app storage and are bounded.
 * No user, customer, financial, SQL, URL-query, or message content is collected.
 */
object StartupPerformanceMonitor {
    private const val TAG = "AppPerformance"
    private const val MAX_EVENTS = 240
    private const val MAX_AGGREGATES = 80
    private const val MAX_REPORT_FILES = 8

    private val processStartedNanos = SystemClock.elapsedRealtimeNanos()
    private val sessionId = java.lang.Long.toString(System.currentTimeMillis(), 36) +
        "-" + Process.myPid()
    private val cookieGenerator = AtomicInteger(1)
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PerformanceReportWriter").apply { isDaemon = true }
    }

    private val lock = Any()
    private val events = ArrayDeque<JSONObject>()
    private val aggregates = LinkedHashMap<String, Aggregate>()
    private val counters = LinkedHashMap<String, Long>()
    @Volatile private var appContext: Context? = null

    private data class Aggregate(
        var count: Long = 0,
        var totalNanos: Long = 0,
        var minNanos: Long = Long.MAX_VALUE,
        var maxNanos: Long = 0,
        var failures: Long = 0
    )

    class Span internal constructor(
        internal val name: String,
        internal val startedNanos: Long,
        internal val traceCookie: Int
    ) {
        internal val completed = AtomicBoolean(false)
    }

    private val frameLock = Any()
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var attachedWindow: Window? = null
    private var frameBudgetNanos = 16_666_667L
    private var frameCount = 0L
    private var slowFrameCount = 0L
    private var verySlowFrameCount = 0L
    private var droppedFrameCount = 0L
    private var totalFrameNanos = 0L
    private var maxFrameNanos = 0L
    private val framePhaseTotals = LinkedHashMap<String, Long>()

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        recordFrame(metrics, dropped)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        mark("application.onCreate")
    }

    fun mark(name: String, attributes: Map<String, String> = emptyMap()) {
        recordEvent("instant", name, 0L, true, attributes)
    }

    fun increment(name: String, amount: Long = 1L) {
        if (amount <= 0L) return
        synchronized(lock) {
            counters[name] = (counters[name] ?: 0L) + amount
            while (counters.size > 50) counters.remove(counters.keys.first())
        }
    }

    fun beginSpan(name: String): Span {
        val normalizedName = normalizeName(name)
        val cookie = cookieGenerator.getAndIncrement()
        val now = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Trace.beginAsyncSection(normalizedName, cookie) }
        }
        recordEvent("begin", normalizedName, 0L, true, emptyMap())
        return Span(normalizedName, now, cookie)
    }

    fun endSpan(
        span: Span?,
        success: Boolean = true,
        attributes: Map<String, String> = emptyMap()
    ) {
        if (span == null || !span.completed.compareAndSet(false, true)) return
        val duration = (SystemClock.elapsedRealtimeNanos() - span.startedNanos).coerceAtLeast(0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Trace.endAsyncSection(span.name, span.traceCookie) }
        }
        synchronized(lock) {
            val aggregate = aggregates.getOrPut(span.name) { Aggregate() }
            aggregate.count++
            aggregate.totalNanos += duration
            aggregate.minNanos = minOf(aggregate.minNanos, duration)
            aggregate.maxNanos = maxOf(aggregate.maxNanos, duration)
            if (!success) aggregate.failures++
            while (aggregates.size > MAX_AGGREGATES) aggregates.remove(aggregates.keys.first())
        }
        recordEvent("duration", span.name, duration, success, attributes)
        Log.d(TAG, "${span.name}=${duration / 1_000_000.0}ms success=$success")
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        val span = beginSpan(name)
        var success = false
        try {
            return block().also { success = true }
        } finally {
            endSpan(span, success)
        }
    }

    suspend fun <T> measureSuspending(name: String, block: suspend () -> T): T {
        val span = beginSpan(name)
        var success = false
        try {
            return block().also { success = true }
        } finally {
            endSpan(span, success)
        }
    }

    fun attachWindow(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        synchronized(frameLock) {
            if (attachedWindow === window) return
            attachedWindow?.removeOnFrameMetricsAvailableListener(frameListener)
            attachedWindow = window
            val refreshRate = runCatching {
                window.decorView.display?.refreshRate?.takeIf { it > 1f } ?: 60f
            }.getOrDefault(60f)
            frameBudgetNanos = (1_000_000_000.0 / refreshRate).toLong().coerceAtLeast(8_000_000L)
            if (frameThread == null) {
                frameThread = HandlerThread("AppPerformanceFrames").apply { start() }
                frameHandler = Handler(frameThread!!.looper)
            }
            runCatching { window.addOnFrameMetricsAvailableListener(frameListener, frameHandler) }
                .onFailure { Log.w(TAG, "Frame metrics listener could not be attached", it) }
        }
        mark("activity.frame_metrics_attached", mapOf("target_fps" to
            (1_000_000_000L / frameBudgetNanos).toString()))
    }

    fun detachWindow(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        synchronized(frameLock) {
            if (attachedWindow !== window) return
            runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) }
            attachedWindow = null
            frameThread?.quitSafely()
            frameThread = null
            frameHandler = null
        }
    }

    private fun recordFrame(metrics: FrameMetrics, dropped: Int) {
        val total = runCatching { metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
            .getOrDefault(0L)
        if (total <= 0L) return
        synchronized(frameLock) {
            frameCount++
            totalFrameNanos += total
            maxFrameNanos = maxOf(maxFrameNanos, total)
            droppedFrameCount += dropped.coerceAtLeast(0)
            if (total > frameBudgetNanos) slowFrameCount++
            if (total > frameBudgetNanos * 2L) verySlowFrameCount++
            addFramePhase("inputHandling", metrics, FrameMetrics.INPUT_HANDLING_DURATION)
            addFramePhase("animation", metrics, FrameMetrics.ANIMATION_DURATION)
            addFramePhase("layoutMeasure", metrics, FrameMetrics.LAYOUT_MEASURE_DURATION)
            addFramePhase("draw", metrics, FrameMetrics.DRAW_DURATION)
            addFramePhase("sync", metrics, FrameMetrics.SYNC_DURATION)
            addFramePhase("commandIssue", metrics, FrameMetrics.COMMAND_ISSUE_DURATION)
            addFramePhase("swapBuffers", metrics, FrameMetrics.SWAP_BUFFERS_DURATION)
            addFramePhase("gpu", metrics, FrameMetrics.GPU_DURATION)
        }
    }

    private fun addFramePhase(name: String, metrics: FrameMetrics, metric: Int) {
        val value = runCatching { metrics.getMetric(metric) }.getOrDefault(0L)
        if (value > 0L) framePhaseTotals[name] = (framePhaseTotals[name] ?: 0L) + value
    }

    private fun recordEvent(
        type: String,
        name: String,
        durationNanos: Long,
        success: Boolean,
        attributes: Map<String, String>
    ) {
        val event = JSONObject()
            .put("type", type)
            .put("name", normalizeName(name))
            .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - processStartedNanos) / 1_000_000L)
            .put("durationMs", durationNanos / 1_000_000.0)
            .put("success", success)
            .put("thread", Thread.currentThread().name.take(48))
        if (attributes.isNotEmpty()) {
            val details = JSONObject()
            attributes.entries.take(8).forEach { (key, value) ->
                details.put(normalizeName(key), value.take(96))
            }
            event.put("details", details)
        }
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(event)
        }
    }

    private fun normalizeName(value: String): String =
        value.filter { it.isLetterOrDigit() || it in "._-" }.take(112).ifBlank { "unnamed" }

    fun flushReport(reason: String) {
        val context = appContext ?: return
        mark("performance.report_requested", mapOf("reason" to reason.take(48)))
        ioExecutor.execute {
            runCatching {
                val report = buildReport(context, reason)
                logSummary(report, reason)
                val directory = File(context.filesDir, "diagnostics/performance")
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException("Could not create performance diagnostics directory")
                }
                val target = File(directory, "startup-performance-$sessionId.json")
                val temporary = File(directory, target.name + ".tmp")
                temporary.writeText(report.toString(2), Charsets.UTF_8)
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("Could not replace previous performance report")
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                directory.listFiles { file ->
                    file.isFile && file.name.startsWith("startup-performance-") &&
                        file.name.endsWith(".json")
                }?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_REPORT_FILES)
                    ?.forEach { it.delete() }
                Log.i(TAG, "Performance report updated: ${target.name}; reason=$reason")
            }.onFailure { Log.w(TAG, "Could not persist performance report", it) }
        }
    }

    private fun logSummary(report: JSONObject, reason: String) {
        val aggregates = report.optJSONObject("aggregates") ?: JSONObject()
        val names = mutableListOf<String>()
        val iterator = aggregates.keys()
        while (iterator.hasNext()) names += iterator.next()
        Log.i(
            TAG,
            "SUMMARY reason=$reason session=$sessionId processElapsedMs=${report.optLong("processElapsedMs")} " +
                "spans=${names.size} events=${report.optJSONArray("events")?.length() ?: 0}"
        )
        names.forEach { name ->
            val metric = aggregates.optJSONObject(name) ?: return@forEach
            Log.i(
                TAG,
                "METRIC name=$name count=${metric.optLong("count")} " +
                    "avgMs=${metric.optDouble("averageMs")} maxMs=${metric.optDouble("maximumMs")} " +
                    "failures=${metric.optLong("failures")}"
            )
        }
        val frames = report.optJSONObject("frames") ?: JSONObject()
        Log.i(
            TAG,
            "FRAMES targetMs=${frames.optDouble("targetFrameMs")} count=${frames.optLong("frames")} " +
                "slow=${frames.optLong("slowFrames")} verySlow=${frames.optLong("verySlowFrames")} " +
                "dropped=${frames.optLong("droppedFrames")} avgMs=${frames.optDouble("averageFrameMs")} " +
                "maxMs=${frames.optDouble("maximumFrameMs")}"
        )
        val memory = report.optJSONObject("memory") ?: JSONObject()
        Log.i(
            TAG,
            "MEMORY pssMb=${memory.optDouble("pssMb")} javaUsedMb=${memory.optDouble("javaHeapUsedMb")} " +
                "nativeAllocatedMb=${memory.optDouble("nativeHeapAllocatedMb")} " +
                "deviceAvailableMb=${memory.optDouble("deviceAvailableMb")}"
        )
    }

    private fun buildReport(context: Context, reason: String): JSONObject {
        val eventSnapshot: List<JSONObject>
        val aggregateSnapshot: Map<String, Aggregate>
        val counterSnapshot: Map<String, Long>
        synchronized(lock) {
            eventSnapshot = events.map { JSONObject(it.toString()) }
            aggregateSnapshot = aggregates.mapValues { (_, value) -> value.copy() }
            counterSnapshot = counters.toMap()
        }

        val runtime = Runtime.getRuntime()
        val memory = JSONObject()
            .put("javaHeapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576.0)
            .put("javaHeapAllocatedMb", runtime.totalMemory() / 1_048_576.0)
            .put("javaHeapMaxMb", runtime.maxMemory() / 1_048_576.0)
            .put("nativeHeapAllocatedMb", Debug.getNativeHeapAllocatedSize() / 1_048_576.0)
            .put("pssMb", Debug.getPss() / 1024.0)
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            if (manager != null) {
                memory.put("deviceAvailableMb", info.availMem / 1_048_576.0)
                memory.put("deviceTotalMb", info.totalMem / 1_048_576.0)
                memory.put("deviceLowMemory", info.lowMemory)
            }
        }

        val aggregateJson = JSONObject()
        aggregateSnapshot.forEach { (name, item) ->
            aggregateJson.put(name, JSONObject()
                .put("count", item.count)
                .put("totalMs", item.totalNanos / 1_000_000.0)
                .put("averageMs", if (item.count == 0L) 0.0 else item.totalNanos / item.count / 1_000_000.0)
                .put("minimumMs", if (item.minNanos == Long.MAX_VALUE) 0.0 else item.minNanos / 1_000_000.0)
                .put("maximumMs", item.maxNanos / 1_000_000.0)
                .put("failures", item.failures))
        }
        val frameJson = synchronized(frameLock) {
            JSONObject()
                .put("targetFrameMs", frameBudgetNanos / 1_000_000.0)
                .put("frames", frameCount)
                .put("slowFrames", slowFrameCount)
                .put("verySlowFrames", verySlowFrameCount)
                .put("droppedFrames", droppedFrameCount)
                .put("averageFrameMs", if (frameCount == 0L) 0.0 else totalFrameNanos / frameCount / 1_000_000.0)
                .put("maximumFrameMs", maxFrameNanos / 1_000_000.0)
                .put("phaseTotalsMs", JSONObject().also { phases ->
                    framePhaseTotals.forEach { (key, value) -> phases.put(key, value / 1_000_000.0) }
                })
        }
        val counterJson = JSONObject()
        counterSnapshot.forEach { (key, value) -> counterJson.put(key, value) }
        val appInfo = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            JSONObject().put("package", context.packageName)
                .put("versionName", info.versionName ?: "unknown")
                .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
        }.getOrElse { JSONObject().put("package", context.packageName) }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("sessionId", sessionId)
            .put("reason", reason.take(64))
            .put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            .put("processElapsedMs", (SystemClock.elapsedRealtimeNanos() - processStartedNanos) / 1_000_000L)
            .put("app", appInfo)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("brand", Build.BRAND)
                .put("model", Build.MODEL)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE))
            .put("memory", memory)
            .put("frames", frameJson)
            .put("counters", counterJson)
            .put("aggregates", aggregateJson)
            .put("events", JSONArray().also { array -> eventSnapshot.forEach { array.put(it) } })
    }
}
