package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundReauthenticationPolicyTest {

    @Test
    fun backgroundThresholdDoesNotLockBeforeSixtySeconds() {
        val backgroundedAt = 10_000L

        assertFalse(BackgroundReauthenticationPolicy.shouldRequireReauthentication(backgroundedAt, backgroundedAt))
        assertFalse(BackgroundReauthenticationPolicy.shouldRequireReauthentication(backgroundedAt, backgroundedAt + 59_000L))
        assertFalse(BackgroundReauthenticationPolicy.shouldRequireReauthentication(backgroundedAt, backgroundedAt + 59_999L))
    }

    @Test
    fun backgroundThresholdLocksAtExactlySixtySecondsAndAfterwards() {
        val backgroundedAt = 5_000L

        assertTrue(BackgroundReauthenticationPolicy.shouldRequireReauthentication(backgroundedAt, backgroundedAt + 60_000L))
        assertTrue(BackgroundReauthenticationPolicy.shouldRequireReauthentication(backgroundedAt, backgroundedAt + 60_001L))
        assertTrue(BackgroundReauthenticationPolicy.shouldRequireReauthentication(backgroundedAt, backgroundedAt + 5 * 60_000L))
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
    fun restoredAuthenticatedActivityDoesNotPromptBeforeThreshold() {
        val pending = BackgroundReauthenticationPolicy.shouldLockOnStartup(
            hasActiveSession = true,
            loginRequiredOnLaunch = false,
            previouslyPending = false,
            isActivityRecreation = true,
            wasAuthenticatedShell = true,
            backgroundTimeoutReached = false,
            securityReauthenticationEnabled = true
        )
        assertFalse(pending)
    }

    @Test
    fun restoredAuthenticatedActivityPromptsAtSixtySecondsButNotAtFiftyNineSeconds() {
        val beforeThreshold = BackgroundReauthenticationPolicy.shouldLockOnStartup(
            hasActiveSession = true,
            loginRequiredOnLaunch = false,
            previouslyPending = false,
            isActivityRecreation = true,
            wasAuthenticatedShell = true,
            backgroundTimeoutReached = BackgroundReauthenticationPolicy.shouldRequireReauthentication(1_000L, 60_999L),
            securityReauthenticationEnabled = true
        )
        val atThreshold = BackgroundReauthenticationPolicy.shouldLockOnStartup(
            hasActiveSession = true,
            loginRequiredOnLaunch = false,
            previouslyPending = false,
            isActivityRecreation = true,
            wasAuthenticatedShell = true,
            backgroundTimeoutReached = BackgroundReauthenticationPolicy.shouldRequireReauthentication(1_000L, 61_000L),
            securityReauthenticationEnabled = true
        )

        assertFalse(beforeThreshold)
        assertTrue(atThreshold)
    }

    @Test
    fun restoredLoginPageKeepsRememberMeAuthenticationWhenBackgroundThresholdReached() {
        val shouldLock = BackgroundReauthenticationPolicy.shouldLockOnStartup(
            hasActiveSession = true,
            loginRequiredOnLaunch = false,
            previouslyPending = false,
            isActivityRecreation = true,
            wasAuthenticatedShell = false,
            backgroundTimeoutReached = true,
            securityReauthenticationEnabled = true
        )

        assertFalse(shouldLock)
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRestoreAuthenticatedShell(
                hasActiveSession = true,
                loginRequiredOnLaunch = false,
                wasAuthenticatedShell = false,
                securityLockPending = false
            )
        )
    }

    @Test
    fun pendingLockSurvivesActivityRecreationAndFreshLaunchKeepsExistingPolicy() {
        assertTrue(
            BackgroundReauthenticationPolicy.shouldLockOnStartup(
                hasActiveSession = true,
                loginRequiredOnLaunch = false,
                previouslyPending = true,
                isActivityRecreation = true,
                wasAuthenticatedShell = true,
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
                wasAuthenticatedShell = false,
                backgroundTimeoutReached = false,
                securityReauthenticationEnabled = true
            )
        )
    }

    @Test
    fun ActivityRecreationRestoresOnlyThePreviouslyAuthenticatedShell() {
        assertTrue(
            BackgroundReauthenticationPolicy.shouldRestoreAuthenticatedShell(
                hasActiveSession = true,
                loginRequiredOnLaunch = false,
                wasAuthenticatedShell = true,
                securityLockPending = false
            )
        )
        assertTrue(
            BackgroundReauthenticationPolicy.shouldRestoreAuthenticatedShell(
                hasActiveSession = true,
                loginRequiredOnLaunch = false,
                wasAuthenticatedShell = true,
                securityLockPending = true
            )
        )
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRestoreAuthenticatedShell(
                hasActiveSession = true,
                loginRequiredOnLaunch = false,
                wasAuthenticatedShell = false,
                securityLockPending = false
            )
        )
    }

    @Test
    fun explicitLogoutOrMissingSessionStillRoutesToLogin() {
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRestoreAuthenticatedShell(
                hasActiveSession = true,
                loginRequiredOnLaunch = true,
                wasAuthenticatedShell = true,
                securityLockPending = true
            )
        )
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRestoreAuthenticatedShell(
                hasActiveSession = false,
                loginRequiredOnLaunch = false,
                wasAuthenticatedShell = true,
                securityLockPending = true
            )
        )
    }

    @Test
    fun internalActivityTransitionsAndConfigurationChangesDoNotStartBackgroundClock() {
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRecordBackgroundTransition(
                isChangingConfigurations = false,
                isExternalPickerActive = false,
                hasOtherStartedActivity = true
            )
        )
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRecordBackgroundTransition(
                isChangingConfigurations = true,
                isExternalPickerActive = false,
                hasOtherStartedActivity = false
            )
        )
        assertFalse(
            BackgroundReauthenticationPolicy.shouldRecordBackgroundTransition(
                isChangingConfigurations = false,
                isExternalPickerActive = true,
                hasOtherStartedActivity = false
            )
        )
    }

    @Test
    fun realAppBackgroundStartsClockWhenNoOtherActivitiesRemain() {
        assertTrue(
            BackgroundReauthenticationPolicy.shouldRecordBackgroundTransition(
                isChangingConfigurations = false,
                isExternalPickerActive = false,
                hasOtherStartedActivity = false
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
                wasAuthenticatedShell = false,
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
                wasAuthenticatedShell = true,
                backgroundTimeoutReached = true,
                securityReauthenticationEnabled = true
            )
        )
    }
}
