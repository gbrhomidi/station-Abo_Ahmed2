package com.aistudio.dieselstationsms.kxmpzq.settings.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Full SQLite backup/restore through the Android Storage Access Framework. */
class FullDatabaseBackupManager(
    private val context: Context,
    private val database: DatabaseHelper
) {
    companion object {
        const val PREFS_NAME = "database_backup_storage"
        const val TREE_URI_KEY = "tree_uri"
        const val BACKUP_PREFIX = "station_full_backup_"
        private const val KEY_ALIAS = "station_full_database_backup_v1"
        private val MAGIC = byteArrayOf(0x53, 0x54, 0x44, 0x42, 0x31)
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        fun selectedTreeUri(context: Context): Uri? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(TREE_URI_KEY, null)
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
    }

    fun createBackup(treeUri: Uri, encrypt: Boolean, keepCount: Int): JSONObject {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        val snapshot = File(database.backupDatabase())
        require(snapshot.isFile && snapshot.length() > 0L) {
            "لم ينتج عن SQLite ملف نسخة احتياطية كامل وغير فارغ"
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = if (encrypt) ".db.enc" else ".db"
        val name = BACKUP_PREFIX + stamp + "_" + System.currentTimeMillis().toString().takeLast(4) + suffix
        val target = DocumentsContract.createDocument(
            context.contentResolver, parentUri, "application/octet-stream", name
        ) ?: throw IllegalStateException("تعذر إنشاء ملف النسخة داخل المجلد المحدد")

        try {
            val targetStream = context.contentResolver.openOutputStream(target, "wt")
                ?: throw IllegalStateException("تعذر فتح ملف النسخة للكتابة")
            targetStream.use { output ->
                if (encrypt) {
                    val cipher = createCipher(Cipher.ENCRYPT_MODE, null)
                    val iv = cipher.iv ?: throw IllegalStateException("لم يتم إنشاء IV لتشفير النسخة")
                    output.write(MAGIC)
                    output.write(iv.size)
                    output.write(iv)
                    CipherOutputStream(output, cipher).use { encryptedOutput ->
                        FileInputStream(snapshot).use { input -> input.copyTo(encryptedOutput) }
                    }
                } else {
                    FileInputStream(snapshot).use { input -> input.copyTo(output) }
                    output.flush()
                }
            }

            val digest = sha256Document(target)
            require(digest.first > 0L) { "ملف النسخة الذي أنشأه مدير التخزين فارغ" }
            val reportedSize = querySize(target).takeIf { it > 0L } ?: digest.first
            pruneTree(treeUri, keepCount.coerceIn(1, 100))
            pruneLocalSnapshots(keepCount.coerceIn(1, 100))
            return JSONObject()
                .put("success", true)
                .put("uri", target.toString())
                .put("file_name", name)
                .put("size", reportedSize)
                .put("checksum", digest.second)
                .put("is_encrypted", encrypt)
                .put("message", "تم إنشاء نسخة كاملة من قاعدة SQLite والتحقق من قراءة محتواها")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw error
        }
    }

    fun listBackups(treeUri: Uri): JSONArray {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val entries = mutableListOf<JSONObject>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol).orEmpty()
                if (!isSupportedBackupName(name)) continue
                val documentId = cursor.getString(idCol) ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                entries += JSONObject()
                    .put("id", uri.toString())
                    .put("file_name", name)
                    .put("created_at", if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L)
                    .put("size", if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L)
                    .put("is_encrypted", name.endsWith(".db.enc", ignoreCase = true))
            }
        } ?: throw IllegalStateException("لم يستجب مدير التخزين لطلب قائمة الملفات")
        val result = JSONArray()
        entries.sortedByDescending { it.optLong("created_at") }.forEach { result.put(it) }
        return result
    }

    fun deleteBackup(treeUri: Uri, documentUri: Uri): Boolean {
        require(documentUri.scheme == "content") { "معرف ملف النسخة غير صالح" }
        require(DocumentsContract.getTreeDocumentId(documentUri) == DocumentsContract.getTreeDocumentId(treeUri)) {
            "ملف النسخة ليس داخل مجلد النسخ المحدد"
        }
        require(isSupportedBackupName(queryDisplayName(documentUri))) {
            "رفض حذف ملف لا يبدو نسخة احتياطية لهذا التطبيق"
        }
        return DocumentsContract.deleteDocument(context.contentResolver, documentUri)
    }

    fun restoreBackup(documentUri: Uri): JSONObject {
        require(documentUri.scheme == "content") { "يجب اختيار ملف من مدير التخزين" }
        val name = queryDisplayName(documentUri)
        require(name.endsWith(".db", true) || name.endsWith(".db.enc", true)) {
            "نوع الملف غير مدعوم؛ اختر ملف .db أو .db.enc"
        }

        val stagingDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
        require(stagingDir.exists() || stagingDir.mkdirs()) { "تعذر إنشاء مجلد الاستعادة المؤقت" }
        val staging = File(stagingDir, "restore_import_" + System.currentTimeMillis() + ".db")
        try {
            val source = context.contentResolver.openInputStream(documentUri)
                ?: throw IllegalStateException("تعذر فتح ملف النسخة المحدد")
            source.use { input ->
                if (name.endsWith(".db.enc", true)) decryptDatabaseStream(input, staging)
                else FileOutputStream(staging).use { output -> input.copyTo(output) }
            }
            require(staging.isFile && staging.length() > 0L) { "ملف النسخة فارغ" }
            verifySnapshot(staging)
            val restored = database.restoreDatabaseSafe(staging.absolutePath)
            require(restored.optBoolean("success", false)) {
                restored.optString("error", "تعذر تطبيق نسخة قاعدة البيانات")
            }
            return JSONObject()
                .put("success", true)
                .put("file_name", name)
                .put("integrity_verified", restored.optBoolean("integrity_verified", false))
                .put("emergency_snapshot", restored.optString("emergency_snapshot"))
                .put("message", "تمت استعادة قاعدة البيانات والتحقق من سلامتها")
        } finally {
            runCatching { staging.delete() }
        }
    }

    private fun isSupportedBackupName(name: String): Boolean =
        name.startsWith(BACKUP_PREFIX) &&
            (name.endsWith(".db", true) || name.endsWith(".db.enc", true))

    private fun queryDisplayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("تعذر تحديد اسم ملف النسخة")

    private fun querySize(uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (col >= 0 && !cursor.isNull(col)) cursor.getLong(col) else 0L
            } else 0L
        } ?: 0L

    private fun sha256Document(uri: Uri): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    digest.update(buffer, 0, count)
                    bytesRead += count.toLong()
                }
            }
        } ?: throw IllegalStateException("تعذر قراءة النسخة للتحقق من SHA-256")
        return bytesRead to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pruneTree(treeUri: Uri, keepCount: Int) {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val files = mutableListOf<Pair<Long, Uri>>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val timeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol).orEmpty()
                if (!isSupportedBackupName(name)) continue
                val documentId = cursor.getString(idCol) ?: continue
                val modified = if (timeCol >= 0 && !cursor.isNull(timeCol)) cursor.getLong(timeCol) else 0L
                files += modified to DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            }
        }
        files.sortedByDescending { it.first }.drop(keepCount).forEach { (_, uri) ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
    }

    private fun pruneLocalSnapshots(keepCount: Int) {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
        directory.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("backup_[0-9]+\\.db")) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keepCount)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun verifySnapshot(file: File) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
            val integrityOk = snapshot.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", true)
            }
            require(integrityOk) { "فشل integrity_check للنسخة المحددة" }
            val version = snapshot.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
            require(version == DatabaseHelper.VERSION) {
                "إصدار Schema في النسخة (" + version + ") لا يطابق إصدار التطبيق (" + DatabaseHelper.VERSION + ")"
            }
        }
    }

    private fun decryptDatabaseStream(source: java.io.InputStream, destination: File) {
        val header = DataInputStream(BufferedInputStream(source))
        val magic = ByteArray(MAGIC.size)
        header.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "ترويسة النسخة المشفرة غير صالحة" }
        val ivLength = header.readUnsignedByte()
        require(ivLength == IV_SIZE) { "طول IV في النسخة المشفرة غير صالح" }
        val iv = ByteArray(ivLength)
        header.readFully(iv)
        CipherInputStream(header, createCipher(Cipher.DECRYPT_MODE, iv)).use { decrypted ->
            FileOutputStream(destination).use { output -> decrypted.copyTo(output) }
        }
    }

    private fun createCipher(mode: Int, iv: ByteArray?): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key: SecretKey = if (store.containsAlias(KEY_ALIAS)) {
            store.getKey(KEY_ALIAS, null) as SecretKey
        } else {
            require(mode == Cipher.ENCRYPT_MODE) {
                "مفتاح تشفير النسخة غير موجود على هذا التثبيت؛ لا يمكن فك تشفير النسخة على هذا الجهاز"
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (mode == Cipher.ENCRYPT_MODE) init(mode, key)
            else init(mode, key, GCMParameterSpec(TAG_BITS, requireNotNull(iv)))
        }
    }
}
