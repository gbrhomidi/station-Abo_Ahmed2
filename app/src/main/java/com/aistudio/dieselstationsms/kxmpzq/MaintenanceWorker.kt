package com.aistudio.dieselstationsms.kxmpzq.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

class MaintenanceWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val dbHelper = DatabaseHelper.getInstance(applicationContext)
            dbHelper.performSecurityCheck()
            dbHelper.cleanupExpired()
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
