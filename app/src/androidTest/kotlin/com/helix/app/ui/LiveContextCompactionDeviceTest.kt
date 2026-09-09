package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.chat.ContextCompaction
import com.helix.app.chat.ModelStreamTerminal
import com.helix.app.chat.TurnCoordinator
import com.helix.app.chat.TurnStartSpec
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Explicit, keyless loopback service fixture. Never reads any user's provider or conversation. */
class LiveContextCompactionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // Live provider lifecycle, persistence and follow-up are one acceptance boundary.
    fun realMetadataAndSummaryPreserveTheOriginalConstraint() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("contextPort")?.toIntOrNull()
            val model = args.getString("contextModel")
            assumeTrue("Explicit keyless model fixture required", port != null && model != null)
            requireNotNull(port)
            requireNotNull(model)
            compose.resetDeterministicUiState()
            val container = compose.container()
            val service = container.providerService
            val chat = container.chatService
            val storage = container.storage
            val previous = chat.runControl.value
            val provider =
                service.create(
                    ProviderDraft(
                        null,
                        "Live context fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model,
                        "{}",
                        false,
                        CleartextAuthorization("127.0.0.1", port),
                        emptyList(),
                    ),
                    null,
                    cleartextConfirmed = true,
                )
            var session: String? = null
            try {
                check(service.runConnectionTest(provider) is ProbeOutcome.Ok)
                val settings = service.discoverContextWindow(provider, model)
                assertTrue("Server must declare a numeric context window", settings.serverWindow != null)
                println("LIVE_CONTEXT_WINDOW=${settings.window}")
                val id = chat.createSession("Live context fixture", provider, model)
                session = id
                seed(id)
                val originalIds = storage.messages.listBySession(id).map { it.id }
                chat.openSession(id)
                chat.setMode(AgentMode.CHAT)
                chat.setReasoning(ReasoningEffort.OFF)
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == id }
                chat.compactContext()
                compose.waitUntil(180_000) {
                    settled(id, 4)
                }
                val compactTurn = storage.turns.listBySession(id).last()
                println("LIVE_SUMMARY_USAGE=${storage.modelCalls.listByTurn(compactTurn.id).map { it.usage }}")
                assertEquals(compactTurn.errorCode, "COMPLETED", compactTurn.state)
                val checkpoint =
                    requireNotNull(
                        ContextCompaction.checkpoint(storage, storage.messages.listBySession(id)),
                    )
                assertTrue(checkpoint.summary.contains("ORANGE-42"))
                assertTrue(
                    originalIds.all { original ->
                        storage.messages.listBySession(id).any { it.id == original }
                    },
                )
                chat.send("What is the project verification code? Reply only with that code.")
                compose.waitUntil(180_000) {
                    settled(id, 5)
                }
                val replyTurn = storage.turns.listBySession(id).last()
                assertEquals(replyTurn.errorCode, "COMPLETED", replyTurn.state)
                assertTrue(
                    storage.messages.listBySession(id).any {
                        it.turnId == replyTurn.id && it.role == "ASSISTANT" &&
                            storage.messages.readContent(it)?.contains("ORANGE-42") == true
                    },
                )
                println("LIVE_CONTEXT_SUMMARY_AND_FOLLOWUP=PASS")
                repeat(2) { cycle ->
                    seed(id, includeConstraint = false)
                    val before = storage.turns.listBySession(id).size
                    val started = System.currentTimeMillis()
                    chat.compactContext()
                    compose.waitUntil(180_000) {
                        settled(id, before + 1)
                    }
                    val summaryTurn = storage.turns.listBySession(id).last()
                    println("LIVE_SUMMARY_USAGE=${storage.modelCalls.listByTurn(summaryTurn.id).map { it.usage }}")
                    assertEquals(summaryTurn.errorCode, "COMPLETED", summaryTurn.state)
                    val nextCheckpoint =
                        requireNotNull(
                            ContextCompaction.checkpoint(storage, storage.messages.listBySession(id)),
                        )
                    assertTrue(nextCheckpoint.summary.contains("ORANGE-42"))
                    chat.send(
                        "Report project verification code, whether deleting original files is allowed, " +
                            "and whether verification is passed or pending. " +
                            "Format: code|YES or NO|PASSED or PENDING. No other text.",
                    )
                    compose.waitUntil(180_000) {
                        settled(id, before + 2)
                    }
                    val result = storage.turns.listBySession(id).last()
                    assertEquals(result.errorCode, "COMPLETED", result.state)
                    val answer =
                        storage.messages
                            .listBySession(id)
                            .filter {
                                it.turnId == result.id && it.role == "ASSISTANT"
                            }.joinToString("\n") { storage.messages.readContent(it).orEmpty() }
                    assertTrue(answer, answer.replace(" ", "").contains("ORANGE-42|NO|PENDING"))
                    println("LIVE_REPEAT_COMPACTION cycle=$cycle elapsedMs=${System.currentTimeMillis() - started}")
                }
            } finally {
                chat.stop()
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                chat.closeSession()
                chat.setMode(previous.mode)
                chat.setReasoning(previous.reasoning)
                session?.let { storage.sessions.archive(it, System.currentTimeMillis()) }
                service.delete(provider)
            }
        }

    private fun settled(
        session: String,
        expectedTurns: Int,
    ): Boolean {
        val container = compose.container()
        val turns = container.storage.turns.listBySession(session)
        return turns.size == expectedTurns &&
            turns.last().state in setOf("COMPLETED", "FAILED", "CANCELLED") &&
            !container.chatService.screen.value.isSending
    }

    private fun seed(
        session: String,
        includeConstraint: Boolean = true,
    ) {
        val storage = compose.container().storage
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.now()
            }
        val constraint =
            if (includeConstraint) {
                "Remember this exact project verification code: ORANGE-42. " +
                    "Never delete original files. Verification is pending, not passed. "
            } else {
                "Routine notes without new constraints. "
            }
        val prompts =
            listOf(
                constraint +
                    "Routine obsolete progress log; no further constraints. ".repeat(150),
                "The project is a local file manager. Preserve original files and conversation history. " +
                    "Routine obsolete progress log; no further constraints. ".repeat(150),
                "We will discuss the next steps later. Do not perform any file operations now.",
            )
        prompts.forEach { prompt ->
            val next = { UUID.randomUUID().toString() }
            val coordinator =
                TurnCoordinator.start(
                    storage,
                    clock,
                    next,
                    TurnStartSpec(session, next(), next(), "{}", prompt),
                )
            val stream = coordinator.beginModelStream()
            stream.apply(ModelEvent.TextDelta("Understood."))
            stream.apply(ModelEvent.Completed("stop"))
            coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        }
    }
}
