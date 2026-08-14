package com.aistudio.dieselstationsms.kxmpzq.startup.event

import com.aistudio.dieselstationsms.kxmpzq.startup.StartupEvent
import kotlinx.coroutines.flow.Flow

interface EventBus {
    fun emit(event: StartupEvent)
    fun events(): Flow<StartupEvent>
}
