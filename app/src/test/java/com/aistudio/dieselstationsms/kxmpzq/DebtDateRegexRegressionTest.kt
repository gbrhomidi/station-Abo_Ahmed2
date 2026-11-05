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
}
