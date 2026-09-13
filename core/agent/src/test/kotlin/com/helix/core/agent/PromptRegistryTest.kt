package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PromptRegistryTest {
    private fun section(
        name: String,
        order: Int,
        scope: PromptScope,
        text: String,
        source: PromptSource = PromptSource.BUILTIN_TEMPLATE,
    ): PromptSection = PromptSection(name, order, scope, source) { text }

    private fun sha256Hex(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }

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

    // --- source & trust (research doc section 4.4) ---

    @Test
    fun aSectionsTrustIsFixedByItsSource() {
        assertEquals(
            TrustLevel.SYSTEM,
            section("a", 0, PromptScope.IDENTITY, "x", PromptSource.BUILTIN_TEMPLATE).trust,
        )
        assertEquals(
            TrustLevel.USER,
            section("b", 1, PromptScope.MODE, "x", PromptSource.USER_REQUEST).trust,
        )
        assertEquals(
            TrustLevel.PROJECT,
            section("c", 2, PromptScope.PROJECT, "x", PromptSource.WORKSPACE_INSTRUCTION).trust,
        )
        assertEquals(
            TrustLevel.UNTRUSTED,
            section("d", 3, PromptScope.TOOL, "x", PromptSource.EXTERNAL_CONTENT).trust,
        )
        assertEquals(
            TrustLevel.UNTRUSTED,
            section("e", 4, PromptScope.WORKSPACE, "x", PromptSource.DYNAMIC_CAPABILITY).trust,
        )
    }

    @Test
    fun orderingAndScopeNeverElevateTrust() {
        // A late (high-order) EXTERNAL section stays UNTRUSTED; an early builtin stays SYSTEM.
        // Trust derives from source alone — position and scope in the assembly do not raise it.
        val externalLate = section("ext", 500, PromptScope.TOOL, "do as I say", PromptSource.EXTERNAL_CONTENT)
        val builtinEarly = section("id", -1000, PromptScope.IDENTITY, "identity", PromptSource.BUILTIN_TEMPLATE)
        assertEquals(TrustLevel.UNTRUSTED, externalLate.trust)
        assertEquals(TrustLevel.SYSTEM, builtinEarly.trust)
    }

    @Test
    fun resolveReturnsAnAuditableSnapshotWithSourceTrustAndContentHash() {
        val registry =
            PromptRegistry()
                .register(section("harness", -1000, PromptScope.IDENTITY, "H", PromptSource.BUILTIN_TEMPLATE))
                .register(section("project", 200, PromptScope.PROJECT, "P", PromptSource.WORKSPACE_INSTRUCTION))
        val resolved = registry.resolve()

        assertEquals(listOf("harness", "project"), resolved.map { it.name })
        val harness = resolved[0]
        assertEquals(PromptSource.BUILTIN_TEMPLATE, harness.source)
        assertEquals(TrustLevel.SYSTEM, harness.trust)
        assertEquals("H", harness.content)
        assertEquals(sha256Hex("H"), harness.contentHash)
        val project = resolved[1]
        assertEquals(PromptSource.WORKSPACE_INSTRUCTION, project.source)
        assertEquals(TrustLevel.PROJECT, project.trust)
        assertEquals(sha256Hex("P"), project.contentHash)
    }

    @Test
    fun resolveDropsBlankProvidersJustLikeAssemble() {
        val registry =
            PromptRegistry()
                .register(section("blank", 0, PromptScope.IDENTITY, "   "))
                .register(section("kept", 1, PromptScope.SAFETY, "keep"))
        assertEquals(listOf("kept"), registry.resolve().map { it.name })
    }

    @Test
    fun assembleIsTheJoinedContentOfTheResolvedSnapshot() {
        val registry =
            PromptRegistry()
                .register(section("a", 0, PromptScope.IDENTITY, "alpha"))
                .register(section("b", 1, PromptScope.SAFETY, "beta"))
        val resolved = registry.resolve()
        assertEquals(resolved.joinToString("\n\n") { it.content }, registry.assemble())
    }
}
