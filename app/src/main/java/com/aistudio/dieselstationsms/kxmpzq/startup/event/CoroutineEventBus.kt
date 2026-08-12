package com.aistudio.dieselstationsms.kxmpzq.startup.event

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Coroutine-based event bus for startup events.
 *
 * Events are published through a SharedFlow so multiple consumers
 * can observe startup lifecycle events independently.
 */
class CoroutineEventBus : EventBus {

    private val _events = MutableSharedFlow<StartupEvent>(
        replay = 10,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Publishes a startup event.
     *
     * tryEmit() is intentionally used because event publication must
     * remain non-blocking and must not suspend the startup pipeline.
     */
    override fun emit(event: StartupEvent) {
        _events.tryEmit(event)
    }

    /**
     * Exposes startup events as a read-only Flow.
     */
    override fun events(): Flow<StartupEvent> = _events
}