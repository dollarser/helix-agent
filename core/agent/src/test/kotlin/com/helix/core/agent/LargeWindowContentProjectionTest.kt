package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.provider.api.ProviderContextSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Large-window projection contracts, not provider-tokenizer or model-retrieval quality claims. */
class LargeWindowContentProjectionTest {
    @Test fun nearPressureKeepsRequiredStateAndSelectsWholeOptionalBodiesAt256KAnd1M() {
        for (window in listOf(262_144L, 1_048_576L)) {
            val original = pressuredRequest(window)
            val settings = ProviderContextSettings(manualWindow = window)
            val projected = ContextCompiler.projectContent(original, settings)
            val projectedPrompt = requireNotNull(projected.prompt)
            assertEquals(listOf("required", "activation", "catalog"), projectedPrompt.sections.map { it.name })
            val omitted = projectedPrompt.omittedSections.single()
            val originalOptional = requireNotNull(original.prompt).sections.last()
            assertEquals("memory", omitted.name)
            assertEquals(PromptOmissionReason.CONTENT_BUDGET, omitted.omissionReason)
            assertEquals(originalOptional.content, omitted.content)
            assertEquals(originalOptional.identity, omitted.identity)
            assertEquals(originalOptional.readReference, omitted.readReference)
            assertEquals(original.messages.drop(1), projected.messages.drop(1))
            assertEquals(original.tools, projected.tools)
            assertTrue(projected.inputTokens() > window / 2)
            assertFalse(ContextCompiler.needsPlan(projected, settings, false, 0))
            projected.modelRequest()
        }
    }

    @Test fun repeatedProjectionAndHistoryReductionDoNotRereadOrChangeSourceVersions() {
        var reads = 0
        val original = pressuredRequest(262_144L) { reads++ }
        val initialReads = reads
        val settings = ProviderContextSettings(manualWindow = 262_144L)
        val projected = ContextCompiler.projectContent(original, settings)
        assertEquals(projected, ContextCompiler.projectContent(projected, settings))
        val shorter = projected.copy(messages = projected.messages.take(1) + projected.messages.takeLast(3))
        val restored = ContextCompiler.projectContent(shorter, settings)
        assertEquals(initialReads, reads)
        val restoredPrompt = requireNotNull(restored.prompt)
        assertTrue(restoredPrompt.omittedSections.isEmpty())
        assertEquals(original.prompt, restored.prompt)
        assertEquals(shorter.messages.drop(1), restored.messages.drop(1))
        assertEquals(restoredPrompt.content, restored.messages.first().text)
    }

    @Test fun calibratedLargeWindowProjectionIsIsolatedFromOtherRunsAndFreshSnapshots() {
        val original = pressuredRequest(262_144L)
        val settings = ProviderContextSettings(manualWindow = 262_144L)
        val before = ContextCompiler.projectContent(original, settings)
        val calibrated = ContextCompiler.projectContent(original, settings, inputScale = 1.1)
        assertEquals(listOf("required", "activation"), calibrated.prompt!!.sections.map { it.name })
        assertEquals(original.messages.drop(1), calibrated.messages.drop(1))
        assertEquals(original.tools, calibrated.tools)
        val fresh = request(snapshot("new-source"), emptyList())
        val other = ContextCompiler.projectContent(fresh, ProviderContextSettings(manualWindow = 1_048_576L))
        val otherPrompt = requireNotNull(other.prompt)
        assertTrue(otherPrompt.sections.all { it.identity?.revision == "new-source" })
        assertFalse(otherPrompt.sections.any { it.identity?.revision == "old-source" })
        assertEquals(before, ContextCompiler.projectContent(original, settings))
    }

    private fun pressuredRequest(
        window: Long,
        onRead: () -> Unit = {},
    ): TurnContextRequest {
        val snapshot = snapshot("old-source", onRead)
        val protected =
            assemblePromptSnapshot(
                snapshot.sections.filter { it.retention == PromptContentRetention.REQUIRED },
                emptyList(),
            )
        val base = request(protected, emptyList())
        // Leave room for the small catalog, but not the whole larger optional memory body.
        val fillerTokens = window * 80 / 100 - base.maxOutputTokens - base.inputTokens() - 6000
        val history =
            "H".repeat((fillerTokens * 4).toInt()).chunked(60_000).map {
                ModelMessage(ModelRole.ASSISTANT, it)
            }
        return request(snapshot, history)
    }

    private fun snapshot(
        revision: String,
        onRead: () -> Unit = {},
    ): PromptSnapshot {
        val registry = PromptRegistry()
        listOf("required", "activation", "catalog", "memory").forEachIndexed { index, name ->
            registry.register(
                PromptSection(
                    name,
                    index,
                    PromptScope.SKILL,
                    if (index == 0) PromptSource.BUILTIN_TEMPLATE else PromptSource.EXTERNAL_CONTENT,
                    retention = if (index < 2) PromptContentRetention.REQUIRED else PromptContentRetention.OPTIONAL,
                    priority = if (name == "catalog") 100 else 50,
                    identity = PromptContentIdentity(name, revision, "resource-$name"),
                    readReference = if (name == "memory") PromptContentReadReference("memory.read", "{}") else null,
                ) {
                    onRead()
                    "$name:$revision\n" + "C".repeat(if (name == "memory") 65_536 else 8192)
                },
            )
        }
        return registry.resolveAndAssemble()
    }

    private fun request(
        snapshot: PromptSnapshot,
        history: List<ModelMessage>,
    ): TurnContextRequest {
        val call = AssistantToolCall(ToolCallId("original-call"), ToolName("read"), """{"path":"source.txt"}""")
        val tail =
            listOf(
                ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                ModelMessage(
                    ModelRole.TOOL,
                    "Observed source value: 12.50 mm",
                    toolCallId = call.id,
                    toolName = call.name,
                ),
                ModelMessage(
                    ModelRole.USER,
                    "Correction: preserve exactly 12.50 mm. I have not accepted an approximation.",
                    images = listOf(ImageReference(ArtifactRef("original-image"), "image/png")),
                ),
            )
        return TurnContextRequest(
            "fixture",
            snapshot.modelMessages() + history + tail,
            listOf(ModelToolSchema(ToolName("read"), "Read the selected source.", """{"type":"object"}""")),
            16_384,
            ReasoningEffort.OFF,
            snapshot,
        )
    }
}
