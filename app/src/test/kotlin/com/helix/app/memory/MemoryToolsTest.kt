package com.helix.app.memory

import com.helix.core.agent.ModeDecision
import com.helix.core.agent.ModePolicy
import com.helix.core.agent.ToolModeProfile
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.memory.MarkdownMemoryStore
import com.helix.core.workspace.memory.MemoryScope
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class MemoryToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val registry = ToolRegistry()
    private val implementations = ToolImplementationRegistry()
    private val flags = mutableMapOf("enabled" to true, "auto-global" to true)

    private fun service(): MemoryService {
        val service =
            MemoryService(MarkdownMemoryStore(temporary.root.toPath()), { flags[it] ?: false }, { k, v ->
                flags[k] =
                    v
            })
        MemoryTools.register(registry, implementations, service)
        return service
    }

    @Test fun planAllowsDiscoveryButNeverDurableMemoryMutations() {
        service()
        (MemoryTools.reads + MemoryTools.writes).forEach { name ->
            val descriptor = registry.resolve(ToolName(name), ToolVersion(1))
            val decision =
                ModePolicy.evaluate(
                    AgentMode.PLAN,
                    ToolModeProfile(descriptor.operationClass),
                )
            assertEquals(name in MemoryTools.reads, decision is ModeDecision.Allowed)
        }
    }

    @Test fun explicitRememberPersistsMarkdownAndLaterReadDiscoversIt() {
        val service = service()
        assertTrue(
            execute(
                "memory.write",
                """{"scope":"global",
"path":"user.md",
"type":"user",
"source":"user explicit remember",
"body":"Prefer concise Chinese answers",
"expectedHash":"new"}""",
            ) is ToolExecutorResult.Completed,
        )
        val result =
            execute(
                "memory.search",
                """{"scope":"global","query":"Chinese"}""",
            ) as ToolExecutorResult.Completed
        assertTrue(result.output.toString().contains("user.md"))
        assertTrue(execute("memory.read", """{"scope":"global","path":"user.md"}""") is ToolExecutorResult.Completed)
        assertEquals(1, service.list(MemoryScope.Global).size)
    }

    @Test fun disabledSettingAndCancellationPreventWritesAndUnknownProjectCannotFallBackGlobal() {
        val service = service()
        val args = """{"scope":"global",
"path":"user.md",
"type":"user",
"source":"user",
"body":"concise",
"expectedHash":"new"}"""
        assertEquals(
            ToolExecutorResult.Cancelled,
            execute(
                "memory.write",
                args,
                object : CancelSignal {
                    override fun isCancelled() = true
                },
            ),
        )
        flags["auto-global"] = false
        assertTrue(execute("memory.write", args) is ToolExecutorResult.Failed)
        assertTrue(execute("memory.list", """{"scope":"project"}""") is ToolExecutorResult.Failed)
        assertTrue(service.list(MemoryScope.Global).isEmpty())
    }

    private fun execute(
        name: String,
        args: String,
        cancel: CancelSignal = NoCancellation,
    ): ToolExecutorResult =
        requireNotNull(implementations.resolve(ToolName(name), ToolVersion(1))).execute(
            ExecutableToolCall(
                "call",
                name,
                "1",
                Json.parseToJsonElement(args).jsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(30),
                cancel,
                "session",
                "turn",
            ),
        )
}
