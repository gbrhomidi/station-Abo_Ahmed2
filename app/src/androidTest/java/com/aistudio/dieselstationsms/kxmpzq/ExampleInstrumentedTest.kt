package com.aistudio.dieselstationsms.kxmpzq

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun useAppContext() {
        val appContext = ApplicationProvider.getApplicationContext<MyApplication>()
        assertEquals("com.aistudio.dieselstationsms.kxmpzq", appContext.packageName)
    }
}
