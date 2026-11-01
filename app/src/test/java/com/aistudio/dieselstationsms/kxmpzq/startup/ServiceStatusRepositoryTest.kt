package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceStatusRepositoryTest {
    @Test
    fun monotonicHeartbeatExpiresAcrossDeviceRestartClockReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("service_status_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val clock = AtomicLong(50_000L)
        val repository = ServiceStatusRepository(context) { clock.get() }
        repository.setRunning(true)
        assertTrue(repository.isRunning())

        // elapsedRealtime resets after reboot; a larger persisted value is stale.
        clock.set(100L)
        assertFalse(repository.isRunning())
        repository.setRunning(false)
        assertFalse(repository.isRunning())
    }
}
