package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

/** Production provider. Release never uses the bypass-capable Debug provider. */
internal object AppCheckInstaller {
    private const val TAG = "AppCheckInstaller"

    fun install(context: Context) {
        val siteKey = BuildConfig.RECAPTCHA_ENTERPRISE_SITE_KEY.trim()
        if (siteKey.isEmpty()) {
            Log.w(TAG, "reCAPTCHA site key is not configured; optional cloud calls remain unavailable")
            return
        }
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
        )
    }
}
