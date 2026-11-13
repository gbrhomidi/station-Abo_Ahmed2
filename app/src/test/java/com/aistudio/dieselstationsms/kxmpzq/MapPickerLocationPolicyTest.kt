package com.aistudio.dieselstationsms.kxmpzq

import android.location.Location
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapPickerLocationPolicyTest {
    @Test
    fun cachedLocationMustBeRecentAndNotFutureDated() {
        val now = 1_800_000_000_000L
        val location = Location("gps").apply { latitude = 15.4; longitude = 44.2 }

        location.time = now - 60_000L
        assertTrue(MapPickerLocationPolicy.isRecentCachedLocation(location, now))

        location.time = now - MapPickerLocationPolicy.MAX_CACHED_AGE_MILLIS - 1L
        assertFalse(MapPickerLocationPolicy.isRecentCachedLocation(location, now))

        location.time = now + 1L
        assertFalse(MapPickerLocationPolicy.isRecentCachedLocation(location, now))
    }

    @Test
    fun cachedLocationRejectsInvalidAgePolicy() {
        val location = Location("network").apply { time = 1_800_000_000_000L }
        assertFalse(MapPickerLocationPolicy.isRecentCachedLocation(location, location.time, -1L))
    }
}
