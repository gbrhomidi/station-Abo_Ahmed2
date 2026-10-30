package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundReauthenticationPolicyTest {

    @Test
    fun backgroundThresholdDoesNotLockBeforeSixtySeconds() {
        val backgroundedAt = 10_000L

        assertFalse(
            BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAt,
                backgroundedAt
            )
        )
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAt,
                backgroundedAt + 59_000L
            )
        )
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAt,
                backgroundedAt + 59_999L
            )
        )
    }

    @Test
    fun backgroundThresholdLocksAtExactlySixtySecondsAndAfterwards() {
        val backgroundedAt = 5_000L

        assertTrue(
            BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAt,
                backgroundedAt + 60_000L
            )
        )
        assertTrue(
            BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAt,
                backgroundedAt + 60_001L
            )
        )
        assertTrue(
            BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAt,
                backgroundedAt + 5 * 60_000L
            )
        )
    }

    @Test
    fun missingOrRegressedMonotonicTimestampNeverCreatesAnElapsedInterval() {
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAtElapsedRealtime = null,
                resumedAtElapsedRealtime = 90_000L
            )
        )
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                backgroundedAtElapsedRealtime = 90_000L,
                resumedAtElapsedRealtime = 89_999L
            )
        )
    }

    @Test
    fun restoredActivityDoesNotPromptBeforeThresholdEvenWhenBiometricLockIsEnabled() {
        val pending = BackgroundReauthenticationPolicy.shouldLockOnStartup(
            hasActiveSession = true,
            loginRequiredOnLaunch = false,
            previouslyPending = false,
            isActivityRecreation = true,
            backgroundTimeoutReached = false,
            securityReauthenticationEnabled = true
        )

        assertFalse(pending)
    }

    @Test
    fun restoredActivityPromptsAtSixtySecondsButNotAtFiftyNineSeconds() {
        val common = mapOf(
            "hasActiveSession" to true,
            "loginRequiredOnLaunch" to false,
            "previouslyPending" to false,
            "isActivityRecreation" to true,
            "securityReauthenticationEnabled" to true
        )

        val beforeThreshold = BackgroundReauthenticationPolicy.shouldLockOnStartup(
            hasActiveSession = common.getValue("hasActiveSession"),
            loginRequiredOnLaunch = common.getValue("loginRequiredOnLaunch"),
            previouslyPending = common.getValue("previouslyPending"),
            isActivityRecreation = common.getValue("isActivityRecreation"),
            backgroundTimeoutReached = BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                1_000L,
                60_999L
            ),
            securityReauthenticationEnabled = common.getValue("securityReauthenticationEnabled")
        )
        val atThreshold = BackgroundReauthenticationPolicy.shouldLockOnStartup(
            hasActiveSession = true,
            loginRequiredOnLaunch = false,
            previouslyPending = false,
            isActivityRecreation = true,
            backgroundTimeoutReached = BackgroundReauthenticationPolicy.shouldRequireReauthentication(
                1_000L,
                61_000L
            ),
            securityReauthenticationEnabled = true
        )

        assertFalse(beforeThreshold)
        assertTrue(atThreshold)
    }

    @Test
    fun pendingLockSurvivesActivityRecreationAndFreshLaunchKeepsExistingPolicy() {
        assertTrue(
            BackgroundReauthenticationPolicy.shouldLockOnStartup(
                hasActiveSession = true,
                loginRequiredOnLaunch = false,
                previouslyPending = true,
                isActivityRecreation = true,
                backgroundTimeoutReached = false,
                securityReauthenticationEnabled = true
            )
        )
        assertTrue(
            BackgroundReauthenticationPolicy.shouldLockOnStartup(
                hasActiveSession = true,
                loginRequiredOnLaunch = false,
                previouslyPending = false,
                isActivityRecreation = false,
                backgroundTimeoutReached = false,
                securityReauthenticationEnabled = true
            )
        )
    }

    @Test
    fun missingSessionOrExplicitLoginRequirementDoesNotStartNativeReauthentication() {
        assertFalse(
            BackgroundReauthenticationPolicy.shouldLockOnStartup(
                hasActiveSession = false,
                loginRequiredOnLaunch = false,
                previouslyPending = true,
                isActivityRecreation = false,
                backgroundTimeoutReached = true,
                securityReauthenticationEnabled = true
            )
        )
        assertFalse(
            BackgroundReauthenticationPolicy.shouldLockOnStartup(
                hasActiveSession = true,
                loginRequiredOnLaunch = true,
                previouslyPending = true,
                isActivityRecreation = false,
                backgroundTimeoutReached = true,
                securityReauthenticationEnabled = true
            )
        )
    }
}
