package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug-only provider. Debug tokens bypass attestation; never use this in Release. */
internal object AppCheckInstaller {
    fun install(context: Context) {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
    }
}
