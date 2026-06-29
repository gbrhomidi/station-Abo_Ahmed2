package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileWriter

class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BackupWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val db = DatabaseHelper(applicationContext)
            val out = db.exportAllData().toString(2)
            val dir = File(applicationContext.filesDir, "backups")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "auto_backup_${System.currentTimeMillis()}.json")
            FileWriter(file).use { writer ->
                writer.write(out)
            }
            Log.d(TAG, "Auto backup completed: ${file.absolutePath}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed: ${e.message}", e)
            Result.failure()
        }
    }
}
