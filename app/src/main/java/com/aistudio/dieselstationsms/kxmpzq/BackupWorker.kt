package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/*
 * ═══════════════════════════════════════════════════════════════
 * BackupWorker - عامل النسخ الاحتياطي التلقائي
 * ═══════════════════════════════════════════════════════════════
 *
 * نسخة معالجة Production
 *
 * الميزات:
 * - تصدير DatabaseHelper
 * - تحقق JSON
 * - تحقق الحجم
 * - تشفير النسخة الاحتياطية
 * - إدارة النسخ القديمة
 * - معالجة أخطاء WorkManager
 * - توافق مع DatabaseHelper Singleton
 *
 * ═══════════════════════════════════════════════════════════════
 */


class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {


    companion object {

        private const val TAG = "BackupWorker"

        private const val BACKUP_PREFIX = "auto_backup_"

        private const val MAX_BACKUPS = 10

        private const val MIN_FREE_SPACE_MB = 50L

        private const val MAX_DATA_SIZE_MB = 10L


        private val DATE_FORMAT =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.getDefault()
            )
    }



    override suspend fun doWork(): Result {

        Log.d(TAG, "Starting automatic backup...")


        return try {


            // ========================================================
            // 1. فحص المساحة
            // ========================================================

            if (!hasEnoughSpace()) {

                Log.e(
                    TAG,
                    "Insufficient storage space"
                )


                return Result.failure(
                    androidx.work.Data.Builder()
                        .putString(
                            "error",
                            "Insufficient storage space"
                        )
                        .build()
                )
            }



            // ========================================================
            // 2. الحصول على قاعدة البيانات
            // ========================================================

            val db =
                DatabaseHelper.getInstance(
                    applicationContext
                )



            try {


                // ====================================================
                // 3. تصدير البيانات
                // ====================================================

                val exportedData =
                    db.exportAllData()



                // ====================================================
                // 4. التحقق من صحة التصدير
                // ====================================================

                if (!isValidExport(exportedData)) {


                    Log.e(
                        TAG,
                        "Invalid export structure"
                    )


                    return Result.failure(
                        androidx.work.Data.Builder()
                            .putString(
                                "error",
                                "Invalid export data"
                            )
                            .build()
                    )
                }



                // ====================================================
                // 5. تحويل JSON والتحقق من الحجم
                // ====================================================

                val jsonString =
                    exportedData.toString(2)



                val dataSize =
                    jsonString
                        .toByteArray(Charsets.UTF_8)
                        .size



                if (dataSize >
                    MAX_DATA_SIZE_MB * 1024 * 1024
                ) {


                    Log.e(
                        TAG,
                        "Backup size exceeded: $dataSize bytes"
                    )


                    return Result.failure(
                        androidx.work.Data.Builder()
                            .putString(
                                "error",
                                "Backup exceeds maximum size"
                            )
                            .build()
                    )
                }



                // ====================================================
                // 6. تشفير البيانات
                // ====================================================

                val encrypted =
                    encryptBackup(jsonString)



                // ====================================================
                // 7. إنشاء مجلد النسخ
                // ====================================================

                val dir =
                    File(
                        applicationContext.filesDir,
                        "backups"
                    )



                if (!dir.exists()
                    && !dir.mkdirs()
                ) {

                    throw IOException(
                        "Cannot create backup directory"
                    )
                }



                // ====================================================
                // 8. تنظيف النسخ القديمة
                // ====================================================

                cleanupOldBackups(dir)



                // ====================================================
                // 9. إنشاء ملف النسخة
                // ====================================================

                val timestamp =
                    DATE_FORMAT.format(Date())



                val file =
                    File(
                        dir,
                        "${BACKUP_PREFIX}${timestamp}.enc"
                    )



                file.writeText(encrypted)



                // ====================================================
                // 10. التحقق من الكتابة
                // ====================================================

                if (!file.exists()
                    || file.length() == 0L
                ) {

                    throw IOException(
                        "Backup file creation failed"
                    )
                }



                Log.d(
                    TAG,
                    "Backup completed successfully"
                )


                Log.d(
                    TAG,
                    "Path: ${file.absolutePath}"
                )


                Log.d(
                    TAG,
                    "Size: ${file.length()} bytes"
                )



                return Result.success(

                    androidx.work.Data.Builder()

                        .putString(
                            "backup_path",
                            file.absolutePath
                        )

                        .putString(
                            "backup_size",
                            file.length().toString()
                        )

                        .putString(
                            "backup_timestamp",
                            timestamp
                        )

                        .build()
                )


            } finally {


                // ====================================================
                // إغلاق Singleton بالطريقة الصحيحة
                // ====================================================

                try {

                    DatabaseHelper.closeInstance()

                } catch (e: Exception) {


                    Log.w(
                        TAG,
                        "Error closing database instance",
                        e
                    )
                }
            }



        } catch (e: OutOfMemoryError) {


            Log.e(
                TAG,
                "Memory limit exceeded",
                e
            )


            Result.failure(
                androidx.work.Data.Builder()
                    .putString(
                        "error",
                        "Memory limit exceeded"
                    )
                    .build()
            )



        } catch (e: SecurityException) {


            Log.e(
                TAG,
                "Permission error",
                e
            )


            Result.failure(
                androidx.work.Data.Builder()
                    .putString(
                        "error",
                        "Permission denied"
                    )
                    .build()
            )



        } catch (e: Exception) {


            Log.e(
                TAG,
                "Backup failed",
                e
            )


            Result.failure(
                androidx.work.Data.Builder()
                    .putString(
                        "error",
                        e.message ?: "Unknown error"
                    )
                    .build()
            )
        }
    }

    // ================================================================
    // التحقق من وجود مساحة كافية
    // ================================================================

    private fun hasEnoughSpace(): Boolean {

        return try {

            val stat =
                StatFs(
                    applicationContext.filesDir.path
                )


            val availableBytes =
                stat.availableBytes


            val requiredBytes =
                MIN_FREE_SPACE_MB * 1024 * 1024


            availableBytes >= requiredBytes


        } catch (e: Exception) {


            Log.e(
                TAG,
                "Unable to check storage space",
                e
            )


            false
        }
    }




    // ================================================================
    // التحقق من صحة البيانات المصدرة
    // ================================================================

    private fun isValidExport(
        data: JSONObject
    ): Boolean {


        return try {


            val requiredKeys =
                arrayOf(

                    "parties",
                    "tanks",
                    "pumps",
                    "sales",
                    "sms_logs",
                    "activity_logs",
                    "employees",
                    "stock_alerts",
                    "system_settings"

                )



            requiredKeys.all { key ->


                data.has(key)
                        &&
                data.get(key) is JSONArray

            }



        } catch (e: Exception) {


            Log.e(
                TAG,
                "Export validation failed",
                e
            )


            false
        }
    }




    // ================================================================
    // تشفير النسخة الاحتياطية
    // ================================================================

    private fun encryptBackup(
        data: String
    ): String {


        val tempFile =
            File(
                applicationContext.cacheDir,
                "temp_backup_${System.currentTimeMillis()}.bin"
            )



        return try {


            val masterKey =
                androidx.security.crypto.MasterKey.Builder(
                    applicationContext
                )
                    .setKeyScheme(
                        androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM
                    )
                    .build()



            val encryptedFile =
                androidx.security.crypto.EncryptedFile.Builder(

                    applicationContext,

                    tempFile,

                    masterKey,

                    androidx.security.crypto.EncryptedFile
                        .FileEncryptionScheme
                        .AES256_GCM_HKDF_4KB

                )
                    .build()



            // الكتابة عبر EncryptedFile
            encryptedFile
                .openFileOutput()
                .use { output ->


                    output.write(
                        data.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }



            // قراءة البيانات المشفرة

            val encryptedBytes =
                encryptedFile
                    .openFileInput()
                    .use { input ->

                        input.readBytes()
                    }



            android.util.Base64
                .encodeToString(
                    encryptedBytes,
                    android.util.Base64.NO_WRAP
                )



        } catch (e: Exception) {


            Log.e(
                TAG,
                "Backup encryption failed",
                e
            )


            throw IOException(
                "Unable to encrypt backup",
                e
            )



        } finally {


            if (tempFile.exists()) {

                tempFile.delete()
            }
        }
    }




    // ================================================================
    // تنظيف النسخ القديمة
    // ================================================================

    private fun cleanupOldBackups(
        dir: File
    ) {


        try {


            val backups =
                dir.listFiles { file ->


                    file.isFile
                            &&
                    file.name.startsWith(
                        BACKUP_PREFIX
                    )

                } ?: return




            if (backups.size <= MAX_BACKUPS) {


                Log.d(
                    TAG,
                    "Backup count within limit"
                )


                return
            }




            val toDelete =
                backups

                    .sortedByDescending {
                        it.lastModified()
                    }

                    .drop(
                        MAX_BACKUPS
                    )




            var deleted = 0

            var failed = 0




            toDelete.forEach { file ->


                try {


                    if (file.delete()) {


                        deleted++


                        Log.d(
                            TAG,
                            "Deleted old backup: ${file.name}"
                        )


                    } else {


                        failed++


                    }



                } catch (e: SecurityException) {


                    failed++


                    Log.e(
                        TAG,
                        "Cannot delete ${file.name}",
                        e
                    )
                }
            }




            Log.d(
                TAG,
                "Cleanup finished. Deleted=$deleted Failed=$failed"
            )



        } catch (e: Exception) {


            Log.e(
                TAG,
                "Cleanup failed",
                e
            )
        }
    }




    // ================================================================
    // عدد النسخ الحالية
    // ================================================================

    private fun getBackupCount(
        dir: File
    ): Int {


        return dir.listFiles { file ->


            file.isFile
                    &&
            file.name.startsWith(
                BACKUP_PREFIX
            )


        }?.size ?: 0
    }
}