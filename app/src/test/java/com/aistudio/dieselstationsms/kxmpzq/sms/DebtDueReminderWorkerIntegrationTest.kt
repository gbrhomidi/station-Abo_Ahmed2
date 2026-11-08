package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.aistudio.dieselstationsms.kxmpzq.BusinessDay
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.startup.DatabaseInitializationCoordinator
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Runs the production debt reminder worker and its actual SQLite/outbox path without carrier I/O. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebtDueReminderWorkerIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: DatabaseHelper
    private var stationId: Int = 1

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseInitializationCoordinator.resetForTests()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        database = DatabaseHelper.getInstance(context)
        stationId = database.readableDatabase.rawQuery(
            "SELECT id FROM stations ORDER BY id LIMIT 1", null
        ).use { cursor ->
            assertTrue("A seeded station is required for the worker integration", cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    @After
    fun tearDown() {
        runCatching {
            WorkManager.getInstance(context).cancelAllWork().get(10, TimeUnit.SECONDS)
        }
        DatabaseInitializationCoordinator.resetForTests()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun actualWorkerQueuesOnlyInsideConfiguredPreDueWindowAndUpdatesUniqueSchedule() = runBlocking {
        val guardedFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return if (workerClassName == SmsOutboxWorker::class.java.name) {
                    SuppressedSmsOutboxWorker(appContext, workerParameters)
                } else {
                    null
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(guardedFactory).build()
        )
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.SEND_SMS)

        val db = database.writableDatabase
        val userId = ensureTestUser(db)
        val today = LocalDate.parse(BusinessDay.currentDate())
        val (inWindowCustomer, inWindowSale) = insertDebtFixture(db, "in-window")
        val (outsideCustomer, outsideSale) = insertDebtFixture(db, "outside-window")
        val phoneInWindow = "967771234567"
        val phoneOutside = "967771765432"

        val partyColumns = columns(db, "parties")
        val legacyPhoneColumn = listOf("phone", "phone_number", "mobile", "phone1")
            .firstOrNull { it in partyColumns }
        listOf(inWindowCustomer to phoneInWindow, outsideCustomer to phoneOutside).forEachIndexed { index, pair ->
            legacyPhoneColumn?.let { column ->
                db.update(
                    "parties",
                    ContentValues().apply { put(column, pair.second) },
                    "id=? AND station_id=?",
                    arrayOf(pair.first.toString(), stationId.toString())
                )
            }
            db.insertOrThrow("party_contacts", null, ContentValues().apply {
                put("uuid", "worker-pre-due-contact-${System.nanoTime()}-$index")
                put("party_id", pair.first)
                put("contact_name", "Pre-due worker test $index")
                put("phone", pair.second)
                put("is_primary", 1)
                put("is_active", 1)
                put("is_deleted", 0)
            })
        }

        val salesColumns = columns(db, "sales_transactions")
        val inWindowDate = today.plusDays(2).toString()
        val outsideDate = today.plusDays(4).toString()
        val inWindowUpdate = ContentValues().apply {
            put("due_date", inWindowDate)
            put("due_date_source", "sale_creation")
            if ("due_date_basis" in salesColumns) put("due_date_basis", today.toString())
            if ("payment_terms_days_at_sale" in salesColumns) put("payment_terms_days_at_sale", 2)
            if ("payment_terms_source_at_sale" in salesColumns) put("payment_terms_source_at_sale", "explicit_text")
            if ("payment_terms_at_sale" in salesColumns) put("payment_terms_at_sale", "صافي 2 يوم")
        }
        val outsideUpdate = ContentValues(inWindowUpdate).apply {
            put("due_date", outsideDate)
            if ("due_date_basis" in salesColumns) put("due_date_basis", today.toString())
            if ("payment_terms_days_at_sale" in salesColumns) put("payment_terms_days_at_sale", 4)
            if ("payment_terms_at_sale" in salesColumns) put("payment_terms_at_sale", "صافي 4 أيام")
        }
        assertEquals(1, db.update("sales_transactions", inWindowUpdate, "id=? AND station_id=?",
            arrayOf(inWindowSale.toString(), stationId.toString())))
        assertEquals(1, db.update("sales_transactions", outsideUpdate, "id=? AND station_id=?",
            arrayOf(outsideSale.toString(), stationId.toString())))

        database.setSetting("sms_send_enabled", "1")
        database.setSetting("debt_reminder_days_before_due", "3")

        DebtDueReminderWorker.schedule(context, stationId, userId)
        DebtDueReminderWorker.schedule(context, stationId, userId + 1L)
        val scheduled = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("debt-due-sms-reminders-station-$stationId")
            .get(10, TimeUnit.SECONDS)
        assertEquals("Station reminder scheduling must remain unique after a user/session change", 1, scheduled.size)

        val requestData = Data.Builder()
            .putInt("debt_reminder_station_id", stationId)
            .putLong("debt_reminder_user_id", userId)
            .build()
        val worker = TestListenableWorkerBuilder.from(context, DebtDueReminderWorker::class.java)
            .setInputData(requestData)
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM sms_reminders WHERE transaction_id=? AND days_before_due=2 AND status='pending'",
            arrayOf(inWindowSale.toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM sms_reminders WHERE transaction_id=?",
            arrayOf(outsideSale.toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })

        val queued = db.rawQuery(
            """SELECT o.status,o.body,o.event_id FROM sms_outbox o
               WHERE o.business_entity_id=? ORDER BY o.queued_at DESC LIMIT 1""",
            arrayOf("party:$inWindowCustomer")
        ).use {
            assertTrue("The in-window customer must have one durable outbox row", it.moveToFirst())
            Triple(it.getString(0), it.getString(1), it.getString(2))
        }
        assertEquals("QUEUED", queued.first)
        assertTrue(queued.second.contains("يتبقى 2 يوم على موعد استحقاق الدين"))
        assertTrue(queued.third.startsWith("debt-reminder:$stationId:$inWindowCustomer:"))
        assertTrue(queued.third.endsWith(":pre"))

        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM sms_outbox WHERE business_entity_id=?",
            arrayOf("party:$outsideCustomer")
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })
    }

    private class SuppressedSmsOutboxWorker(
        appContext: Context,
        workerParameters: WorkerParameters
    ) : Worker(appContext, workerParameters) {
        override fun doWork(): Result = Result.success()
    }

    private fun ensureTestUser(db: android.database.sqlite.SQLiteDatabase): Long {
        val existing = db.rawQuery(
            "SELECT id FROM users WHERE COALESCE(is_deleted,0)=0 ORDER BY id LIMIT 1", null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        if (existing > 0L) return existing

        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        return db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "debt-reminder-worker-$suffix")
            put("password_hash", "test-hash")
            put("password_salt", "test-salt")
            put("full_name", "Debt Reminder Worker Test")
            put("full_name_ar", "مستخدم اختبار تذكير الديون")
            put("role_id", 1L)
            put("station_id", stationId)
            put("company_id", 1L)
            put("status", "active")
            put("is_deleted", 0)
        })
    }

    private fun insertDebtFixture(
        db: android.database.sqlite.SQLiteDatabase,
        label: String
    ): Pair<Long, Long> {
        db.execSQL("PRAGMA foreign_keys=OFF")
        val suffix = UUID.randomUUID().toString()
        val customerId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", "worker-party-$suffix")
            put("party_code", "WORKER-$suffix")
            put("party_type_id", 1)
            put("station_id", stationId)
            put("commercial_name_ar", "اختبار تذكير $label")
            put("payment_terms", "صافي 30 يوم")
            put("payment_terms_days", 30)
            put("payment_terms_source", "explicit_text")
            put("is_active", 1)
            put("is_deleted", 0)
        })

        val salesColumns = columns(db, "sales_transactions")
        val saleId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", "worker-sale-$suffix")
            put("sale_code", "WORKER-SALE-$suffix")
            put("station_id", stationId)
            put("shift_id", 1)
            put("customer_party_id", customerId)
            put("subtotal", 100.0)
            put("gross_amount", 100.0)
            put("net_amount", 100.0)
            put("payment_method", "credit")
            put("payment_status", "pending")
            put("paid_amount", 0.0)
            put("remaining_amount", 100.0)
            put("status", "completed")
            put("cashier_id", 1)
            put("is_deleted", 0)
            listOf("business_day", "transaction_date", "sale_date")
                .firstOrNull { it in salesColumns }
                ?.let { put(it, BusinessDay.currentDate()) }
            if ("payment_terms_at_sale" in salesColumns) put("payment_terms_at_sale", "صافي 30 يوم")
            if ("payment_terms_source_at_sale" in salesColumns) put("payment_terms_source_at_sale", "explicit_text")
        })
        return customerId to saleId
    }

    private fun columns(db: android.database.sqlite.SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
}
