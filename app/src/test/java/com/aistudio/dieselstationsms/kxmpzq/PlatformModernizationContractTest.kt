package com.aistudio.dieselstationsms.kxmpzq

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.sms.resolveSmsManagerRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformModernizationContractTest {

    @Test
    @Suppress("DEPRECATION")
    fun localWebViewKeepsRequiredCapabilitiesAndRejectsUnsafeFileOrigins() {
        val webView = WebView(ApplicationProvider.getApplicationContext())
        try {
            StationWebViewConfiguration.configure(webView.settings)
            val settings = webView.settings

            assertTrue(settings.javaScriptEnabled)
            assertTrue(settings.domStorageEnabled)
            assertTrue(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertFalse(settings.allowFileAccessFromFileURLs)
            assertFalse(settings.allowUniversalAccessFromFileURLs)
            assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
            assertTrue(settings.safeBrowsingEnabled)
            assertTrue(settings.loadsImagesAutomatically)
            assertTrue(settings.userAgentString.contains("Chrome/91.0.4472.120"))
        } finally {
            webView.destroy()
        }
    }

    @Test
    fun explicitSmsSubscriptionNeverFallsBackToTheDefaultSim() {
        var defaultProviderCalled = false
        var selectedSubscriptionId: Int? = null

        val route = resolveSmsManagerRoute(
            subscriptionId = 7,
            defaultManager = {
                defaultProviderCalled = true
                "default-sim"
            },
            subscriptionManager = { id ->
                selectedSubscriptionId = id
                "subscription-$id"
            }
        )

        assertEquals("subscription-7", route)
        assertEquals(7, selectedSubscriptionId)
        assertFalse(defaultProviderCalled)
    }

    @Test
    fun absentOrInvalidSmsSubscriptionUsesTheDefaultManager() {
        var defaultCalls = 0
        val defaultProvider = {
            defaultCalls++
            "default-sim"
        }
        val selectedProvider: (Int) -> String = { id -> "subscription-$id" }

        assertEquals("default-sim", resolveSmsManagerRoute(null, defaultProvider, selectedProvider))
        assertEquals("default-sim", resolveSmsManagerRoute(-1, defaultProvider, selectedProvider))
        assertEquals(2, defaultCalls)
    }
}
