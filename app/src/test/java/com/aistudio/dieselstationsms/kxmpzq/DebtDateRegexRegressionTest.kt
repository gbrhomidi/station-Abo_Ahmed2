package com.aistudio.dieselstationsms.kxmpzq

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebtDateRegexRegressionTest {
    @Test
    fun allDebtScreensUseSingleEscapedIsoDateMatchers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val screens = listOf(
            "screens/customer-debts.html",
            "screens/debt-reminders.html",
            "screens/customer-reports.html",
            "screens/customers.html"
        )
        val valid = "/^\\d{4}-\\d{2}-\\d{2}$/"
        val invalid = "/^\\\\d{4}-\\\\d{2}-\\\\d{2}$/"
        screens.forEach { path ->
            val html = context.assets.open(path).bufferedReader().use { it.readText() }
            assertTrue("$path must validate YYYY-MM-DD dates", html.contains(valid))
            assertFalse("$path must not double-escape the date matcher", html.contains(invalid))
        }
    }

    @Test
    fun debtScreenDateMatchersAcceptIsoShapeAndRejectMalformedValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val screens = listOf(
            "screens/customer-debts.html",
            "screens/debt-reminders.html",
            "screens/customer-reports.html",
            "screens/customers.html"
        )
        val validLiteral = "/^\\d{4}-\\d{2}-\\d{2}$/"
        val accepted = listOf("2026-11-06", "2000-01-31", "2031-12-01")
        val rejected = listOf("2026-1-06", "26-11-06", "2026/11/06", "not-a-date", "")

        screens.forEach { path ->
            val html = context.assets.open(path).bufferedReader().use { it.readText() }
            assertTrue("$path must use the expected date matcher", html.contains(validLiteral))
            val source = validLiteral.removePrefix("/").removeSuffix("/")
            val matcher = java.util.regex.Pattern.compile(source)
            accepted.forEach { value -> assertTrue("$path should accept $value", matcher.matcher(value).matches()) }
            rejected.forEach { value -> assertFalse("$path should reject $value", matcher.matcher(value).matches()) }
        }
    }
}
