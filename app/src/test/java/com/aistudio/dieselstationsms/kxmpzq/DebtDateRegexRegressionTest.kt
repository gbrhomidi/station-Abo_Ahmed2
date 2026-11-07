package com.aistudio.dieselstationsms.kxmpzq

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.sms.DebtDueDateRepository
import com.aistudio.dieselstationsms.kxmpzq.utils.IsoCivilDate
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebtDateRegexRegressionTest {
    @Test
    fun allDebtScreensLoadAndUseTheSharedCalendarValidator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val screens = listOf(
            "screens/customer-debts.html",
            "screens/debt-reminders.html",
            "screens/customer-reports.html",
            "screens/customers.html"
        )
        screens.forEach { path ->
            val html = context.assets.open(path).bufferedReader().use { it.readText() }
            assertTrue("$path must load the packaged shared validator", html.contains("assets-local/js/iso-civil-date.js"))
            assertTrue("$path must call the shared validator", html.contains("IsoCivilDate."))
        }
    }

    @Test
    fun strictIsoCivilDateParserValidatesCalendarDaysRatherThanOnlyTheShape() {
        listOf("2024-02-29", "2000-02-29", "0001-01-01", "9999-12-31").forEach {
            assertTrue("$it should be a valid civil date", IsoCivilDate.isValid(it))
        }
        listOf(
            "2023-02-29", "1900-02-29", "2024-02-31", "2024-04-31", "2024-13-01",
            "2024-00-10", "2024-01-32", "2024-1-09", "09/01/2024", "2024-01-09x",
            "2024-01-09 ", " 2024-01-09", "2024-01-09\n", "2024-01-09 12:00:00", "0000-01-01"
        ).forEach {
            assertFalse("$it must not pass as a date-only value", IsoCivilDate.isValid(it))
            assertNull(IsoCivilDate.parse(it))
        }
        assertNull(IsoCivilDate.parse(null))
        IsoCivilDate.requireOptionalRange(null, null)
        IsoCivilDate.requireOptionalRange("", "2024-02-29")
        IsoCivilDate.requireOptionalRange("2024-02-29", "2024-02-29")
        assertFalse(runCatching { IsoCivilDate.requireOptionalRange("2024-02-31", null) }.isSuccess)
        assertFalse(runCatching { IsoCivilDate.requireOptionalRange("2024-03-01", "2024-02-29") }.isSuccess)
        assertFalse(runCatching { IsoCivilDate.requireOptionalRange(" ", null) }.isSuccess)
    }

    @Test
    fun nativeDebtAndReportBoundariesRejectInvalidDatesBeforeQueryingSqlite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = DatabaseHelper.getInstance(context)
        assertFalse(runCatching { helper.getCustomerDebts("2024-02-31", null, 1) }.isSuccess)
        assertFalse(runCatching {
            DebtDueDateRepository.getCustomerDebts(helper, 1, "2024-02-31", null)
        }.isSuccess)
        assertFalse(runCatching {
            helper.generateCRMReport(JSONObject().put("report_type", "activity").put("start_date", "2024-01-01 "))
        }.isSuccess)
        assertFalse(runCatching {
            helper.getDashboardStatsForDate(1, JSONObject().put("from_date", "2024-03-01").put("to_date", "2024-02-29"), "2024-03-01")
        }.isSuccess)
        assertFalse(runCatching {
            helper.getDashboardStatsForDate(1, JSONObject(), "2024-02-30")
        }.isSuccess)
    }
}
