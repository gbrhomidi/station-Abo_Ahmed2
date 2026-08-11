package com.aistudio.dieselstationsms.kxmpzq.startup.health

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import kotlinx.coroutines.delay

/**
 * ═══════════════════════════════════════════════════════════════
* مراقب صحة خدمة SMS - SmsServiceHealthMonitor
 * ═══════════════════════════════════════════════════════════════
 *
 * التحديثات:
 * 1. ✅ إضافة Context للتحقق من AndroidManifest.xml
 * 2. ✅ التحقق من تسجيل SmsReceiver في Manifest
 * 3. ✅ الحفاظ على منطق Heartbeat الأصلي
 */
class SmsServiceHealthMonitor(
    private val checkIntervalMs: Long,
    private val heartbeatProvider: HeartbeatProvider,
    private val context: Context,  // ✅ جديد
    private val maxFailures: Int = 3
) : HealthMonitor {

    private var consecutiveFailures = 0

    override suspend fun check(): HealthStatus {
        delay(checkIntervalMs)

        // ✅ التحقق من SmsReceiver في Manifest
        if (!isSmsReceiverInManifest()) {
            return HealthStatus.Unhealthy("SmsReceiver not registered in AndroidManifest.xml")
        }

        return when {
            !isHeartbeatRecent() -> {
                consecutiveFailures++
                if (consecutiveFailures >= maxFailures) {
                    HealthStatus.Unhealthy("No heartbeat after $maxFailures checks")
                } else {
                    HealthStatus.Unknown("No heartbeat, attempt $consecutiveFailures/$maxFailures")
                }
            }
            else -> {
                consecutiveFailures = 0
                HealthStatus.Healthy
            }
        }
    }

    override fun isHealthy(): Boolean = isHeartbeatRecent() && isSmsReceiverInManifest()

    override suspend fun waitForHealthy(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isHealthy()) return true
            delay(checkIntervalMs)
        }
        return false
    }

    private fun isHeartbeatRecent(): Boolean {
        return System.currentTimeMillis() - heartbeatProvider.lastHeartbeat() < checkIntervalMs * 2
    }

    /**
     * ✅ التحقق من تسجيل SmsReceiver في AndroidManifest.xml
     */
    private fun isSmsReceiverInManifest(): Boolean {
        return try {
            val pm = context.packageManager
            val receivers = pm.queryBroadcastReceivers(
                Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
                PackageManager.GET_RESOLVED_FILTER
            )
            receivers.any { it.activityInfo.packageName == context.packageName }
        } catch (e: Exception) {
            false
        }
    }
}