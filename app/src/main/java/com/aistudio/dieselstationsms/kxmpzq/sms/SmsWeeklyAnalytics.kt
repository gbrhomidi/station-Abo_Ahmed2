package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** تحليلات آخر سبعة أيام من سجلات SQLite الفعلية فقط. */
class SmsWeeklyAnalytics(private val db: DatabaseHelper) {
    fun build(days: Int = 7): JSONObject {
        val safeDays = days.coerceIn(1, 31)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(safeDays - 1))
        }
        val startAt = calendar.timeInMillis
        val daily = JSONArray()
        var totalReceived = 0
        var totalProcessed = 0
        var totalMetricFailures = 0
        var totalQueued = 0
        var totalSent = 0
        var totalDelivered = 0
        var totalOutboxFailed = 0
        val latencySamples = mutableListOf<Long>()

        repeat(safeDays) {
            val dayStart = calendar.timeInMillis
            val dayEnd = dayStart + 86_400_000L
            val date = dateFormat.format(Date(dayStart))
            val received = metricCount(date, "SMS_RECEIVED")
            val processed = metricCount(date, "SMS_PROCESSED")
            val metricFailures = metricCount(date, "SMS_FAILED")
            val queued = outboxCount(dayStart, dayEnd, null)
            val sent = outboxCount(dayStart, dayEnd, "SENT") + outboxCount(dayStart, dayEnd, "DELIVERY_PENDING") + outboxCount(dayStart, dayEnd, "DELIVERED")
            val delivered = outboxCount(dayStart, dayEnd, "DELIVERED")
            val failed = outboxCount(dayStart, dayEnd, "FAILED") + outboxCount(dayStart, dayEnd, "RETRY_PENDING")
            val latencies = outboxLatencies(dayStart, dayEnd)
            latencySamples += latencies
            daily.put(JSONObject().apply {
                put("date", date)
                put("received", received)
                put("processed", processed)
                put("metric_failures", metricFailures)
                put("queued", queued)
                put("sent", sent)
                put("delivered", delivered)
                put("failed", failed)
                put("delivery_rate", if (sent > 0) delivered.toDouble() / sent else 0.0)
                put("avg_queue_ms", if (latencies.isNotEmpty()) latencies.average().toLong() else 0L)
            })
            totalReceived += received
            totalProcessed += processed
            totalMetricFailures += metricFailures
            totalQueued += queued
            totalSent += sent
            totalDelivered += delivered
            totalOutboxFailed += failed
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        val pending = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sms_outbox WHERE status IN ('QUEUED','RETRY_PENDING','SENDING','DELIVERY_PENDING')",
            null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val averageLatency = if (latencySamples.isEmpty()) 0L else latencySamples.average().toLong()
        val p95Latency = percentile(latencySamples, 0.95)
        return JSONObject().apply {
            put("success", true)
            put("period_days", safeDays)
            put("period_start", dateFormat.format(Date(startAt)))
            put("period_end", dateFormat.format(Date(System.currentTimeMillis())))
            put("daily", daily)
            put("summary", JSONObject().apply {
                put("received", totalReceived)
                put("processed", totalProcessed)
                put("metric_failures", totalMetricFailures)
                put("queued", totalQueued)
                put("sent", totalSent)
                put("delivered", totalDelivered)
                put("outbox_failures", totalOutboxFailed)
                put("delivery_rate", if (totalSent > 0) totalDelivered.toDouble() / totalSent else 0.0)
                put("average_queue_ms", averageLatency)
                put("p95_queue_ms", p95Latency)
                put("pending_now", pending)
            })
        }
    }

    private fun metricCount(date: String, eventType: String): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sms_metrics WHERE date = ? AND event_type = ?",
            arrayOf(date, eventType)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun outboxCount(startAt: Long, endAt: Long, status: String?): Int {
        val selection = if (status == null) "queued_at >= ? AND queued_at < ?" else "queued_at >= ? AND queued_at < ? AND status = ?"
        val args = if (status == null) arrayOf(startAt.toString(), endAt.toString()) else arrayOf(startAt.toString(), endAt.toString(), status)
        return db.readableDatabase.rawQuery("SELECT COUNT(*) FROM sms_outbox WHERE $selection", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun outboxLatencies(startAt: Long, endAt: Long): List<Long> {
        val values = mutableListOf<Long>()
        db.readableDatabase.rawQuery(
            "SELECT sent_at - queued_at FROM sms_outbox WHERE queued_at >= ? AND queued_at < ? AND sent_at IS NOT NULL AND sent_at >= queued_at",
            arrayOf(startAt.toString(), endAt.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) values += cursor.getLong(0)
        }
        return values
    }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
