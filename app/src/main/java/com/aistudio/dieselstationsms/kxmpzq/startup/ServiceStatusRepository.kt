package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.content.SharedPreferences

/**
 * حالة SMSService المشتركة بين طبقة startup والخدمة نفسها.
 *
 * هذا المخزن لا يثبت أن Android سلّم الخدمة نهائياً عند لحظة launch؛
 * بل يمنع طلبات الإطلاق المتزامنة، ثم تُثبت الحياة المستمرة بواسطة heartbeat.
 */
class ServiceStatusRepository(context: Context) {
    companion object {
        private const val PREFS_NAME = "service_status_prefs"
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        private const val STALE_TIMEOUT_MS = 300_000L
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /**
     * يحدّث حالة التشغيل. عند true يمثل ذلك launch gate، وليس دليلاً
     * نهائياً على الجاهزية؛ الجاهزية المستمرة تثبت عبر recordHeartbeat().
     */
    fun setRunning(isRunning: Boolean) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
            .putBoolean(KEY_IS_RUNNING, isRunning)
            .putLong(KEY_LAST_UPDATE, now)

        if (!isRunning) {
            editor.putLong(KEY_LAST_HEARTBEAT, 0L)
        }

        editor.apply()
    }

    /**
     * يرجع true فقط إذا كان آخر launch/heartbeat ضمن نافذة freshness.
     */
    fun isRunning(): Boolean {
        val now = System.currentTimeMillis()
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        val lastSignal = maxOf(lastUpdate, lastHeartbeat)

        if (lastSignal == 0L || now - lastSignal > STALE_TIMEOUT_MS) {
            return false
        }

        return prefs.getBoolean(KEY_IS_RUNNING, false)
    }

    /**
     * heartbeat موحد للخدمة؛ ينعش last_update حتى لا تُعتبر الخدمة stale
     * رغم استمرارها الفعلي.
     */
    fun recordHeartbeat() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_IS_RUNNING, true)
            .putLong(KEY_LAST_HEARTBEAT, now)
            .putLong(KEY_LAST_UPDATE, now)
            .apply()
    }

    fun lastHeartbeat(): Long =
        prefs.getLong(KEY_LAST_HEARTBEAT, 0L)

    fun clear() {
        prefs.edit().clear().apply()
    }
}
