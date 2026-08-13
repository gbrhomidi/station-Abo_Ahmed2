package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ================================================================
 * SMS CORE DIAGNOSTICS v1.0
 * ================================================================
 *
 * نظام تشخيص داخلي كامل لنظام SMS.
 *
 * IMPORTANT:
 * - لا يستخدم Logcat كمصدر بيانات.
 * - لا ينشئ أي جدول SQLite.
 * - لا يعدل DatabaseHelper.
 * - لا يرفع Database VERSION.
 * - يخزن سجل التشخيص في SharedPreferences فقط.
 *
 * يحتفظ بآخر MAX_EVENTS حدث تشخيصي.
 *
 * ================================================================
 */
object SmsCoreDiagnostics {

    private const val PREFS_NAME = "sms_core_diagnostics_v1"
    private const val EVENTS_KEY = "events"
    private const val ACTIVE_KEY = "active_traces"
    private const val MAX_EVENTS = 1000
    private const val MAX_ACTIVE_TRACES = 50

    private val lock = Any()

    enum class Stage(
        val code: String,
        val title: String
    ) {
        RECEIVED(
            "received",
            "تم استلام الرسالة"
        ),

        PARSING(
            "parsing",
            "تحليل الرسالة"
        ),

        DUPLICATE_CHECK(
            "duplicate_check",
            "فحص التكرار"
        ),

        SMSC_CHECK(
            "smsc_check",
            "فحص SMSC"
        ),

        BLOCK_CHECK(
            "block_check",
            "فحص الحظر"
        ),

        SUSPICIOUS_CHECK(
            "suspicious_check",
            "فحص المحتوى المشبوه"
        ),

        CUSTOMER_RESOLUTION(
            "customer_resolution",
            "البحث عن العميل"
        ),

        CUSTOMER_RESOLVED(
            "customer_resolved",
            "تم التعرف على العميل"
        ),

        CUSTOMER_NOT_FOUND(
            "customer_not_found",
            "العميل غير مسجل"
        ),

        CONTEXT(
            "context",
            "قراءة سياق المحادثة"
        ),

        RATE_LIMIT(
            "rate_limit",
            "فحص معدل الطلبات"
        ),

        INTENT_DETECTION(
            "intent_detection",
            "اكتشاف النية"
        ),

        INTENT_DETECTED(
            "intent_detected",
            "تم اكتشاف النية"
        ),

        BUSINESS_PROCESSING(
            "business_processing",
            "تنفيذ منطق الطلب"
        ),

        REPLY_PROCESSING(
            "reply_processing",
            "تجهيز الرد"
        ),

        REPLY_SENT(
            "reply_sent",
            "تم إرسال الرد"
        ),

        COMPLETED(
            "completed",
            "اكتملت المعالجة"
        ),

        IGNORED(
            "ignored",
            "تم تجاهل الرسالة"
        ),

        REJECTED(
            "rejected",
            "تم رفض الرسالة"
        ),

        BLOCKED(
            "blocked",
            "تم حظر الرسالة"
        ),

        FAILED(
            "failed",
            "فشلت المعالجة"
        )
    }

    enum class Level {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        SUCCESS
    }

    data class Trace(
        val id: String,
        val phone: String,
        val messagePreview: String,
        val startedAt: Long
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private fun now(): Long = System.currentTimeMillis()

    private fun formatTime(timestamp: Long): String {
        return try {
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale("ar")
            ).format(Date(timestamp))
        } catch (_: Exception) {
            timestamp.toString()
        }
    }

    private fun sanitizePhone(phone: String?): String {
        if (phone.isNullOrBlank()) return "unknown"

        val normalized = phone.trim()

        return when {
            normalized.length <= 4 -> normalized
            else -> {
                val prefix = normalized.take(3)
                val suffix = normalized.takeLast(3)
                "$prefix•••$suffix"
            }
        }
    }

    private fun sanitizeMessage(message: String?): String {
        return message
            ?.replace("\n", " ")
            ?.replace("\r", " ")
            ?.trim()
            ?.take(300)
            ?: ""
    }

    private fun readEvents(context: Context): JSONArray {
        return synchronized(lock) {
            try {
                val raw = prefs(context).getString(
                    EVENTS_KEY,
                    "[]"
                ) ?: "[]"

                JSONArray(raw)
            } catch (_: Exception) {
                JSONArray()
            }
        }
    }

    private fun writeEvents(
        context: Context,
        events: JSONArray
    ) {
        synchronized(lock) {
            prefs(context)
                .edit()
                .putString(
                    EVENTS_KEY,
                    events.toString()
                )
                .apply()
        }
    }

    fun newTrace(
        context: Context,
        phone: String,
        message: String
    ): String {

        val traceId = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(12)

        val trace = JSONObject().apply {
            put("trace_id", traceId)
            put("phone", sanitizePhone(phone))
            put("message_preview", sanitizeMessage(message))
            put("started_at", now())
            put("started_at_text", formatTime(now()))
            put("last_stage", Stage.RECEIVED.code)
            put("last_stage_title", Stage.RECEIVED.title)
            put("status", "running")
            put("events_count", 0)
        }

        synchronized(lock) {
            val prefs = prefs(context)

            val current = try {
                JSONArray(
                    prefs.getString(
                        ACTIVE_KEY,
                        "[]"
                    ) ?: "[]"
                )
            } catch (_: Exception) {
                JSONArray()
            }

            current.put(trace)

            while (current.length() > MAX_ACTIVE_TRACES) {
                current.remove(0)
            }

            prefs.edit()
                .putString(
                    ACTIVE_KEY,
                    current.toString()
                )
                .apply()
        }

        event(
            context = context,
            traceId = traceId,
            stage = Stage.RECEIVED,
            level = Level.INFO,
            message = "تم استقبال رسالة SMS جديدة"
        )

        return traceId
    }

    fun event(
        context: Context,
        traceId: String,
        stage: Stage,
        level: Level,
        message: String,
        details: JSONObject? = null
    ) {

        val timestamp = now()

        val item = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("trace_id", traceId)
            put("timestamp", timestamp)
            put("timestamp_text", formatTime(timestamp))
            put("stage", stage.code)
            put("stage_title", stage.title)
            put("level", level.name)
            put("message", message.take(500))

            if (details != null) {
                put("details", details)
            }
        }

        synchronized(lock) {

            val events = readEvents(context)

            events.put(item)

            while (events.length() > MAX_EVENTS) {
                events.remove(0)
            }

            writeEvents(
                context,
                events
            )

            updateActiveTrace(
                context,
                traceId,
                stage,
                level
            )
        }
    }

    private fun updateActiveTrace(
        context: Context,
        traceId: String,
        stage: Stage,
        level: Level
    ) {

        val prefs = prefs(context)

        val active = try {
            JSONArray(
                prefs.getString(
                    ACTIVE_KEY,
                    "[]"
                ) ?: "[]"
            )
        } catch (_: Exception) {
            JSONArray()
        }

        for (i in 0 until active.length()) {

            val item = active.optJSONObject(i)
                ?: continue

            if (item.optString("trace_id") != traceId) {
                continue
            }

            item.put(
                "last_stage",
                stage.code
            )

            item.put(
                "last_stage_title",
                stage.title
            )

            item.put(
                "last_level",
                level.name
            )

            item.put(
                "last_event_at",
                now()
            )

            item.put(
                "last_event_at_text",
                formatTime(now())
            )

            item.put(
                "events_count",
                item.optInt("events_count", 0) + 1
            )

            active.put(i, item)

            break
        }

        prefs.edit()
            .putString(
                ACTIVE_KEY,
                active.toString()
            )
            .apply()
    }

    fun finish(
        context: Context,
        traceId: String,
        stage: Stage,
        success: Boolean,
        message: String,
        details: JSONObject? = null
    ) {

        val end = now()

        event(
            context = context,
            traceId = traceId,
            stage = stage,
            level = if (success) {
                Level.SUCCESS
            } else {
                Level.ERROR
            },
            message = message,
            details = details
        )

        synchronized(lock) {

            val events = readEvents(context)

            val summary = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("trace_id", traceId)
                put("timestamp", end)
                put("timestamp_text", formatTime(end))
                put(
                    "stage",
                    if (success) {
                        Stage.COMPLETED.code
                    } else {
                        Stage.FAILED.code
                    }
                )
                put(
                    "stage_title",
                    if (success) {
                        Stage.COMPLETED.title
                    } else {
                        Stage.FAILED.title
                    }
                )
                put(
                    "level",
                    if (success) {
                        Level.SUCCESS.name
                    } else {
                        Level.ERROR.name
                    }
                )
                put(
                    "message",
                    message.take(500)
                )
                put(
                    "terminal",
                    true
                )
                put(
                    "success",
                    success
                )
            }

            events.put(summary)

            while (events.length() > MAX_EVENTS) {
                events.remove(0)
            }

            writeEvents(
                context,
                events
            )

            removeActiveTrace(
                context,
                traceId
            )
        }
    }

    private fun removeActiveTrace(
        context: Context,
        traceId: String
    ) {

        val prefs = prefs(context)

        val active = try {
            JSONArray(
                prefs.getString(
                    ACTIVE_KEY,
                    "[]"
                ) ?: "[]"
            )
        } catch (_: Exception) {
            JSONArray()
        }

        val result = JSONArray()

        for (i in 0 until active.length()) {

            val item = active.optJSONObject(i)
                ?: continue

            if (item.optString("trace_id") != traceId) {
                result.put(item)
            }
        }

        prefs.edit()
            .putString(
                ACTIVE_KEY,
                result.toString()
            )
            .apply()
    }

    fun getEvents(
        context: Context,
        traceId: String? = null
    ): JSONArray {

        val source = readEvents(context)

        if (traceId.isNullOrBlank()) {
            return source
        }

        val result = JSONArray()

        for (i in 0 until source.length()) {

            val item = source.optJSONObject(i)
                ?: continue

            if (item.optString("trace_id") == traceId) {
                result.put(item)
            }
        }

        return result
    }

    fun getActiveTraces(
        context: Context
    ): JSONArray {

        return synchronized(lock) {

            try {
                JSONArray(
                    prefs(context).getString(
                        ACTIVE_KEY,
                        "[]"
                    ) ?: "[]"
                )
            } catch (_: Exception) {
                JSONArray()
            }
        }
    }

    fun getSummary(
        context: Context
    ): JSONObject {

        val events = readEvents(context)

        var received = 0
        var completed = 0
        var failed = 0
        var rejected = 0
        var blocked = 0
        var ignored = 0
        var errors = 0

        for (i in 0 until events.length()) {

            val item = events.optJSONObject(i)
                ?: continue

            when (item.optString("stage")) {

                Stage.RECEIVED.code ->
                    received++

                Stage.COMPLETED.code ->
                    completed++

                Stage.FAILED.code ->
                    failed++

                Stage.REJECTED.code ->
                    rejected++

                Stage.BLOCKED.code ->
                    blocked++

                Stage.IGNORED.code ->
                    ignored++
            }

            if (
                item.optString("level") ==
                Level.ERROR.name
            ) {
                errors++
            }
        }

        return JSONObject().apply {
            put("total_events", events.length())
            put("received", received)
            put("completed", completed)
            put("failed", failed)
            put("rejected", rejected)
            put("blocked", blocked)
            put("ignored", ignored)
            put("errors", errors)
            put(
                "active_traces",
                getActiveTraces(context).length()
            )
        }
    }

    fun clear(
        context: Context
    ) {

        synchronized(lock) {

            prefs(context)
                .edit()
                .remove(EVENTS_KEY)
                .remove(ACTIVE_KEY)
                .apply()
        }
    }

    fun exportJson(
        context: Context
    ): JSONObject {

        return JSONObject().apply {

            put(
                "diagnostics_version",
                "1.0"
            )

            put(
                "generated_at",
                now()
            )

            put(
                "generated_at_text",
                formatTime(now())
            )

            put(
                "android_version",
                Build.VERSION.SDK_INT
            )

            put(
                "manufacturer",
                Build.MANUFACTURER
            )

            put(
                "model",
                Build.MODEL
            )

            put(
                "summary",
                getSummary(context)
            )

            put(
                "active_traces",
                getActiveTraces(context)
            )

            put(
                "events",
                getEvents(context)
            )
        }
    }
}