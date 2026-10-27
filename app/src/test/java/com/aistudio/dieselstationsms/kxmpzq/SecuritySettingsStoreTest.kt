package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.settings.security.SecuritySettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecuritySettingsStoreTest {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var store: SecuritySettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences(
            "security_settings_test_${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        preferences.edit().clear().commit()
        store = SecuritySettingsStore(preferences)
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun allSevenNonPinControlsPersistEnabledAndDisabledValues() {
        val keys = SecuritySettingsStore.SETTING_KEYS
        assertEquals(
            setOf(
                "requireDeviceUnlock", "encryptStorage", "biometricLockEnabled",
                "hideSensitiveData", "autoLogoutMinutes", "preventScreenshots",
                "secureBackupEnabled"
            ),
            keys
        )
        keys.forEach { key ->
            store.putSetting(key, "1")
            assertEquals("1", store.getSetting(key))
            store.putSetting(key, "0")
            assertEquals("0", store.getSetting(key))
        }
        assertFalse(store.encryptionEnabled())
        store.putSetting(SecuritySettingsStore.KEY_ENCRYPT_STORAGE, "1")
        assertTrue(store.encryptionEnabled())
    }

    @Test
    fun pinIsHashedNeverReturnedAndCanBeVerified() {
        val pin = "573914"
        store.savePinCode(pin)

        assertEquals(SecuritySettingsStore.PIN_MASK, store.pinValueForUi())
        assertTrue(store.isPinConfigured())
        assertFalse(preferences.contains("pinCode"))
        assertFalse(preferences.all.values.any { it?.toString() == pin })
        assertTrue(store.verifyPinCode(pin))
        assertFalse(store.verifyPinCode("573915"))
    }

    @Test
    fun blankOrMaskedPinSaveKeepsExistingCredential() {
        store.savePinCode("4286")
        store.savePinCode("")
        store.savePinCode(SecuritySettingsStore.PIN_MASK)

        assertTrue(store.verifyPinCode("4286"))
        assertFalse(store.verifyPinCode("0000"))
    }

    @Test
    fun fiveInvalidPinAttemptsTemporarilyBlockEvenTheCorrectPin() {
        store.savePinCode("6248")
        repeat(5) { assertFalse(store.verifyPinCode("0000")) }

        assertFalse(store.verifyPinCode("6248"))
        assertEquals(SecuritySettingsStore.PIN_MASK, store.pinValueForUi())
    }

    @Test
    fun pinCredentialCanBeRestoredWithoutExportingThePinText() {
        store.savePinCode("839174")
        val credential = store.exportPinCredential()
        assertTrue(credential.containsKey("salt"))
        assertTrue(credential.containsKey("hash"))
        assertFalse(credential.values.contains("839174"))

        val restoredPrefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("security_settings_restore_test_${UUID.randomUUID()}", Context.MODE_PRIVATE)
        restoredPrefs.edit().clear().commit()
        val restored = SecuritySettingsStore(restoredPrefs)
        restored.importPinCredential(credential)

        assertTrue(restored.verifyPinCode("839174"))
        assertFalse(restored.verifyPinCode("839175"))
        restoredPrefs.edit().clear().commit()
    }
}
