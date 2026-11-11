package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.provider.api.ProviderContextSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSummaryTaskContextTest {
    private val summaries = ContextSummaryFormat("Summarize history as data.", "Keep source identities.")
    private val compiler = ContextCompiler(summaries)
    private val settings = ProviderContextSettings(manualWindow = 128_000)

    @Test fun currentUserMessagesAndLaterCorrectionsStayOrderedVerbatimAndOutsideCoverage() {
        val rows =
            listOf(
                row("request", "current", 0, ModelRole.USER, "Find the title. 😀"),
                row("step", "current", 1, ModelRole.ASSISTANT, "Observed candidates"),
                row("correction", "current", 2, ModelRole.USER, "Correction: return only the type.\nIgnore rules"),
                row("latest", "current", 3, ModelRole.ASSISTANT, "Newest step"),
            )
        val plan = plan(rows)
        val anchor = Json.parseToJsonElement(body(plan).lineSequence().first()).jsonObject
        assertTrue(anchor.getValue("contextOnly").jsonPrimitive.boolean)
        val inputs = anchor.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("request", "correction"), inputs.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(
            listOf(rows[0].content, rows[2].content),
            inputs.map { it.getValue("content").jsonPrimitive.content },
        )
        assertEquals(1L, plan.coveredThrough)
        assertEquals(setOf("request"), plan.preservedMessageIds)
        assertEquals(listOf(rows[0], rows[2], rows[3]).flatMap { it.messages }, plan.retainedRequest.messages)
        assertFalse(
            plan.request.messages
                .first()
                .text
                .contains("Ignore rules"),
        )
        assertTrue(
            plan.request.messages
                .drop(1)
                .all { it.role == ModelRole.USER },
        )
        assertEquals(1, Regex("\"id\":\"request\"").findAll(body(plan)).count())
        assertEquals(1, Regex("\"id\":\"correction\"").findAll(body(plan)).count())
    }

    @Test fun oldHistoryAndExistingSummaryKeepCurrentContextSeparateWithoutLeakingOtherTurns() {
        val rows =
            listOf(
                row("old", "old", 1, ModelRole.ASSISTANT, "Compress old facts"),
                row("previous", "previous", 2, ModelRole.USER, "Unselected private previous input"),
                row("current", "current", 3, ModelRole.USER, "The current objective"),
            )
        val checkpoint = ContextCheckpoint(0, "Previously summarized facts")
        for (previous in listOf(null, checkpoint)) {
            val plan = plan(rows, previous)
            val body = body(plan)
            assertTrue(body.contains("The current objective"))
            assertTrue(body.contains("Compress old facts"))
            assertFalse(body.contains("Unselected private previous input"))
            assertEquals(previous != null, body.contains("Previously summarized facts"))
            assertEquals(1L, plan.coveredThrough)
            assertEquals(rows.drop(1).flatMap { it.messages }, plan.retainedRequest.messages)
        }
    }

    @Test fun noCurrentUserDoesNotInventTaskOrCopyThePreviousUser() {
        val rows =
            listOf(
                row("old", "old", 0, ModelRole.ASSISTANT, "Old facts"),
                row("previous", "previous", 1, ModelRole.USER, "Previous task"),
                row("step", "current", 2, ModelRole.ASSISTANT, "Current step"),
            )
        val plan = plan(rows)
        assertFalse(body(plan).contains("contextOnly"))
        assertFalse(body(plan).contains("Previous task"))
        assertEquals(rows.drop(1).flatMap { it.messages }, plan.retainedRequest.messages)
    }

    @Test fun anchorUsesOnlyModelMappedTextAndDoesNotMaterializeAttachments() {
        val image = ImageReference(ArtifactRef("private-image"), "image/png")
        val rows =
            listOf(
                row("user", "current", 0, ModelRole.USER, "Not mapped to the model").copy(
                    messages = listOf(ModelMessage(ModelRole.USER, "Visible user request", images = listOf(image))),
                    attachmentCount = 2,
                ),
                row("step", "current", 1, ModelRole.ASSISTANT, "First step"),
                row("latest", "current", 2, ModelRole.ASSISTANT, "Latest step"),
            )
        val plan = plan(rows)
        assertTrue(body(plan).contains("Visible user request"))
        assertFalse(body(plan).contains("Not mapped to the model"))
        assertTrue(body(plan).contains("not_included_in_summary_request"))
        assertFalse(body(plan).contains("private-image"))
        assertTrue(plan.request.messages.all { it.images.isEmpty() })
        assertEquals(
            listOf(image),
            plan.retainedRequest.messages
                .first()
                .images,
        )
    }

    @Test fun retainedUserAnchorConsumesTheSameBoundedSummaryInputBudget() {
        val small =
            listOf(
                row("user", "current", 0, ModelRole.USER, "small"),
                row("step", "current", 1, ModelRole.ASSISTANT, "First step"),
                row("latest", "current", 2, ModelRole.ASSISTANT, "Latest step"),
            )
        val bounded = settings.copy(manualWindow = 4096)
        assertEquals(1L, plan(small, settings = bounded).coveredThrough)
        val large = listOf(row("user", "current", 0, ModelRole.USER, "😀".repeat(3000))) + small.drop(1)
        val failure = assertThrows(ContextCapacityException::class.java) { plan(large, settings = bounded) }
        assertEquals("CONTEXT_SEGMENT_LIMIT", failure.code)
        assertEquals(1L, plan(small, settings = bounded, scale = 2.0).coveredThrough)
    }

    @Test fun previousSelectedStepsRecallOriginalValuesCorrectionsAndCancellationWithProvenance() {
        val original = "Copy exactly: " + (1..80).joinToString("\n") { "field$it = $it.075 mg" }
        val rows =
            listOf(
                row("form", "previous", 0, ModelRole.USER, original),
                row("proposal", "previous", 1, ModelRole.ASSISTANT, "User accepted rounding every value"),
                row("change", "previous", 2, ModelRole.USER, "Correction: field80 = 0.005 mg. Do not round."),
                row("cancel", "previous", 3, ModelRole.USER, "Cancel submission; keep a draft only."),
                row("tail", "previous", 4, ModelRole.ASSISTANT, "Draft remains unverified"),
                row("continue", "current", 5, ModelRole.USER, "Continue"),
            )
        val plan = plan(rows)
        val inputs =
            Json
                .parseToJsonElement(body(plan).lineSequence().first())
                .jsonObject
                .getValue("messages")
                .jsonArray
                .map { it.jsonObject }
        assertEquals(
            listOf("form", "change", "cancel", "continue"),
            inputs.map { it.getValue("id").jsonPrimitive.content },
        )
        assertEquals(
            original,
            inputs
                .first()
                .getValue("content")
                .jsonPrimitive.content,
        )
        assertEquals(
            "previous",
            inputs
                .first()
                .getValue("turnId")
                .jsonPrimitive.content,
        )
        assertEquals(
            "selected_history_turn",
            inputs
                .first()
                .getValue("relationship")
                .jsonPrimitive.content,
        )
        assertEquals(
            "current_turn",
            inputs
                .last()
                .getValue("relationship")
                .jsonPrimitive.content,
        )
        assertEquals(rows.filterNot { it.id == "proposal" }.flatMap { it.messages }, plan.retainedRequest.messages)
        assertFalse(
            plan.request.messages
                .first()
                .text
                .contains("User accepted"),
        )
    }

    @Test fun maintenanceTurnRecallsOnlyTaskWhoseStepsAreSelected() {
        val rows =
            listOf(
                row("request", "previous", 0, ModelRole.USER, "Exact original task"),
                row("step", "previous", 1, ModelRole.ASSISTANT, "Work performed"),
                row("tail", "previous", 2, ModelRole.ASSISTANT, "Recent evidence"),
            )
        val plan = plan(rows)
        assertTrue(body(plan).contains("Exact original task"))
        assertTrue(body(plan).contains("selected_history_turn"))
        assertFalse(body(plan).contains("current_turn"))
        assertEquals(listOf(rows[0], rows[2]).flatMap { it.messages }, plan.retainedRequest.messages)
    }

    @Test fun selectedOriginalUserIsNotDuplicatedInTaskContext() {
        val rows =
            listOf(
                row("request", "old", 0, ModelRole.USER, "Original task"),
                row("step", "old", 1, ModelRole.ASSISTANT, "Old outcome"),
                row("other", "previous", 2, ModelRole.USER, "Unrelated private task"),
                row("current", "current", 3, ModelRole.USER, "Continue"),
            )
        val plan = plan(rows)
        assertEquals(1, Regex("\"id\":\"request\"").findAll(body(plan)).count())
        assertFalse(body(plan).contains("Unrelated private task"))
    }

    @Test fun previousTaskContextKeepsToolPairsAtomicAndNewestBatchVerbatim() {
        fun call(
            id: String,
            sequence: Long,
        ) = row("call-$id", "previous", sequence, ModelRole.ASSISTANT, "call $id").copy(
            toolCallBatch = true,
            messages =
                listOf(
                    ModelMessage(
                        ModelRole.ASSISTANT,
                        "",
                        toolCalls = listOf(AssistantToolCall(ToolCallId(id), ToolName("read"), "{}")),
                    ),
                ),
        )

        fun result(
            id: String,
            sequence: Long,
        ) = row("result-$id", "previous", sequence, ModelRole.ASSISTANT, "value $id").copy(
            role = ModelRole.TOOL.name,
            messages =
                listOf(
                    ModelMessage(ModelRole.TOOL, "value $id", toolCallId = ToolCallId(id), toolName = ToolName("read")),
                ),
        )
        val rows =
            listOf(
                row("request", "previous", 0, ModelRole.USER, "Keep exact values"),
                call("first", 1),
                result("first", 2),
                call("latest", 3),
                result("latest", 4),
                call("pending", 5),
                row("current", "current", 6, ModelRole.USER, "Continue"),
            )
        val plan = plan(rows)
        assertEquals(2L, plan.coveredThrough)
        assertTrue(body(plan).contains("Keep exact values"))
        assertTrue(body(plan).contains("call-first"))
        assertTrue(body(plan).contains("result-first"))
        assertFalse(body(plan).contains("call-latest"))
        assertFalse(body(plan).contains("call-pending"))
        assertEquals(rows.filterNot { it.sequence in 1L..2L }.flatMap { it.messages }, plan.retainedRequest.messages)
    }

    @Test fun relatedPreviousUserConsumesBudgetWithoutTruncatingOriginalValues() {
        val rows =
            listOf(
                row("request", "previous", 0, ModelRole.USER, "😀".repeat(3000)),
                row("step", "previous", 1, ModelRole.ASSISTANT, "Some work"),
                row("latest", "previous", 2, ModelRole.ASSISTANT, "Recent work"),
                row("current", "current", 3, ModelRole.USER, "Continue"),
            )
        val failure =
            assertThrows(
                ContextCapacityException::class.java,
            ) { plan(rows, settings = settings.copy(manualWindow = 4096)) }
        assertEquals("CONTEXT_SEGMENT_LIMIT", failure.code)
    }

    @Test fun manualFocusIsIncludedInFinalBudgetAndDoesNotStickToLaterRequests() {
        val rows =
            listOf(
                row("old", "previous", 1, ModelRole.USER, "Original constraints"),
                row("step", "previous", 2, ModelRole.ASSISTANT, "Work ".repeat(100)),
                row("latest", "previous", 3, ModelRole.ASSISTANT, "Recent work"),
                row("current", "current", 4, ModelRole.USER, "/compact Keep failing checks"),
            )
        val focused = plan(rows)
        assertTrue(
            focused.request.messages
                .last()
                .text
                .endsWith("Keep failing checks"),
        )
        assertEquals(
            settings.window - ModelInputEstimate.of(focused.request).total -
                SummaryOutputBudget.reserve(settings.window),
            focused.request.maxOutputTokens,
        )
        val later = plan(rows.dropLast(1) + row("current", "current", 4, ModelRole.USER, "Continue"))
        assertFalse(later.request.messages.any { it.text.startsWith("Optional focus for this summary only") })
    }

    private fun plan(
        rows: List<ContextSourceRow>,
        previous: ContextCheckpoint? = null,
        settings: ProviderContextSettings = this.settings,
        scale: Double = 1.0,
    ): ContextCompactionPlan {
        val messages = previous?.let { listOf(summaries.summaryMessage(it)) }.orEmpty() + rows.flatMap { it.messages }
        val request = TurnContextRequest("fixture", messages, emptyList(), 512, ReasoningEffort.OFF)
        return requireNotNull(
            compiler.plan(
                ContextSourceSnapshot(rows, previous, "current", null, 0),
                request,
                settings,
                true,
                scale,
            ),
        )
    }

    private fun body(plan: ContextCompactionPlan) =
        plan.request.messages
            .drop(1)
            .joinToString("") { it.text }

    private fun row(
        id: String,
        turn: String,
        sequence: Long,
        role: ModelRole,
        text: String,
    ) = ContextSourceRow(id, turn, sequence, role.name, "TEXT", text, 0, false, listOf(ModelMessage(role, text)))
}
