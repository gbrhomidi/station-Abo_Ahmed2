package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class StartupStateMachine {

    enum class State {
        IDLE, PREPARING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    private val allowedTransitions = mapOf(
        State.IDLE to setOf(State.PREPARING),
        State.PREPARING to setOf(State.RUNNING, State.FAILED, State.CANCELLED),
        State.RUNNING to setOf(State.PAUSED, State.COMPLETED, State.FAILED, State.CANCELLED),
        State.PAUSED to setOf(State.RUNNING, State.CANCELLED),
        State.COMPLETED to setOf(State.IDLE),
        State.FAILED to setOf(State.IDLE),
        State.CANCELLED to setOf(State.IDLE)
    )

    private val currentState = AtomicReference(State.IDLE)
    private val listeners = mutableListOf<(State, State) -> Unit>()
    private val mutex = Mutex()

    suspend fun transition(to: State): Boolean {
        return mutex.withLock {
            val from = currentState.get()
            val allowed = allowedTransitions[from] ?: emptySet()
            if (to in allowed) {
                currentState.set(to)
                listeners.forEach { it(from, to) }
                true
            } else false
        }
    }

    fun getCurrentState(): State = currentState.get()
    fun addListener(listener: (State, State) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (State, State) -> Unit) { listeners.remove(listener) }
}
