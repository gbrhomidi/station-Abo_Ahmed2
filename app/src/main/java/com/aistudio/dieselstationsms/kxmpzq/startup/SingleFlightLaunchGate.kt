package com.aistudio.dieselstationsms.kxmpzq.startup

/**
 * Process-wide gate for service-start requests initiated by independent
 * startup entry points (boot, activity, recovery worker, and app update).
 */
internal class SingleFlightLaunchGate {
    private val lock = Any()
    private var claimed = false

    fun tryAcquire(): Boolean = synchronized(lock) {
        if (claimed) false else {
            claimed = true
            true
        }
    }

    fun release() {
        synchronized(lock) { claimed = false }
    }
}
