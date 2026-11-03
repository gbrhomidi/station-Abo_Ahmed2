package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import com.aistudio.dieselstationsms.kxmpzq.startup.InitializationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DagEngineTest {
    private class TestPhase(
        override val name: String,
        override val dependencies: List<String> = emptyList()
    ) : InitializationPhase {
        override suspend fun execute(ctx: InitializationContext): PhaseResult =
            PhaseResult.Success()
    }

    @Test
    fun sortsPhasesAfterTheirDependencies() {
        val result = DagEngine(
            listOf(
                TestPhase("publish", listOf("validate")),
                TestPhase("validate", listOf("load")),
                TestPhase("load")
            )
        ).validateAndSort()

        assertTrue(result.isValid)
        assertEquals(listOf("load", "validate", "publish"), result.executionOrder)
    }

    @Test
    fun rejectsMissingDependency() {
        val result = DagEngine(listOf(TestPhase("publish", listOf("missing"))))
            .validateAndSort()

        assertFalse(result.isValid)
        assertTrue(result.executionOrder.isEmpty())
        assertTrue(result.error.orEmpty().contains("Missing dependency"))
    }

    @Test
    fun rejectsCircularDependency() {
        val result = DagEngine(
            listOf(
                TestPhase("first", listOf("second")),
                TestPhase("second", listOf("first"))
            )
        ).validateAndSort()

        assertFalse(result.isValid)
        assertTrue(result.executionOrder.isEmpty())
        assertEquals("Circular dependency detected", result.error)
    }
}
