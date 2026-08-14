package com.aistudio.dieselstationsms.kxmpzq.sms

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ================================================================
 * SMS CORE DIAGNOSTICS SCREEN v1.0
 * ================================================================
 *
 * شاشة تشخيص كاملة لنظام SMS.
 *
 * لا تقوم بأي تعديل على قاعدة البيانات.
 *
 * SQLite:
 *     READ ONLY
 *
 * Diagnostics:
 *     SharedPreferences
 *
 * ================================================================
 */
class SmsCoreDiagnosticsActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var eventsContainer: LinearLayout

    private val handler = Handler(
        Looper.getMainLooper()
    )

    private val refreshRunnable =
        object : Runnable {

            override fun run() {

                if (!isFinishing) {

                    refreshScreen()

                    handler.postDelayed(
                        this,
                        1500L
                    )
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        title = "SMS Core Diagnostics"

        createUi()

        refreshScreen()

        handler.postDelayed(
            refreshRunnable,
            1500L
        )
    }

    override fun onDestroy() {

        handler.removeCallbacks(
            refreshRunnable
        )

        super.onDestroy()
    }

    private fun createUi() {

        root = LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                24,
                24,
                24,
                24
            )
        }

        val header = TextView(this).apply {

            text =
                "📡 SMS CORE DIAGNOSTICS v1.0"

            textSize = 22f

            setPadding(
                0,
                0,
                0,
                16
            )
        }

        root.addView(header)

        statusText =
            TextView(this).apply {

                textSize = 15f

                setPadding(
                    0,
                    8,
                    0,
                    16
                )
            }

        root.addView(statusText)

        summaryText =
            TextView(this).apply {

                textSize = 14f

                setPadding(
                    0,
                    8,
                    0,
                    16
                )
            }

        root.addView(summaryText)

        val buttons =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val refreshButton =
            Button(this).apply {

                text = "تحديث"

                setOnClickListener {
                    refreshScreen()
                }
            }

        buttons.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val clearButton =
            Button(this).apply {

                text = "مسح التشخيص"

                setOnClickListener {

                    SmsCoreDiagnostics.clear(
                        this@SmsCoreDiagnosticsActivity
                    )

                    refreshScreen()
                }
            }

        buttons.addView(
            clearButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val exportButton =
            Button(this).apply {

                text = "تصدير JSON"

                setOnClickListener {

                    showExport()
                }
            }

        buttons.addView(
            exportButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(buttons)

        val scroll =
            ScrollView(this)

        eventsContainer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    0,
                    20,
                    0,
                    40
                )
            }

        scroll.addView(
            eventsContainer
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun refreshScreen() {

        try {

            val summary =
                SmsCoreDiagnostics.getSummary(
                    this
                )

            summaryText.text =
                buildSummaryText(summary)

            statusText.text =
                "🟢 نظام التشخيص يعمل داخلياً — بدون Logcat"

            renderEvents()

        } catch (e: Exception) {

            statusText.text =
                "🔴 خطأ في شاشة التشخيص: ${e.message}"
        }
    }

    private fun buildSummaryText(
        json: JSONObject
    ): String {

        return buildString {

            append("إجمالي الأحداث: ")
            append(
                json.optInt(
                    "total_events"
                )
            )

            append("\n")

            append("📥 مستلمة: ")
            append(
                json.optInt(
                    "received"
                )
            )

            append("   ")

            append("✅ مكتملة: ")
            append(
                json.optInt(
                    "completed"
                )
            )

            append("\n")

            append("❌ فاشلة: ")
            append(
                json.optInt(
                    "failed"
                )
            )

            append("   ")

            append("🚫 مرفوضة: ")
            append(
                json.optInt(
                    "rejected"
                )
            )

            append("\n")

            append("⛔ محظورة: ")
            append(
                json.optInt(
                    "blocked"
                )
            )

            append("   ")

            append("⚠️ أخطاء: ")
            append(
                json.optInt(
                    "errors"
                )
            )

            append("\n")

            append("🔄 عمليات نشطة: ")
            append(
                json.optInt(
                    "active_traces"
                )
            )
        }
    }

    private fun renderEvents() {

        eventsContainer.removeAllViews()

        val events =
            SmsCoreDiagnostics.getEvents(
                this
            )

        if (events.length() == 0) {

            eventsContainer.addView(
                TextView(this).apply {

                    text =
                        "لا توجد أحداث تشخيصية حتى الآن."

                    textSize = 16f

                    setPadding(
                        0,
                        30,
                        0,
                        30
                    )
                }
            )

            return
        }

        for (
            i in 0 until events.length()
        ) {

            val event =
                events.optJSONObject(i)
                    ?: continue

            addEventView(event)
        }
    }

    private fun addEventView(
        event: JSONObject
    ) {

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    20,
                    20,
                    20,
                    20
                )

                setBackgroundColor(
                    0xFFF2F5F8.toInt()
                )
            }

        val stage =
            event.optString(
                "stage_title",
                event.optString("stage")
            )

        val level =
            event.optString(
                "level"
            )

        val timestamp =
            event.optString(
                "timestamp_text"
            )

        val traceId =
            event.optString(
                "trace_id"
            )

        val message =
            event.optString(
                "message"
            )

        val header =
            TextView(this).apply {

                text =
                    "[$timestamp]  $stage"

                textSize = 16f
            }

        card.addView(
            header
        )

        val levelView =
            TextView(this).apply {

                text =
                    "المستوى: $level"

                textSize = 13f
            }

        card.addView(
            levelView
        )

        val traceView =
            TextView(this).apply {

                text =
                    "Trace: $traceId"

                textSize = 12f
            }

        card.addView(
            traceView
        )

        val messageView =
            TextView(this).apply {

                text =
                    message

                textSize = 15f

                setPadding(
                    0,
                    8,
                    0,
                    8
                )
            }

        card.addView(
            messageView
        )

        event.optJSONObject(
            "details"
        )?.let {

            val details =
                TextView(this).apply {

                    text =
                        it.toString(2)

                    textSize = 12f

                    setPadding(
                        0,
                        8,
                        0,
                        0
                    )
                }

            val horizontal =
                HorizontalScrollView(this)

            horizontal.addView(
                details
            )

            card.addView(
                horizontal
            )
        }

        eventsContainer.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                setMargins(
                    0,
                    0,
                    0,
                    12
                )
            }
        )
    }

    private fun showExport() {

        val data =
            SmsCoreDiagnostics.exportJson(
                this
            )

        val dialog =
            androidx.appcompat.app.AlertDialog.Builder(
                this
            )
                .setTitle(
                    "SMS Diagnostics JSON"
                )
                .setMessage(
                    data.toString(2)
                )
                .setPositiveButton(
                    "إغلاق",
                    null
                )
                .create()

        dialog.show()
    }
}