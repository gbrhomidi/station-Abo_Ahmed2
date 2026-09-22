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

    override fun launch(reason: StartupReason): ServiceLaunchResult {
        if (statusRepository.isRunning()) {
            return ServiceLaunchResult.AlreadyRunning("Service already running (persistent)")
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
            ServiceLaunchResult.Failure("Security: ${e.message}", retryable = false)
        } catch (e: Exception) {
            ServiceLaunchResult.Failure("Launch failed: ${e.message}", retryable = true)
        }
    }
}
