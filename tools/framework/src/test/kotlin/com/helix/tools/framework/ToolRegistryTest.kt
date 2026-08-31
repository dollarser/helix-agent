package com.helix.tools.framework

import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.TestFixtures.builtIn
import com.helix.tools.framework.TestFixtures.mcpSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class ToolRegistryTest {
    @Test
    fun constructsFromSourcesAndResolves() {
        val registry =
            ToolRegistry(
                listOf(
                    BuiltInToolSource(
                        listOf(
                            builtIn(name = "read"),
                            builtIn(name = "write", operationClass = ToolOperationClass.LOCAL_MUTATION),
                        ),
                    ),
                    McpToolSource("wikipedia", 1, listOf(mcpSpec("search"))),
                ),
            )
        assertEquals(3, registry.all().size)
        assertEquals("read", registry.resolve(ToolName("read"), ToolVersion(1)).name.value)
        assertEquals(
            "mcp.wikipedia.search",
            registry.resolve(ToolName("mcp.wikipedia.search"), ToolVersion(1)).name.value,
        )
    }

    @Test
    fun resolvingAnUnknownToolFails() {
        val registry = ToolRegistry(listOf(BuiltInToolSource(listOf(builtIn()))))
        assertThrows(IllegalArgumentException::class.java) {
            registry.resolve(ToolName("nope"), ToolVersion(1))
        }
        assertNull(registry.resolveLatest(ToolName("nope")))
    }

    @Test
    fun duplicateRegistrationFails() {
        // same (name, version) through two sources
        assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry(
                listOf(
                    BuiltInToolSource(listOf(builtIn(name = "read"))),
                    BuiltInToolSource(listOf(builtIn(name = "read"))),
                ),
            )
        }
        // dynamic registration of an existing (name, version)
        val registry = ToolRegistry(listOf(BuiltInToolSource(listOf(builtIn(name = "read")))))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(builtIn(name = "read"))
        }
        // the registry is unchanged after the failed registration
        assertEquals(1, registry.all().size)
    }

    @Test
    fun versionEvolutionIsLegalAndResolvesToTheLatest() {
        val registry = ToolRegistry(emptyList())
        registry.register(builtIn(name = "read", version = 1))
        registry.register(builtIn(name = "read", version = 2))
        assertEquals(2, registry.all().size)
        assertEquals(1, registry.resolve(ToolName("read"), ToolVersion(1)).version.value)
        assertEquals(2, registry.resolve(ToolName("read"), ToolVersion(2)).version.value)
        assertEquals(2, registry.resolveLatest(ToolName("read"))?.version?.value)
        // the mode view shows the LATEST version only
        val view = registry.visibleFor(setOf(ToolOperationClass.READ_ONLY))
        assertEquals(1, view.size)
        assertEquals(2, view.single().version.value)
    }

    @Test
    fun planViewShowsOnlyReadOnlyToolsAndNeverSubstitutesRiskForClass() {
        // a LOCAL_MUTATION tool at dynamic-risk L0 must NOT appear in the
        // Plan (READ_ONLY-only) view: the operation-class filter is primary
        // and a risk-level check cannot substitute it (doc 02 section 7;
        // core:agent ModePolicy enforces the same rule per call).
        val registry =
            ToolRegistry(
                listOf(
                    BuiltInToolSource(
                        listOf(
                            builtIn(
                                name = "read",
                                operationClass = ToolOperationClass.READ_ONLY,
                                baseRisk = RiskLevel.L0,
                            ),
                            builtIn(
                                name = "peek",
                                operationClass = ToolOperationClass.LOCAL_MUTATION,
                                baseRisk = RiskLevel.L0,
                            ),
                            builtIn(
                                name = "fetch",
                                operationClass = ToolOperationClass.NETWORK,
                                baseRisk = RiskLevel.L0,
                            ),
                        ),
                    ),
                ),
            )
        val planView = registry.visibleFor(setOf(ToolOperationClass.READ_ONLY)).map { it.name.value }
        assertEquals(listOf("read"), planView)
        // Act/Goal-style unrestricted view sees everything
        assertEquals(3, registry.visibleFor(ToolOperationClass.entries.toSet()).size)
    }

    @Test
    fun allIsSortedByNameThenVersion() {
        val registry =
            ToolRegistry(
                listOf(
                    BuiltInToolSource(
                        listOf(
                            builtIn(name = "write", version = 2),
                            builtIn(name = "read", version = 2),
                            builtIn(name = "write", version = 1),
                            builtIn(name = "read", version = 1),
                        ),
                    ),
                ),
            )
        assertEquals(
            listOf("read", "read", "write", "write"),
            registry.all().map { it.name.value },
        )
        assertEquals(
            listOf(1, 2, 1, 2),
            registry.all().map { it.version.value },
        )
    }

    @Test
    fun registrationAndResolutionAreThreadSafe() {
        val registry = ToolRegistry(emptyList())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (0 until 8).map { worker ->
                    pool.submit {
                        (0 until 50).forEach { i ->
                            registry.register(
                                builtIn(name = "t$worker", version = i, operationClass = ToolOperationClass.READ_ONLY),
                            )
                            registry.resolve(ToolName("t$worker"), ToolVersion(i))
                        }
                    }
                }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(400, registry.all().size)
        assertTrue(registry.all().size == 400)
    }
}
