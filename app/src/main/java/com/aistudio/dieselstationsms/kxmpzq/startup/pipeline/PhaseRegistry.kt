package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

class PhaseRegistry {
    private val phases = mutableMapOf<String, InitializationPhase>()

    fun register(phase: InitializationPhase) { phases[phase.name] = phase }
    fun get(name: String): InitializationPhase = phases[name] ?: throw IllegalArgumentException("Phase not found: $name")
    fun getAll(): List<InitializationPhase> = phases.values.toList()
    fun contains(name: String): Boolean = name in phases
}
