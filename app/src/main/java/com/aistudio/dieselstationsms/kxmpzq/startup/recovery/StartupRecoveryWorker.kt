package com.aistudio.dieselstationsms.kxmpzq.startup.recovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.startup.StartupReason
import com.aistudio.dieselstationsms.kxmpzq.startup.di.StartupCompositionRoot
import kotlinx.coroutines.delay

class StartupRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { const val WORK_NAME = "startup_recovery_work" }

    override suspend fun doWork(): Result {
        val coordinator = StartupCompositionRoot.createCoordinator(applicationContext)
        return try {
            var completed = false
            coordinator.execute(
                context = applicationContext,
                reason = StartupReason.SCHEDULED,
                onComplete = { completed = true }
            )
            var waitTime = 0L
            while (!completed && waitTime < 60_000) { delay(1000); waitTime += 1000 }
            if (completed) Result.success() else Result.retry()
        } catch (e: Exception) { Result.retry() }
    }
}
