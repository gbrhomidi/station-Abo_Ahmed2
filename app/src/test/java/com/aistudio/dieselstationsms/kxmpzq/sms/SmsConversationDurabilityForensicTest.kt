package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsConversationDurabilityForensicTest {
    private lateinit var context: Context
    private val phone = "967771234567"

    @Before
    fun resetDatabase() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        val db = DatabaseHelper.getInstance(context)
        db.writableDatabase.delete("sms_conversation_context", "phone = ?", arrayOf(phone))
    }

    @Test
    fun `pending context survives the legacy ten minute window`() = runTest {
        val db = DatabaseHelper.getInstance(context)
        val oldTimestamp = System.currentTimeMillis() - 11L * 60L * 1000L
        val expiresAt = System.currentTimeMillis() + 60L * 60L * 1000L
        db.writableDatabase.insertWithOnConflict(
            "sms_conversation_context",
            null,
            android.content.ContentValues().apply {
                put("phone", phone)
                put("last_topic", "diesel")
                put("last_intent", "diesel_request")
                put("timestamp", oldTimestamp)
                put("pending_action", "awaiting_quantity")
                put("awaiting_response", 1)
                put("data_json", "{}")
                put("expires_at", expiresAt)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )

        val contextAfterTenMinutes = SmsConversationManager(db).getOrCreateContext(phone)
        assertTrue(contextAfterTenMinutes.awaitingResponse)
        assertEquals("awaiting_quantity", contextAfterTenMinutes.pendingAction)

        DatabaseHelper.closeInstance()
    }

    @Test
    fun `first diesel draft survives manager recreation`() = runTest {
        val db = DatabaseHelper.getInstance(context)
        val firstManager = SmsConversationManager(db)
        firstManager.updateOrderDraft(phone) {
            product = "diesel"
            step = 1
            status = "draft"
            unitPrice = 100.0
        }
        val firstContext = firstManager.getOrCreateContext(phone).apply {
            awaitingResponse = true
            pendingAction = "awaiting_quantity"
        }
        firstManager.saveContext(phone, firstContext)

        DatabaseHelper.closeInstance()
        val secondDb = DatabaseHelper.getInstance(context)
        val secondManager = SmsConversationManager(secondDb)
        assertNotNull(secondManager.getOrderDraft(phone))
    }
}
