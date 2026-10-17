package com.helix.tools.framework

import com.helix.core.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolDiscoveryContractTest {
    @Test fun publishedCompanionsAreImmutableAndDoNotChangeExecutionAuthority() {
        val base = TestFixtures.builtIn()
        val companions = mutableSetOf(ToolName("sample.observe"))
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
        assertEquals(setOf(ToolName("sample.observe")), published.discoveryCompanions)
        assertEquals(base.contractHash, published.contractHash)
        assertEquals(base.requiredCapabilities, published.requiredCapabilities)
        assertThrows(UnsupportedOperationException::class.java) {
            (published.discoveryCompanions as MutableSet).clear()
        }
    }
}
