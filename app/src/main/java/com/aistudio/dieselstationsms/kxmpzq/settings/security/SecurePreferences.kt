package com.aistudio.dieselstationsms.kxmpzq.settings.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SharedPreferences backed by an AES-GCM encrypted payload and a non-exportable Android Keystore key.
 * The AndroidX Security Crypto API is used only by the isolated one-time legacy reader below.
 */
object SecurePreferences {
    const val DEFAULT_LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    private val instances = mutableMapOf<String, SharedPreferences>()

    @Synchronized
    fun open(
        context: Context,
        name: String,
        legacyMasterKeyAlias: String = DEFAULT_LEGACY_MASTER_KEY_ALIAS,
        allowSanitizedPlaintextLegacy: Boolean = false
    ): SharedPreferences {
        require(name.isNotBlank() && !name.contains('/')) { "Invalid secure preference name" }
        val appContext = context.applicationContext
        val cacheKey = "${appContext.packageName}:${appContext.filesDir.absolutePath}:$name"
        return instances.getOrPut(cacheKey) {
            KeystoreSharedPreferences(
                context = appContext,
                name = name,
                legacyMasterKeyAlias = legacyMasterKeyAlias,
                allowSanitizedPlaintextLegacy = allowSanitizedPlaintextLegacy
            )
        }
    }
}

internal object AndroidKeystoreAesGcm {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val AES_KEY_BITS = 256
    private val keyLock = Any()

    fun encrypt(alias: String, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        cipher.updateAAD(associatedData)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(alias: String, associatedData: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size > GCM_NONCE_BYTES + GCM_TAG_BYTES) { "Encrypted payload is truncated" }
        val nonce = payload.copyOfRange(0, GCM_NONCE_BYTES)
        val encrypted = payload.copyOfRange(GCM_NONCE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(alias), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(encrypted)
    }

    @Synchronized
    private fun getOrCreateKey(alias: String): SecretKey {
        require(alias.isNotBlank()) { "Keystore alias must not be blank" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKey(alias: String): SecretKey {
        require(alias.isNotBlank()) { "Keystore alias must not be blank" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Android Keystore key is missing for encrypted data")
    }
}

private class KeystoreSharedPreferences(
    private val context: Context,
    private val name: String,
    private val legacyMasterKeyAlias: String,
    private val allowSanitizedPlaintextLegacy: Boolean
) : SharedPreferences {
    private val backing = context.getSharedPreferences("${name}_secure_v2", Context.MODE_PRIVATE)
    private val lock = Any()
    private val keyAlias = "station_secure_preferences_v2_${sha256(name).take(32)}"
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SecurePreferences-$name").apply { isDaemon = true }
    }

    @Volatile
    private var values: Map<String, Any> = loadOrMigrate()

    private fun loadOrMigrate(): Map<String, Any> {
        val encryptedPayload = backing.getString(DATA_KEY, null)
        if (encryptedPayload != null) {
            return decodeValues(decrypt(Base64.decode(encryptedPayload, Base64.NO_WRAP)))
        }

        val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
        if (!legacyFile.isFile) return emptyMap()

        val legacyValues = try {
            readLegacyEncryptedPreferences(context, name, legacyMasterKeyAlias)
        } catch (legacyFailure: Exception) {
            val rawLegacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val sanitizedAuditLog = if (allowSanitizedPlaintextLegacy && rawLegacy.contains(AUDIT_LOG_KEY)) {
                rawLegacy.all.filterKeys { it == AUDIT_LOG_KEY }
            } else {
                emptyMap()
            }
            if (sanitizedAuditLog.isEmpty()) {
                throw IllegalStateException("Unable to migrate encrypted preferences '$name'", legacyFailure)
            }
            sanitizedAuditLog
        }
        val migrated = normalizeValues(legacyValues)
        check(persist(migrated)) { "Unable to persist migrated secure preferences '$name'" }
        check(context.deleteSharedPreferences(name)) { "Unable to remove legacy secure preferences '$name'" }
        return migrated
    }

    @Suppress("DEPRECATION")
    private fun readLegacyEncryptedPreferences(
        context: Context,
        name: String,
        masterKeyAlias: String
    ): Map<String, *> {
        // security-crypto 1.1.0 deprecated its API in favor of Android Keystore. This single
        // compatibility bridge is retained only to decrypt existing on-device files once.
        val legacyMasterKey = MasterKey.Builder(context, masterKeyAlias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            legacyMasterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).all
    }

    override fun getAll(): MutableMap<String, *> = synchronized(lock) {
        values.mapValuesTo(linkedMapOf()) { (_, value) -> copyValue(value) }
    }

    override fun getString(key: String?, defValue: String?): String? = value(key) as String? ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val stored = value(key) as Set<*>? ?: return defValues?.toMutableSet()
        @Suppress("UNCHECKED_CAST")
        return (stored as Set<String>).toMutableSet()
    }

    override fun getInt(key: String?, defValue: Int): Int = value(key) as Int? ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = value(key) as Long? ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = value(key) as Float? ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = value(key) as Boolean? ?: defValue

    override fun contains(key: String?): Boolean = key != null && synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.remove(listener)
    }

    private fun value(key: String?): Any? = if (key == null) null else synchronized(lock) { values[key] }

    private fun persist(next: Map<String, Any>): Boolean = try {
        val ciphertext = encrypt(encodeValues(next))
        backing.edit().putString(DATA_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit()
    } catch (error: Exception) {
        Log.e(TAG, "Unable to persist secure preferences '$name'", error)
        false
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        return AndroidKeystoreAesGcm.encrypt(keyAlias, name.toByteArray(StandardCharsets.UTF_8), plaintext)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        return AndroidKeystoreAesGcm.decrypt(keyAlias, name.toByteArray(StandardCharsets.UTF_8), payload)
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            put(key, values?.toSet())

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                changes.remove(key)
                removals.add(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean = updateAndPersist(waitForDisk = true)

        override fun apply() {
            updateAndPersist(waitForDisk = false)
        }

        private fun updateAndPersist(waitForDisk: Boolean): Boolean {
            val (write, changedKeys) = synchronized(lock) {
                val before = values
                val next = if (clearRequested) linkedMapOf() else before.toMutableMap()
                removals.forEach(next::remove)
                changes.forEach { (key, value) ->
                    if (value == null) next.remove(key) else next[key] = copyValue(value)
                }
                val snapshot = next.toMap()
                values = snapshot
                val future = writeExecutor.submit<Boolean> {
                    val persisted = persist(snapshot)
                    if (!persisted) Log.e(TAG, "Asynchronous write failed for secure preferences '$name'")
                    persisted
                }
                future to (before.keys + snapshot.keys).filter { before[it] != snapshot[it] }.toSet()
            }
            changedKeys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@KeystoreSharedPreferences, key) } }
            if (!waitForDisk) return true
            return try {
                write.get()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (error: Exception) {
                Log.e(TAG, "Synchronous write failed for secure preferences '$name'", error)
                false
            }
        }

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) {
                removals.remove(key)
                changes[key] = value?.let(::copyValue)
            }
            return this
        }
    }

    companion object {
        private const val DATA_KEY = "encrypted_payload_v2"
        private const val AUDIT_LOG_KEY = "audit_log"
        private const val TAG = "SecurePreferences"
        private const val MAX_SERIALIZED_BYTES = 2 * 1024 * 1024

        private fun normalizeValues(values: Map<String, *>): Map<String, Any> = buildMap {
            values.forEach { (key, value) ->
                if (key.isNotEmpty() && value != null) {
                    when (value) {
                        is String, is Int, is Long, is Float, is Boolean -> put(key, value)
                        is Set<*> -> if (value.all { it is String }) put(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }
        }

        private fun copyValue(value: Any): Any = when (value) {
            is Set<*> -> value.filterIsInstance<String>().toSet()
            else -> value
        }

        internal fun encodeValues(values: Map<String, Any>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(values.size)
                values.toSortedMap().forEach { (key, value) ->
                    writeString(output, key)
                    when (value) {
                        is String -> { output.writeByte(TYPE_STRING); writeString(output, value) }
                        is Int -> { output.writeByte(TYPE_INT); output.writeInt(value) }
                        is Long -> { output.writeByte(TYPE_LONG); output.writeLong(value) }
                        is Float -> { output.writeByte(TYPE_FLOAT); output.writeFloat(value) }
                        is Boolean -> { output.writeByte(TYPE_BOOLEAN); output.writeBoolean(value) }
                        is Set<*> -> {
                            output.writeByte(TYPE_STRING_SET)
                            val entries = value.filterIsInstance<String>().sorted()
                            output.writeInt(entries.size)
                            entries.forEach { writeString(output, it) }
                        }
                        else -> throw IllegalArgumentException("Unsupported preference value: ${value.javaClass.name}")
                    }
                }
            }
            return bytes.toByteArray().also { require(it.size <= MAX_SERIALIZED_BYTES) { "Secure preferences exceed the supported size" } }
        }

        internal fun decodeValues(bytes: ByteArray): Map<String, Any> {
            require(bytes.size <= MAX_SERIALIZED_BYTES) { "Secure preferences exceed the supported size" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val count = input.readInt()
                require(count in 0..MAX_ENTRY_COUNT) { "Invalid secure preference entry count" }
                val result = linkedMapOf<String, Any>()
                repeat(count) {
                    val key = readString(input)
                    require(key.isNotEmpty() && !result.containsKey(key)) { "Invalid or duplicate secure preference key" }
                    val value: Any = when (input.readUnsignedByte()) {
                        TYPE_STRING -> readString(input)
                        TYPE_INT -> input.readInt()
                        TYPE_LONG -> input.readLong()
                        TYPE_FLOAT -> input.readFloat()
                        TYPE_BOOLEAN -> input.readBoolean()
                        TYPE_STRING_SET -> {
                            val size = input.readInt()
                            require(size in 0..MAX_ENTRY_COUNT) { "Invalid secure string-set size" }
                            buildSet { repeat(size) { add(readString(input)) } }
                        }
                        else -> throw IllegalArgumentException("Unknown secure preference value type")
                    }
                    result[key] = value
                }
                require(input.available() == 0) { "Trailing bytes in secure preferences payload" }
                return result
            }
        }

        private fun writeString(output: DataOutputStream, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_SERIALIZED_BYTES) { "Secure preference string is too large" }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        private fun readString(input: DataInputStream): String {
            val length = input.readInt()
            require(length in 0..MAX_SERIALIZED_BYTES && length <= input.available()) { "Invalid secure preference string length" }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private const val MAX_ENTRY_COUNT = 100_000
        private const val TYPE_STRING = 1
        private const val TYPE_INT = 2
        private const val TYPE_LONG = 3
        private const val TYPE_FLOAT = 4
        private const val TYPE_BOOLEAN = 5
        private const val TYPE_STRING_SET = 6
    }
}
