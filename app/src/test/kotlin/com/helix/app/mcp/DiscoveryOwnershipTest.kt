package com.helix.app.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DiscoveryOwnershipTest {
    private class Store : ExecutionOwnership.Store {
        var value: Set<ExecutionOwnership.Owner> = emptySet()

        override fun owners() = value

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
        ): Boolean {
            if (value != expected) return false
            value = replacement
            return true
        }
    }

    @Test fun retainedJobCanDiscoverAndCollectWithoutAdmittingAnUnrelatedWriter() {
        val registry = ToolRegistry()
        val ownership = ExecutionOwnership(Store())
        val owner = ExecutionOwnership.Owner("execution", "job")
        val discovery = McpToolDiscovery(registry)
        discovery.register(registry, ownership::metadataExecutor)
        val collect =
            TimeNowTool.descriptor().copy(
                name = ToolName("code.linux.job.collect"),
                operationClass = ToolOperationClass.LOCAL_MUTATION,
            )
        registry.register(
            collect,
            ownership.controlExecutor({ owner }) { _, permit ->
                assertTrue(requireNotNull(permit).settle())
                ToolExecutorResult.Completed(buildJsonObject {})
            },
        )
        discovery.visible("s", registry.all())
        requireNotNull(ownership.acquire("start")).use { assertTrue(it.retain(owner)) }
        requireNotNull(ownership.acquire("unrelated-write")).close()
        val call =
            ExecutableToolCall(
                "search",
                "tools.search",
                "1",
                Json.parseToJsonElement("""{"query":"code.linux.job.collect"}""").jsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(30),
                NoCancellation,
                "s",
                "t",
            )
        val result = ownership.guard(registry.executor(ToolName("tools.search"), ToolVersion(1))).execute(call)
        assertTrue(result is ToolExecutorResult.Completed)
        assertTrue(collect in discovery.visible("s", registry.all()))
        requireNotNull(ownership.acquire("still-blocked")).close()
        val collected =
            ownership.guard(registry.executor(collect.name, collect.version)).execute(
                call.copy(toolCallId = "collect", toolName = collect.name.value, args = buildJsonObject {}),
            )
        assertTrue(collected is ToolExecutorResult.Completed)
        assertNull(ownership.retainedOwners().singleOrNull())
    }

    @Test fun exposureReadsAvailabilityOncePerDescriptor() {
        val registry = ToolRegistry()
        val descriptors = (0 until 100).map { TimeNowTool.descriptor().copy(name = ToolName("read_$it")) }
        var reads = 0
        val discovery =
            McpToolDiscovery(registry) { _, _ ->
                reads++
                true
            }
        discovery.visible("s", descriptors, setOf("read_1"))
        assertEquals(descriptors.size, reads)
    }
}
