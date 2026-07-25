package com.aistudio.dieselstationsms.kxmpzq.startup.event

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupEvent
import kotlinx.coroutines.flow.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class CoroutineEventBus : EventBus {
    private val _events = MutableSharedFlow<StartupEvent>(
        replay = 10, extraBufferCapacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override fun emit(event: StartupEvent) { _events.tryEmit(event) }
    override fun events(): Flow<StartupEvent> = _events
}
