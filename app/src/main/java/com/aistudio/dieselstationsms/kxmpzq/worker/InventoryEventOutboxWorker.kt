package com.aistudio.dieselstationsms.kxmpzq.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.startup.DatabaseInitializationCoordinator
import java.util.concurrent.TimeUnit

/** Reconciles committed inventory events without changing stock or tank balances. */
class InventoryEventOutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val helper = DatabaseInitializationCoordinator.awaitDatabase(applicationContext)
            var acknowledged = 0
            for (stationId in helper.pendingInventoryOutboxStationIds()) {
                if (isStopped) return Result.retry()
                acknowledged += helper.processPendingInventoryOutbox(stationId, 50)
            }
            Log.i(TAG, "Inventory outbox pass completed; acknowledged=$acknowledged")
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Inventory outbox processing failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "InventoryEventOutbox"
        private const val ONE_TIME = "inventory-event-outbox-now"
        private const val RECOVERY = "inventory-event-outbox-recovery"

        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                ONE_TIME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<InventoryEventOutboxWorker>().build()
            )
        }

        fun scheduleRecovery(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                RECOVERY, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<InventoryEventOutboxWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }
}
