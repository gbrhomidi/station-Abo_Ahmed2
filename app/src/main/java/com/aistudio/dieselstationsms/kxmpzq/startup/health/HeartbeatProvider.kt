package com.aistudio.dieselstationsms.kxmpzq.startup.health

interface HeartbeatProvider {
    fun lastHeartbeat(): Long
    fun recordHeartbeat()
}
