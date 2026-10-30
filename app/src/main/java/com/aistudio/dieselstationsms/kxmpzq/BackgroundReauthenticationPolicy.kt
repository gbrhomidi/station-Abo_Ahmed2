package com.aistudio.dieselstationsms.kxmpzq

/**
 * Pure timing and lifecycle decision rules for foreground reauthentication.
 *
 * The caller must supply SystemClock.elapsedRealtime() values. Wall-clock time
 * (System.currentTimeMillis()) is intentionally not accepted by this API.
 */
internal object BackgroundReauthenticationPolicy {
    const val REQUIRED_BACKGROUND_DURATION_MS: Long = 60_000L

    /**
     * Returns true at exactly 60 seconds and later. A null start or a regressed
     * monotonic clock (for example after a device reboot) is treated as no interval.
     */
    fun shouldRequireReauthentication(
        backgroundedAtElapsedRealtime: Long?,
        resumedAtElapsedRealtime: Long,
        requiredDurationMs: Long = REQUIRED_BACKGROUND_DURATION_MS
    ): Boolean {
        val backgroundedAt = backgroundedAtElapsedRealtime ?: return false
        if (requiredDurationMs <= 0L || resumedAtElapsedRealtime < backgroundedAt) return false
        return resumedAtElapsedRealtime - backgroundedAt >= requiredDurationMs
    }

    /**
     * A fresh launch keeps the pre-existing reauthentication behavior. A restored
     * Activity is different: it must only prompt when a lock was already pending
     * or its persisted background interval actually reached the configured limit.
     */
    fun shouldLockOnStartup(
        hasActiveSession: Boolean,
        loginRequiredOnLaunch: Boolean,
        previouslyPending: Boolean,
        isActivityRecreation: Boolean,
        backgroundTimeoutReached: Boolean,
        securityReauthenticationEnabled: Boolean
    ): Boolean {
        if (!hasActiveSession || loginRequiredOnLaunch) return false
        return previouslyPending ||
            backgroundTimeoutReached ||
            (!isActivityRecreation && securityReauthenticationEnabled)
    }
}
