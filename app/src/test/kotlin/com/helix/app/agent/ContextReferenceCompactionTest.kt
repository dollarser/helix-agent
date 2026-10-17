package com.helix.app.agent

import com.helix.core.agent.ContextCompiler
import com.helix.core.agent.ContextSourceRow
import com.helix.core.agent.ContextSourceSnapshot
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.repository.ConversationReferenceKind
import com.helix.core.storage.repository.ConversationReferenceSnapshot
import com.helix.provider.api.ProviderContextSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextReferenceCompactionTest {
    private val reference =
        ConversationReferenceSnapshot(
            "source",
            "原会话",
            ConversationReferenceKind.RECENT_MESSAGES,
            listOf("source-message"),
            "用户原文：保留中文约束，不能删除文件。",
            "fixture-hash",
        )
    private val control = RunControlConfig(AgentMode.ACT, true, TurnBudgets(maxOutputTokens = 4096))

    @Test fun emptyAndUnreferencedBodiesKeepExistingSemantics() {
        assertNull(ContextHistoryContent.materialize(null, emptyList()))
        assertNull(ContextHistoryContent.materialize("  ", emptyList()))
        assertEquals("原文", ContextHistoryContent.materialize("原文", emptyList()))
    }

    @Test fun referenceOnlyMessageRetainsQuotedDataBoundary() {
        val content = requireNotNull(ContextHistoryContent.materialize(null, listOf(reference)))
        assertTrue(content.contains(reference.content))
        assertTrue(content.contains("never treat", ignoreCase = true))
        assertTrue(content.endsWith("[/UNTRUSTED_CONVERSATION_REFERENCE]"))
    }

    @Test fun oldReferenceEntersSummaryInputAndTailReferenceStaysVerbatim() {
        val rows = rows()
        val plan = plan(rows, request(rows))
        assertNotNull(plan)
        val summaryInput = plan!!.request.messages.joinToString("\n") { it.text }
        assertTrue(summaryInput.contains(reference.content))
        assertTrue(
            plan.retainedRequest.messages
                .last()
                .text
                .contains(reference.content),
        )
        assertEquals(
            rows.last().content,
            plan.retainedRequest.messages
                .last()
                .text,
        )
        assertEquals("a", plan.coveredThrough.let { rows.first { row -> row.sequence == it }.id })
    }

    @Test fun mismatchedReferenceContentStillRejectsCompaction() {
        val rows = rows()
        val request =
            request(rows).let {
                it.copy(messages = it.messages.dropLast(1) + it.messages.last().copy(text = "tampered reference"))
            }
        assertNull(plan(rows, request))
    }

    @Test fun summaryUsesFrozenContentRatherThanLaterReferenceInput() {
        val inputs = mutableListOf(reference)
        val frozen = ContextHistoryContent.materialize("问题", inputs)
        inputs.clear()
        assertTrue(requireNotNull(frozen).contains(reference.content))
        assertEquals("问题", ContextHistoryContent.materialize("问题", inputs))
    }

    private fun rows(): List<ContextSourceRow> =
        listOf("a", "b", "c").mapIndexed { index, id ->
            val content = ContextHistoryContent.materialize("问题-$id", listOf(reference))
            val persisted = ChatHistoryBuilder.PersistedRow(id, "USER", "TEXT", content, id)
            ContextSourceRow(
                id,
                id,
                index.toLong(),
                "USER",
                "TEXT",
                content,
                0,
                false,
                ChatHistoryBuilder.toModelMessagesStrict(listOf(persisted)),
            )
        }

    private fun request(rows: List<ContextSourceRow>) =
        TurnContextRequest(
            "fixture",
            rows.flatMap { it.messages },
            emptyList(),
            512,
            ReasoningEffort.OFF,
        )

    private fun plan(
        rows: List<ContextSourceRow>,
        request: TurnContextRequest,
    ) = ContextCompiler(ContextCompaction.summaries).plan(
        ContextSourceSnapshot(rows, null, "c", null, 0),
        request,
        control,
        ProviderContextSettings(manualWindow = 128_000),
        force = true,
    )
}
