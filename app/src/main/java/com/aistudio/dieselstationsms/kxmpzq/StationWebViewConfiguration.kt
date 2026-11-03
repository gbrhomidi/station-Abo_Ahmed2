package com.aistudio.dieselstationsms.kxmpzq

import android.webkit.WebSettings

/**
 * Single source of truth for the main local-assets WebView.
 *
 * The app stores browser preferences through DOM Storage and business records through the
 * native SQLite bridge. It does not use the obsolete WebSQL openDatabase() API, so WebSQL
 * is deliberately not enabled.
 */
internal object StationWebViewConfiguration {
    @Suppress("DEPRECATION")
    fun configure(settings: WebSettings) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false

            // file access is needed for file:///android_asset; cross-file and cross-origin
            // JavaScript access remains explicitly disabled.
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
        }
    }
}
