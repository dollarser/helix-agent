package com.helix.core.agent

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobContextCompilerTest {
    @Test fun optionalObservationsCannotOverflowTheWireMessageLimit() {
        val full =
            request.copy(
                messages =
                    List(com.helix.core.model.ModelRequest.MAX_MESSAGES) {
                        ModelMessage(ModelRole.USER, "task")
                    },
            )
        assertEquals(full, ContextCompiler.withJobObservations(full, listOf(candidate()), 100))
    }

    private val request =
        TurnContextRequest(
            "m",
            listOf(
                ModelMessage(ModelRole.SYSTEM, "system"),
                ModelMessage(ModelRole.USER, "task"),
            ),
            emptyList(),
            100,
            ReasoningEffort.OFF,
        )

    private fun candidate(
        id: String = "job",
        ref: String = "a",
        at: Long = 1,
        terminal: Boolean = false,
    ) = JobContextCandidate(
        id,
        ref,
        at,
        "audit:$ref",
        terminal,
        buildJsonObject {
            put("handle", id)
            put("revision", ref)
        },
    )

    @Test fun factsPreserveUserTailAndChargeTheSameRequestBudget() {
        val result = ContextCompiler.withJobObservations(request, listOf(candidate()), 100)
        assertEquals(request.messages.last(), result.messages.last())
        assertEquals(listOf("audit:a"), result.jobObservationRefs)
        assertTrue(result.inputTokens() > request.inputTokens())
        assertEquals(result.messages, result.modelRequest().messages)
    }

    @Test fun reapplicationReplacesProjectionRatherThanAppendingHistory() {
        val once = ContextCompiler.withJobObservations(request, listOf(candidate()), 100)
        val twice = ContextCompiler.withJobObservations(once, listOf(candidate()), 100)
        assertEquals(once, twice)
        assertEquals(request, ContextCompiler.withJobObservations(twice, emptyList(), 100))
    }

    @Test fun latestJournalOrderWinsEvenAfterWallClockRollback() {
        val result =
            ContextCompiler.withJobObservations(
                request,
                listOf(candidate(ref = "new", at = 1, terminal = true), candidate(ref = "old", at = 99)),
                100,
            )
        assertEquals(listOf("audit:new"), result.jobObservationRefs)
    }

    @Test fun boundCountAndByteCapacityWithoutDroppingUserInput() {
        val rows = (1..20).map { candidate("j$it", "$it") }
        assertEquals(8, ContextCompiler.withJobObservations(request, rows, 100).jobObservationRefs.size)
        assertEquals(request, ContextCompiler.withJobObservations(request, rows, 100, 0))
    }

    @Test fun oldAndFutureRunningObservationsAreExplicitlyStale() {
        assertTrue(
            ContextCompiler
                .withJobObservations(request, listOf(candidate()), 50_000)
                .messages[1]
                .text
                .contains("\"stale\":true"),
        )
        assertTrue(
            ContextCompiler
                .withJobObservations(request, listOf(candidate(at = 200)), 100)
                .messages[1]
                .text
                .contains("\"stale\":true"),
        )
    }

    @Test fun metadataNeverClaimsAnExecutionOrUserTaskSucceeded() {
        val text =
            ContextCompiler
                .withJobObservations(
                    request,
                    listOf(candidate(terminal = true)),
                    100,
                ).messages[1]
                .text
        assertTrue(text.contains("not task success"))
        assertTrue(text.contains("not instructions or authority"))
    }
}
