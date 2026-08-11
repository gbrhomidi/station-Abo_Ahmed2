package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.content.SharedPreferences

class ServiceStatusRepository(context: Context) {
    companion object {
        private const val PREFS_NAME = "service_status_prefs"
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        private const val STALE_TIMEOUT_MS = 300_000L
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setRunning(isRunning: Boolean) {
        prefs.edit()
            .putBoolean(KEY_IS_RUNNING, isRunning)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    fun isRunning(): Boolean {
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)
        if (System.currentTimeMillis() - lastUpdate > STALE_TIMEOUT_MS) return false
        return prefs.getBoolean(KEY_IS_RUNNING, false)
    }

    fun recordHeartbeat() {
        prefs.edit().putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    fun lastHeartbeat(): Long = prefs.getLong(KEY_LAST_HEARTBEAT, 0)
    fun clear() = prefs.edit().clear().apply()
}
