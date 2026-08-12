package com.aistudio.dieselstationsms.kxmpzq.startup.health

/**
 * Provides access to the SMS service heartbeat state.
 *
 * Implementations are responsible for storing and retrieving
 * the timestamp of the latest successful service heartbeat.
 */
interface HeartbeatProvider {

    /**
     * Returns the timestamp of the latest recorded heartbeat
     * in milliseconds since the Unix epoch.
     *
     * A value of 0 indicates that no heartbeat has been recorded.
     */
    fun lastHeartbeat(): Long

    /**
     * Records a heartbeat at the current point in time.
     */
    fun recordHeartbeat()
}