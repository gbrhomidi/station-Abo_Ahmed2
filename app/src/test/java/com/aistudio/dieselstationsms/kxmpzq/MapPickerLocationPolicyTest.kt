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
        val location = Location("gps").apply { latitude = 15.4; longitude = 44.2; accuracy = 35f }

        location.time = now - 60_000L
        assertTrue(MapPickerLocationPolicy.isRecentCachedLocation(location, now))

        location.time = now - MapPickerLocationPolicy.MAX_CACHED_AGE_MILLIS - 1L
        assertFalse(MapPickerLocationPolicy.isRecentCachedLocation(location, now))

        location.time = now + 1L
        assertFalse(MapPickerLocationPolicy.isRecentCachedLocation(location, now))
    }

    @Test
    fun cachedLocationRejectsInvalidAgePolicy() {
        val location = Location("network").apply { time = 1_800_000_000_000L; latitude = 15.4; longitude = 44.2; accuracy = 40f }
        assertFalse(MapPickerLocationPolicy.isRecentCachedLocation(location, location.time, -1L))
    }

    @Test
    fun freshFixMustBeRecentAccurateAndGeographicallyValid() {
        val now = 1_800_000_000_000L
        val location = Location("gps").apply {
            latitude = 15.4
            longitude = 44.2
            time = now - 5_000L
            accuracy = 25f
        }
        assertTrue(MapPickerLocationPolicy.isUsableLocation(location, now))

        location.time = now - MapPickerLocationPolicy.MAX_FRESH_AGE_MILLIS - 1L
        assertFalse(MapPickerLocationPolicy.isUsableLocation(location, now))

        location.time = now
        location.accuracy = MapPickerLocationPolicy.MAX_ACCEPTABLE_ACCURACY_METERS + 1f
        assertFalse(MapPickerLocationPolicy.isUsableLocation(location, now))

        location.accuracy = 25f
        location.latitude = 0.0
        location.longitude = 0.0
        assertFalse(MapPickerLocationPolicy.isUsableLocation(location, now))
    }

    @Test
    fun locationWithoutMeasuredAccuracyIsRejected() {
        val now = 1_800_000_000_000L
        val location = Location("network").apply {
            latitude = 15.4
            longitude = 44.2
            time = now
            accuracy = 0f
        }
        assertFalse(MapPickerLocationPolicy.isUsableLocation(location, now))
    }

}
