package com.aistudio.dieselstationsms.kxmpzq.startup

import com.aistudio.dieselstationsms.kxmpzq.startup.pipeline.PhaseRegistry
import com.aistudio.dieselstationsms.kxmpzq.startup.policy.BootStartupPolicy
import com.aistudio.dieselstationsms.kxmpzq.startup.policy.ManualStartupPolicy

object StartupPolicyFactory {
    fun create(reason: StartupReason, phaseRegistry: PhaseRegistry): StartupPolicy {
        val phases = when (reason) {
            StartupReason.BOOT -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("BootDelay"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )
            StartupReason.MANUAL, StartupReason.CRASH_RECOVERY -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch")
            )
            else -> listOf(
                phaseRegistry.get("EnvironmentCheck"),
                phaseRegistry.get("BootDelay"),
                phaseRegistry.get("PermissionCheck"),
                phaseRegistry.get("ServiceLaunch"),
                phaseRegistry.get("HealthCheck")
            )
        }
        return when (reason) {
            StartupReason.BOOT -> BootStartupPolicy(phases)
            else -> ManualStartupPolicy(phases)
        }
    }
}
