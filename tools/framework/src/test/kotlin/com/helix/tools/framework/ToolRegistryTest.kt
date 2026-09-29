package com.helix.tools.framework

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
    private val executor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall) =
                ToolExecutorResult.Completed(kotlinx.serialization.json.buildJsonObject {})
        }

    private fun registryFromSources(sources: List<ToolSource> = emptyList()): ToolRegistry =
        ToolRegistry().also { registry ->
            registry.registerBatch(sources.flatMap { it.load() }.map { ToolBinding(it, executor) })
        }

    private fun ToolRegistry.registerTest(descriptor: ToolDescriptor) = register(descriptor, executor)

    @Test
    fun constructsFromSourcesAndResolves() {
        val registry =
            registryFromSources(
                listOf(
                    BuiltInToolSource(
                        listOf(
                            builtIn(name = "read"),
                            builtIn(name = "write", operationClass = ToolOperationClass.LOCAL_MUTATION),
                        ),
                    ),
                    McpToolSource("wikipedia", "2025-03-26", listOf(mcpSpec("search"))),
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
        val registry = registryFromSources(listOf(BuiltInToolSource(listOf(builtIn()))))
        assertThrows(IllegalArgumentException::class.java) {
            registry.resolve(ToolName("nope"), ToolVersion(1))
        }
        assertNull(registry.resolveLatest(ToolName("nope")))
    }

    @Test
    fun duplicateRegistrationFails() {
        // same (name, version) through two sources
        assertThrows(IllegalArgumentException::class.java) {
            registryFromSources(
                listOf(
                    BuiltInToolSource(listOf(builtIn(name = "read"))),
                    BuiltInToolSource(listOf(builtIn(name = "read"))),
                ),
            )
        }
        // dynamic registration of an existing (name, version)
        val registry = registryFromSources(listOf(BuiltInToolSource(listOf(builtIn(name = "read")))))
        assertThrows(IllegalArgumentException::class.java) {
            registry.registerTest(builtIn(name = "read"))
        }
        // the registry is unchanged after the failed registration
        assertEquals(1, registry.all().size)
    }

    @Test
    fun versionEvolutionIsLegalAndResolvesToTheLatest() {
        val registry = registryFromSources(emptyList())
        registry.registerTest(builtIn(name = "read", version = 1))
        registry.registerTest(builtIn(name = "read", version = 2))
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
            registryFromSources(
                listOf(
                    BuiltInToolSource(
                        listOf(
                            builtIn(
                                name = "read",
                                operationClass = ToolOperationClass.READ_ONLY,
                            ),
                            builtIn(
                                name = "peek",
                                operationClass = ToolOperationClass.LOCAL_MUTATION,
                            ),
                            builtIn(
                                name = "fetch",
                                operationClass = ToolOperationClass.NETWORK,
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
            registryFromSources(
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
        val registry = registryFromSources(emptyList())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (0 until 8).map { worker ->
                    pool.submit {
                        (0 until 50).forEach { i ->
                            registry.registerTest(
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
