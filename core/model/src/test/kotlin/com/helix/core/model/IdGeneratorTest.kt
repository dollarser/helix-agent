package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdGeneratorTest {
    private class SequentialIdGenerator : IdGenerator {
        private var counter = 0

        override fun next(): String {
            counter += 1
            return "id%04d".format(counter)
        }
    }

    @Test
    fun randomGeneratorProducesValidDistinctValues() {
        val generator = RandomIdGenerator()
        val seen = mutableSetOf<String>()
        repeat(20_000) {
            val value = generator.next()
            assertEquals(32, value.length)
            assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue("duplicate value $value", seen.add(value))
            // Generated values must satisfy the domain ID invariants.
            TurnId(value)
            CorrelationId(value)
        }
    }

    @Test
    fun deterministicGeneratorGivesReproducibleIds() {
        val generator = SequentialIdGenerator()
        val turnId = TurnId(generator.next())
        val executionId = ExecutionId(generator.next())
        assertEquals("id0001", turnId.value)
        assertEquals("id0002", executionId.value)
    }
}
