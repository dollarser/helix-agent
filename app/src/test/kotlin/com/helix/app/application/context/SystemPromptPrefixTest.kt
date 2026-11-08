package com.helix.app.application.context

import com.helix.app.infrastructure.prompt.packagedPromptTemplates
import com.helix.app.plugin.PluginReadinessContext
import com.helix.core.agent.TrustLevel
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.workspace.FileScopePath
import com.helix.extensions.plugin.NativePluginState
import com.helix.extensions.plugin.PluginContentContribution
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import com.helix.provider.openai.responses.ResponsesRequestEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptPrefixTest {
    private val mapper = SystemPromptContext(packagedPromptTemplates)
    private val request =
        SystemPromptRequest(
            "session",
            "turn",
            FileScopePath("workspace", "start"),
            AgentMode.ACT,
            setOf("read", "skills.read"),
        )
    private val owner = NativePluginState("plugin", "runtime", "1", "a".repeat(64), true, 1)

    @Test
    fun requestFreshDirectoryAndPluginFactsFollowStableGuidanceAcrossLongSequence() {
        val baseline = snapshot(0)
        val stable = baseline.sections.takeWhile { it.name != "env.directory" }
        assertEquals(listOf("env.base", "env.files", "tool.presentation"), stable.map { it.name })
        val prefix = stable.joinToString("\n\n") { it.content }
        repeat(64) { step ->
            val current = snapshot(step)
            assertTrue(current.content.startsWith(prefix))
            assertTrue(current.content.contains("scope:workspace:step-$step"))
            assertTrue(current.content.contains("plugin guidance $step"))
            assertTrue(current.content.contains("skill description $step"))
            val readiness = if (step % 2 == 0) "AVAILABLE" else "HOST_UNAVAILABLE"
            assertTrue(current.content.contains("\"browserHost\":\"$readiness\""))
            assertEquals(stable, current.sections.take(stable.size))
            assertTrue(
                current.sections
                    .filter { it.name.startsWith("plugin.") || it.name.startsWith("skill.") }
                    .all { it.trust == TrustLevel.UNTRUSTED },
            )
            if (step > 0) {
                assertFalse(current.content.contains("scope:workspace:step-${step - 1}\n"))
                assertFalse(current.content.contains("plugin guidance ${step - 1}\n"))
                assertFalse(current.content.contains("\"description\":\"skill description ${step - 1}\""))
            }
        }
        val revoked = request.copy(toolNames = emptySet(), directory = FileScopePath("workspace", "revoked"))
        val current = mapper.build(revoked, SystemPromptFacts(revoked))
        assertFalse(current.sections.any { it.name in setOf("env.files", "env.directory", "tool.presentation") })
        assertFalse(current.content.contains("plugin guidance"))
        assertFalse(current.content.contains("skill description"))
    }

    @Test
    fun everyModeKeepsDirectoryFreshAfterStableBuiltIns() {
        for (mode in AgentMode.values()) {
            val current = request.copy(mode = mode)
            val moved = current.copy(directory = FileScopePath("other", "next"))
            val before = mapper.build(current, SystemPromptFacts(current))
            val after = mapper.build(moved, SystemPromptFacts(moved))
            assertEquals(
                before.sections.filter { it.name != "env.directory" },
                after.sections.filter { it.name != "env.directory" },
            )
            assertEquals(mode == AgentMode.PLAN, after.sections.any { it.name == "env.plan" })
            assertTrue(after.content.contains("scope:other:next"))
            assertFalse(after.content.contains("scope:workspace:start"))
            assertEquals("env.directory", after.sections.last().name)
        }
    }

    @Test
    fun appendingLongHistoryLeavesExistingEncodedInputPrefixUnchanged() {
        val system = snapshot(0).content
        val encoder = ResponsesRequestEncoder { error("no images") }
        var history = listOf(ModelMessage(ModelRole.SYSTEM, system), ModelMessage(ModelRole.USER, "Start"))
        repeat(64) { step ->
            val previous = Json.parseToJsonElement(encoder.encode(ModelRequest("fixture", history))).jsonObject
            history = history +
                listOf(
                    ModelMessage(ModelRole.ASSISTANT, "Completed step-$step"),
                    ModelMessage(ModelRole.USER, "Continue after step-$step"),
                )
            val next = Json.parseToJsonElement(encoder.encode(ModelRequest("fixture", history))).jsonObject
            assertEquals(previous["input"]!!.jsonArray.toList(), next["input"]!!.jsonArray.dropLast(2))
        }
    }

    private fun snapshot(step: Int): com.helix.core.agent.PromptSnapshot {
        val current = request.copy(directory = FileScopePath("workspace", "step-$step"))
        val contributions =
            listOf(
                PluginContentContribution.RequiredInstructions(owner, "plugin guidance $step", "revision-$step"),
                PluginContentContribution.SkillCatalog(
                    SkillKey(SkillSource.BUILT_IN, "fixture", "b".repeat(64)),
                    "skill description $step",
                    owner,
                ),
            )
        val sections =
            PromptContentContributions.sections(
                contributions,
                null,
                PluginReadinessContext(emptyList(), 0, step % 2 == 0, 0, 0),
                current.toolNames,
            )
        return mapper.build(current, SystemPromptFacts(current), sections)
    }
}
