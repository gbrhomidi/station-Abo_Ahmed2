package com.aistudio.dieselstationsms.kxmpzq.service

import com.aistudio.dieselstationsms.kxmpzq.startup.health.HeartbeatProvider
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

object SMSServiceHeartbeatProvider : HeartbeatProvider {
    private val lastHeartbeatTime = AtomicLong(System.currentTimeMillis())
    private val _isRunning = AtomicBoolean(false)

    override fun lastHeartbeat(): Long = lastHeartbeatTime.get()
    override fun recordHeartbeat() { lastHeartbeatTime.set(System.currentTimeMillis()) }
    fun setRunning(running: Boolean) { _isRunning.set(running) }
    fun isRunning(): Boolean = _isRunning.get()
}
