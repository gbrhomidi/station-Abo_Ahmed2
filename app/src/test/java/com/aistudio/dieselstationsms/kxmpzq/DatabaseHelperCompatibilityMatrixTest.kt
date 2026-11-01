package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DatabaseHelperCompatibilityMatrixTest {
    @Test
    @Config(sdk = [26, 28, 35])
    fun opensWithForeignKeysAndWalAcrossSupportedAndroidApiLevels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        try {
            val database = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals(DatabaseHelper.VERSION, database.version)
            val foreignKeysEnabled = database.rawQuery("PRAGMA foreign_keys", null).use {
                it.moveToFirst() && it.getInt(0) == 1
            }
            val journalMode = database.rawQuery("PRAGMA journal_mode", null).use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            }
            assertTrue("foreign key enforcement must be enabled", foreignKeysEnabled)
            assertEquals("wal", journalMode.lowercase())
        } finally {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        }
    }
}
