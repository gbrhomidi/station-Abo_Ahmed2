package com.aistudio.dieselstationsms.kxmpzq.startup.event

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupEvent
import kotlinx.coroutines.flow.Flow

/**
 * Event bus abstraction for the startup subsystem.
 *
 * Provides a simple, non-blocking API for publishing startup events
 * and exposing them as a read-only Flow to observers.
 */
interface EventBus {

    /**
     * Publishes a startup event.
     *
     * Implementations are responsible for determining how the event
     * is dispatched and buffered.
     */
    fun emit(event: StartupEvent)

    /**
     * Returns a read-only stream of startup events.
     *
     * The returned Flow is intended to be collected by components
     * interested in startup lifecycle events.
     */
    fun events(): Flow<StartupEvent>
}