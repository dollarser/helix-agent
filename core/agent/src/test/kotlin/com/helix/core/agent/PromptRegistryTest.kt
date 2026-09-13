package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRegistryTest {
    private fun section(
        name: String,
        order: Int,
        scope: PromptScope,
        text: String,
    ): PromptSection = PromptSection(name, order, scope) { text }

    @Test
    fun aDuplicateNameIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptRegistry().register(section("a", 0, PromptScope.IDENTITY, "x")).register(
                section("a", 1, PromptScope.SAFETY, "y"),
            )
        }
    }

    @Test
    fun itAssemblesInAscendingOrderRegardlessOfRegistrationOrder() {
        val registry =
            PromptRegistry()
                .register(section("project", 200, PromptScope.PROJECT, "P"))
                .register(section("harness", -1000, PromptScope.IDENTITY, "H"))
                .register(section("safety", -900, PromptScope.SAFETY, "S"))
        assertEquals("H\n\nS\n\nP", registry.assemble())
    }

    @Test
    fun orderTiesBreakByName() {
        val registry =
            PromptRegistry()
                .register(section("zeta", 50, PromptScope.MODE, "Z"))
                .register(section("alpha", 50, PromptScope.MODE, "A"))
        assertEquals("A\n\nZ", registry.assemble())
    }

    @Test
    fun blankProvidersAreOmitted() {
        val registry =
            PromptRegistry()
                .register(section("a", 0, PromptScope.IDENTITY, "  "))
                .register(section("b", 1, PromptScope.SAFETY, "keep"))
        assertEquals("keep", registry.assemble())
    }

    @Test
    fun anEmptyRegistryAssemblesToAnEmptyString() {
        assertTrue(PromptRegistry().assemble().isEmpty())
    }

    @Test
    fun orderedSectionsAreSortedByOrderThenName() {
        val registry =
            PromptRegistry()
                .register(section("b", 1, PromptScope.MODE, "x"))
                .register(section("a", 1, PromptScope.MODE, "y"))
                .register(section("c", 0, PromptScope.IDENTITY, "z"))
        assertEquals(listOf("c", "a", "b"), registry.orderedSections().map { it.name })
    }
}
