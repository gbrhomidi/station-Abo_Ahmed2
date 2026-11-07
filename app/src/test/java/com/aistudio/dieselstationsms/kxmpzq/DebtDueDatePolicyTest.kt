package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.sms.DebtDueDateRepository
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsOutboxRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DebtDueDatePolicyTest {
    private lateinit var context:Context
    private lateinit var helper:DatabaseHelper

    @Before fun setUp() {
        context=ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper=DatabaseHelper.getInstance(context)
    }
    @After fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test fun parsesOnlyExplicitEnglishArabicAndZeroTerms() {
        assertEquals(30,DebtDueDateRepository.parseTermsDays("50% مقدم، صافي 30 يوم"))
        assertEquals(30,DebtDueDateRepository.parseTermsDays("NET 30"))
        assertEquals(30,DebtDueDateRepository.parseTermsDays("صافي ٣٠ يوم"))
        assertEquals(15,DebtDueDateRepository.parseTermsDays("خلال 15 يوماً"))
        assertEquals(0,DebtDueDateRepository.parseTermsDays("صافي 0 يوم"))
        assertEquals(0,DebtDueDateRepository.parseTermsDays("نقدي"))
        assertNull(DebtDueDateRepository.parseTermsDays(""))
        assertNull(DebtDueDateRepository.parseTermsDays("50% مقدم"))
        assertNull(DebtDueDateRepository.parseTermsDays("شروط غير واضحة"))
        assertNull(DebtDueDateRepository.parseTermsDays("NET 999 days"))
    }

    @Test fun creditRiskDoesNotOverrideMaturityState() {
        val today=LocalDate.parse("2026-11-05")
        assertEquals("current",DebtDueDateRepository.statusFor(100.0,0.0,"2026-11-15","critical",today))
        assertEquals("overdue",DebtDueDateRepository.statusFor(100.0,0.0,"2026-11-04","normal",today))
        assertEquals("undated",DebtDueDateRepository.statusFor(100.0,0.0,"","normal",today))
        assertEquals("paid",DebtDueDateRepository.statusFor(0.0,100.0,"2026-11-04","critical",today))
    }

    @Test fun sqliteCreatesNormalizedTermsAndVersionFourTriggers() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.readableDatabase
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='payment_terms'",null).use { it.moveToFirst() })
        assertEquals(7,db.rawQuery("SELECT COUNT(*) FROM payment_terms WHERE is_active=1",null).use { it.moveToFirst();it.getInt(0) })
        assertEquals(30,db.rawQuery("SELECT days FROM payment_terms WHERE code='NET_30'",null).use { it.moveToFirst();it.getInt(0) })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='trg_debt_due_date_sales_insert_v4'",null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='trg_debt_due_date_sales_update_v4'",null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("PRAGMA table_info(parties)",null).use { c -> var found=false;while(c.moveToNext()) if(c.getString(c.getColumnIndexOrThrow("name"))=="payment_terms_source") found=true;found })
        assertTrue(db.rawQuery("PRAGMA table_info(sales_transactions)",null).use { c -> var found=false;while(c.moveToNext()) if(c.getString(c.getColumnIndexOrThrow("name"))=="payment_terms_source_at_sale") found=true;found })
    }

    @Test fun schemaInitializationDoesNotBackfillHistoricalSales() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_insert_v4")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_update_v4")
        db.execSQL("PRAGMA foreign_keys=OFF")
        val partyId=addParty(db,"historical-no-backfill","صافي 30 يوم",30,"explicit_text")
        val saleId=addSale(db,"historical-no-backfill",partyId,null,null,"explicit_text")

        DebtDueDateRepository.ensureSchema(helper)

        db.rawQuery(
            "SELECT due_date,due_date_source FROM sales_transactions WHERE id=?",
            arrayOf(saleId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertTrue("schema setup must not write a historical due date", cursor.isNull(0))
            assertTrue("schema setup must not label a historical reconstruction", cursor.isNull(1))
        }
        assertFalse(
            "historical migration bookkeeping must not be created by the read/schema path",
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='debt_due_date_migrations'",
                null
            ).use { it.moveToFirst() }
        )
    }

    @Test fun unknownLegacyTermsAreNotEligibleButExplicitTermsCreateDueDates() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val legacyParty=addParty(db,"old-default","صافي 30 يوم",30,null)
        val explicitParty=addParty(db,"explicit","صافي 15 يوم",15,"explicit_text")
        val legacySale=addSale(db,"old-due",legacyParty,"2026-10-01","sale_creation")
        val explicitSale=addSale(db,"new-explicit",explicitParty,null,null)

        val rows=DebtDueDateRepository.getCustomerDebts(helper,1)
        val old=rows.getJSONObject(0)
        val current=rows.getJSONObject(1)
        assertEquals(legacySale,old.getLong("transaction_id"))
        assertEquals("",old.getString("due_date"))
        assertEquals("undated",old.getString("status"))
        assertEquals(explicitSale,current.getLong("transaction_id"))
        assertEquals("2026-11-16",current.getString("due_date"))
        assertEquals("explicit_text",db.rawQuery("SELECT payment_terms_source_at_sale FROM sales_transactions WHERE id=?",arrayOf(explicitSale.toString())).use { it.moveToFirst();it.getString(0) })
        assertEquals("sales_trigger_v4",db.rawQuery("SELECT due_date_source FROM sales_transactions WHERE id=?",arrayOf(explicitSale.toString())).use { it.moveToFirst();it.getString(0) })
        assertEquals(1,helper.getCustomerDebts(null,null,1).length())
        val dashboard=helper.getDashboardStatsForDate(1,JSONObject(),"2026-11-06")
        assertEquals(0,dashboard.getInt("overdue_count"))
        assertEquals(0,dashboard.getInt("due_invoices"))
        val taskRows=helper.getPendingTasks(1,JSONObject().put("limit",5000))
        val oldTask=(0 until taskRows.length()).map { taskRows.getJSONObject(it) }
            .first { it.optString("source_kind")=="credit_sale" && it.optLong("source_id")==legacySale }
        assertEquals("بحاجة مراجعة",oldTask.getString("status"))
        assertEquals("",oldTask.getString("task_date"))
    }

    @Test fun legacyReminderQueriesMatchKotlinTrustedDateEligibility() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val today=LocalDate.parse(BusinessDay.currentDate())
        val past=today.minusDays(1).toString()
        val future=today.plusDays(5).toString()
        val unknownParty=addParty(db,"untrusted-reminder","NET 30",30,null)
        val overdueParty=addParty(db,"trusted-overdue","NET 30",30,"explicit_text")
        val soonParty=addParty(db,"trusted-soon","NET 30",30,"explicit_text")
        val unknownSale=addSale(db,"untrusted-overdue",unknownParty,past,"sale_creation")
        val trustedOverdue=addSale(db,"trusted-overdue",overdueParty,past,"sale_creation","explicit_text")
        val trustedSoon=addSale(db,"trusted-soon",soonParty,future,"sale_creation","explicit_text")

        val overdueRows=helper.getOverduePayments(1)
        val overdueIds=(0 until overdueRows.length())
            .map { overdueRows.getJSONObject(it).getLong("id") }.toSet()
        assertEquals(setOf(trustedOverdue),overdueIds)

        val soonRows=helper.getDueSoonDebtReminders(1,10)
        val sqliteSoonIds=(0 until soonRows.length()).map { soonRows.getJSONObject(it).getLong("transaction_id") }.toSet()
        val kotlinSoon=DebtDueDateRepository.getDueSoonDebtReminders(helper,1,10)
        val kotlinSoonIds=(0 until kotlinSoon.length()).map { kotlinSoon.getJSONObject(it).getLong("transaction_id") }.toSet()
        assertEquals(setOf(trustedSoon),sqliteSoonIds)
        assertEquals(sqliteSoonIds,kotlinSoonIds)

        fun pageIds(status:String)=helper.getDebtRemindersPage(1,status).let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getLong("debt_id") }.toSet()
        }
        assertEquals(setOf(trustedOverdue),pageIds("overdue"))
        assertEquals(setOf(trustedSoon),pageIds("pending"))
        assertEquals(setOf(unknownSale),pageIds("undated"))
        val undated=helper.getDebtRemindersPage(1,"undated").getJSONObject(0)
        assertTrue(undated.optString("due_date").isBlank())
    }

    @Test fun outboxDebtReminderIsRevalidatedAgainstCurrentBalanceAndPhone() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val originalPhone="967771234567"
        val partyId=addParty(db,"outbox-revalidation","صافي 0 يوم",0,"explicit_text")
        db.insertOrThrow("party_contacts",null,ContentValues().apply {
            put("uuid","contact-outbox-${System.nanoTime()}");put("party_id",partyId)
            put("contact_name","Outbox test");put("phone",originalPhone)
            put("is_primary",1);put("is_active",1);put("is_deleted",0)
        })
        val today=BusinessDay.currentDate()
        addSale(db,"outbox-revalidation",partyId,today,"sale_creation","explicit_text")

        val secondPhone="967771112222"
        val secondPartyId=addParty(db,"outbox-revalidation-second","صافي 0 يوم",0,"explicit_text")
        db.insertOrThrow("party_contacts",null,ContentValues().apply {
            put("uuid","contact-outbox-second-${System.nanoTime()}");put("party_id",secondPartyId)
            put("contact_name","Outbox test second");put("phone",secondPhone)
            put("is_primary",1);put("is_active",1);put("is_deleted",0)
        })
        addSale(db,"outbox-revalidation-second",secondPartyId,today,"sale_creation","explicit_text")

        val reminderRows=DebtDueDateRepository.getDueSoonDebtReminders(helper,1,0)
        val candidates=(0 until reminderRows.length()).map { reminderRows.getJSONObject(it) }
        val candidate=candidates.first { it.getLong("customer_party_id")==partyId }
        val secondCandidate=candidates.first { it.getLong("customer_party_id")==secondPartyId }
        val eventId=candidate.getString("event_id")
        val entityId="party:$partyId"
        val body="مرحباً، موعد استحقاق الدين هو اليوم ($today). المبلغ المستحق اليوم 100 وإجمالي الرصيد القائم 100 ر. ي."
        assertTrue(DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))

        val batch=(1..100).map { index ->
            val source=if (index % 2 == 0) candidate else secondCandidate
            val phone=if (index % 2 == 0) originalPhone else secondPhone
            SmsOutboxRepository.OutboxMessage(
                messageId="batch-validation-$index",
                recipient=phone,
                body=body,
                partsCount=1,
                attemptCount=0,
                subscriptionId=null,
                eventId=source.getString("event_id"),
                businessEntityId="party:${source.getLong("customer_party_id")}"
            )
        }
        assertEquals(
            "All current reminders in a 100-message batch should validate from the station/customer-scoped read",
            batch.map { it.messageId }.toSet(),
            DebtDueDateRepository.validateDebtReminderBatch(helper,batch)
        )

        db.update("sales_transactions",ContentValues().apply {
            put("paid_amount",100.0);put("remaining_amount",0.0);put("payment_status","paid")
        },"id=?",arrayOf(candidate.getLong("transaction_id").toString()))
        assertFalse("A fully paid debt must not reach SMS transport",DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))

        db.update("sales_transactions",ContentValues().apply {
            put("paid_amount",0.0);put("remaining_amount",100.0);put("payment_status","pending")
        },"id=?",arrayOf(candidate.getLong("transaction_id").toString()))
        db.update("sales_transactions",ContentValues().apply {
            put("paid_amount",50.0);put("remaining_amount",50.0);put("payment_status","partial")
        },"id=?",arrayOf(candidate.getLong("transaction_id").toString()))
        assertFalse("A partially paid debt must not send a stale amount",DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))
        db.update("sales_transactions",ContentValues().apply {
            put("paid_amount",0.0);put("remaining_amount",100.0);put("payment_status","pending")
        },"id=?",arrayOf(candidate.getLong("transaction_id").toString()))
        db.update("party_contacts",ContentValues().apply { put("phone","967779999999") },"party_id=?",arrayOf(partyId.toString()))
        assertFalse("A queued message must not be sent to the customer's old phone",DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))
    }

    private fun addParty(db:android.database.sqlite.SQLiteDatabase,key:String,terms:String,days:Int,source:String?):Long {
        val values=ContentValues().apply {
            put("uuid","party-$key");put("party_code","P-$key");put("party_type_id",1);put("station_id",1)
            put("commercial_name_ar",key);put("payment_terms",terms);put("payment_terms_days",days)
            if(source!=null) put("payment_terms_source",source) else putNull("payment_terms_source")
            put("is_active",1);put("is_deleted",0)
        }
        return db.insertOrThrow("parties",null,values)
    }

    private fun addSale(db:android.database.sqlite.SQLiteDatabase,key:String,partyId:Long,dueDate:String?,dueSource:String?,termsSourceAtSale:String?=null):Long {
        val salesColumns=db.rawQuery("PRAGMA table_info(sales_transactions)",null).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        val values=ContentValues().apply {
            put("uuid","sale-$key");put("sale_code","SALE-$key");put("station_id",1);put("shift_id",1)
            put("customer_party_id",partyId);put("subtotal",100.0);put("gross_amount",100.0);put("net_amount",100.0)
            put("payment_method","credit");put("payment_status","pending");put("is_credit",1);put("paid_amount",0.0);put("remaining_amount",100.0)
            put("status","completed");put("cashier_id",1);put("is_deleted",0)
            listOf("business_day","transaction_date","sale_date").firstOrNull { it in salesColumns }?.let { put(it,"2026-11-01") }
            if(dueDate!=null) put("due_date",dueDate)
            if(dueSource!=null) put("due_date_source",dueSource)
            if(termsSourceAtSale!=null) put("payment_terms_source_at_sale",termsSourceAtSale)
        }
        return db.insertOrThrow("sales_transactions",null,values)
    }
}
