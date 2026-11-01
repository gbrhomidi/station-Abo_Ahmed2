package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService

class SmsServiceLauncher(
    private val context: Context,
    private val statusRepository: ServiceStatusRepository
) : ServiceLauncher {

    companion object {
        private val launchGate = SingleFlightLaunchGate()
        fun releaseLaunchClaim() { launchGate.release() }
    }

    override fun launch(reason: StartupReason): ServiceLaunchResult {
        if (statusRepository.isRunning() || SMSService.getInstance() != null) {
            return ServiceLaunchResult.AlreadyRunning("Service already running or initializing")
        }
        if (!launchGate.tryAcquire()) {
            return ServiceLaunchResult.AlreadyRunning("SMSService launch is already in progress")
        }
        if (statusRepository.isRunning() || SMSService.getInstance() != null) {
            launchGate.release()
            return ServiceLaunchResult.AlreadyRunning("Service already running or initializing")
        }

        return try {
            val intent = Intent(context, SMSService::class.java).apply {
                putExtra("startup_reason", reason.name)
                putExtra("launch_time", System.currentTimeMillis())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }

            // تُضبط حالة التشغيل من SMSService بعد اكتمال onCreate والتهيئة.
            // لا نكتب true هنا لأن startForegroundService قد يفشل لاحقًا.
            ServiceLaunchResult.Success("SMSService launch requested")

        } catch (e: SecurityException) {
            launchGate.release()
            ServiceLaunchResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: Exception) {
            launchGate.release()
            ServiceLaunchResult.Failure("Launch failed: ${e.message}", retryable = true)
        }
    }
}
