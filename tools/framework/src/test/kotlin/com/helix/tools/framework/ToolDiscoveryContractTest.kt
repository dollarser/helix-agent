package com.helix.tools.framework

import com.helix.core.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolDiscoveryContractTest {
    @Test fun publishedHintsAreImmutableAndNeverChangeAuthority() {
        val base = TestFixtures.builtIn()
        val keywords = mutableSetOf("content search", "查找内容")
        val descriptor = base.copy(discovery = ToolDiscoveryHints("files", keywords))
        val registry = ToolRegistry()
        registry.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult = error("Never executes")
            },
        )
        keywords.clear()
        val published = requireNotNull(registry.resolveLatest(base.name))
        assertEquals(setOf("content search", "查找内容"), published.discovery.keywords)
        assertEquals("files", published.discovery.category)
        assertEquals(base.contractHash, published.contractHash)
        assertThrows(UnsupportedOperationException::class.java) {
            (published.discovery.keywords as MutableSet).clear()
        }
    }

    @Test fun malformedHintsFailAtDeclaration() {
        assertThrows(IllegalArgumentException::class.java) { ToolDiscoveryHints("File category") }
        assertThrows(IllegalArgumentException::class.java) { ToolDiscoveryHints(keywords = setOf("")) }
        assertThrows(IllegalArgumentException::class.java) { ToolDiscoveryHints(keywords = setOf("x".repeat(129))) }
    }

    @Test fun publishedCompanionsAreImmutableAndDoNotChangeExecutionAuthority() {
        val base = TestFixtures.builtIn()
        val companions = mutableSetOf(ToolName("sample_observe"))
        val descriptor = base.copy(discoveryCompanions = companions)
        val registry = ToolRegistry()
        registry.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult = error("Discovery never executes")
            },
        )
        companions.clear()
        val published = requireNotNull(registry.resolveLatest(base.name))
        assertEquals(setOf(ToolName("sample_observe")), published.discoveryCompanions)
        assertEquals(base.contractHash, published.contractHash)
        assertEquals(base.requiredCapabilities, published.requiredCapabilities)
        assertThrows(UnsupportedOperationException::class.java) {
            (published.discoveryCompanions as MutableSet).clear()
        }
    }
}
