package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import java.util.*

class DagEngine(private val phases: List<InitializationPhase>) {

    data class DagResult(val isValid: Boolean, val executionOrder: List<String>, val error: String? = null)

    fun validateAndSort(): DagResult {
        val graph = mutableMapOf<String, MutableSet<String>>()
        val inDegree = mutableMapOf<String, Int>()

        phases.forEach { phase ->
            graph[phase.name] = mutableSetOf()
            inDegree[phase.name] = 0
        }

        phases.forEach { phase ->
            phase.dependencies.forEach { dep ->
                if (dep !in graph) {
                    return DagResult(false, emptyList(), "Missing dependency: $dep for phase ${phase.name}")
                }
                graph[dep]?.add(phase.name)
                inDegree[phase.name] = (inDegree[phase.name] ?: 0) + 1
            }
        }

        val queue = LinkedList<String>()
        val result = mutableListOf<String>()
        inDegree.filter { it.value == 0 }.keys.forEach { queue.add(it) }

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            result.add(current)
            graph[current].orEmpty().forEach { neighbor ->
                inDegree[neighbor] = (inDegree[neighbor] ?: 0) - 1
                if (inDegree[neighbor] == 0) queue.add(neighbor)
            }
        }

        if (result.size != phases.size) {
            return DagResult(false, emptyList(), "Circular dependency detected")
        }
        return DagResult(true, result)
    }

    fun getReadyPhases(completed: Set<String>): List<InitializationPhase> {
        return phases.filter { phase ->
            phase.name !in completed && phase.dependencies.all { it in completed }
        }
    }
}
