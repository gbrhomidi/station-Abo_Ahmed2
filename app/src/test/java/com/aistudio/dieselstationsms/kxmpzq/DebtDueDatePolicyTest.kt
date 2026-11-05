package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.sms.DebtDueDateRepository
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun parsesEnglishArabicAndExplicitZeroTerms() {
        assertEquals(30,DebtDueDateRepository.parseTermsDays("50% مقدم، صافي 30 يوم"))
        assertEquals(30,DebtDueDateRepository.parseTermsDays("NET 30"))
        assertEquals(30,DebtDueDateRepository.parseTermsDays("صافي ٣٠ يوم"))
        assertEquals(15,DebtDueDateRepository.parseTermsDays("خلال 15 يوماً"))
        assertEquals(0,DebtDueDateRepository.parseTermsDays("صافي 0 يوم"))
        assertEquals(0,DebtDueDateRepository.parseTermsDays("نقدي"))
        assertEquals(30,DebtDueDateRepository.parseTermsDays(""))
    }

    @Test fun creditRiskDoesNotOverrideMaturityState() {
        val today=LocalDate.parse("2026-11-05")
        assertEquals("current",DebtDueDateRepository.statusFor(100.0,0.0,"2026-11-15","critical",today))
        assertEquals("overdue",DebtDueDateRepository.statusFor(100.0,0.0,"2026-11-04","normal",today))
        assertEquals("undated",DebtDueDateRepository.statusFor(100.0,0.0,"","normal",today))
        assertEquals("paid",DebtDueDateRepository.statusFor(0.0,100.0,"2026-11-04","critical",today))
    }

    @Test fun sqliteCreatesNormalizedPaymentTermsAndVersionThreeTriggers() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.readableDatabase
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='payment_terms'",null).use { it.moveToFirst() })
        assertEquals(7,db.rawQuery("SELECT COUNT(*) FROM payment_terms WHERE is_active=1",null).use { it.moveToFirst();it.getInt(0) })
        assertEquals(30,db.rawQuery("SELECT days FROM payment_terms WHERE code='NET_30'",null).use { it.moveToFirst();it.getInt(0) })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='trg_debt_due_date_sales_insert_v3'",null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='trg_debt_due_date_sales_update_v3'",null).use { it.moveToFirst() })
    }
}
