package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.ChatToolMessageEncoder
import com.helix.app.chat.SessionTools
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.app.test.settleFixtureTerminal
import com.helix.app.test.startFixtureTurn
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.SettledCall
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.model.AgentMode
import com.helix.core.model.BoundToolResult
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ModelEvent
import com.helix.core.model.NoCancellation
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.repository.ConversationReferenceKind
import com.helix.core.storage.repository.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.ToolCallOrigin
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
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
    private val referenceSources = mutableListOf<String>()

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
                    ),
                    null,
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
                // This live fixture owns its finite output limit; another suite's 128-token
                // configuration must not truncate the summary before we test its contents.
                val fixtureBudgets = previous.budgets.copy(maxOutputTokens = 8192)
                chat.setTurnBudgets(fixtureBudgets)
                compose.waitUntil(10_000) { chat.runControl.value.budgets == fixtureBudgets }
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
                chat.sendTestMessage("What is the project verification code? Reply only with that code.")
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
                    // Preparation writes Room through a fixture Engine, not the application's
                    // live observation stream. Refresh the visible projection before using
                    // the same guarded compact action a user clicks.
                    chat.openSession(id)
                    compose.waitUntil(10_000) {
                        chat.screen.value.openSessionId == id && !chat.screen.value.isSending &&
                            !requireNotNull(container.turnEngine).liveExecution.hasActive(id)
                    }
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
                    println("LIVE_SYNTHETIC_CHECKPOINT cycle=$cycle ${nextCheckpoint.summary}")
                    assertTrue(nextCheckpoint.summary.contains("ORANGE-42"))
                    chat.sendTestMessage(
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
                session?.let { id ->
                    println(
                        "LIVE_FINAL_STATE turns=${storage.turns.listBySession(id).map { it.state to it.errorCode }} " +
                            "blocked=${chat.screen.value.blockedReason}",
                    )
                }
                chat.stop()
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                chat.closeSession()
                chat.setMode(previous.mode)
                chat.setReasoning(previous.reasoning)
                chat.setTurnBudgets(previous.budgets)
                session?.let { id ->
                    storage.turns.listBySession(id).forEach {
                        chat.stopTurn(it.id)
                        withTimeout(20_000) { container.agentRuntime.observe(TurnId(it.id)).collect {} }
                    }
                    storage.deleteSessionPermanently(id)
                }
                referenceSources.forEach { storage.deleteSessionPermanently(it) }
                referenceSources.clear()
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
            !container.chatService.screen.value.isSending &&
            !requireNotNull(container.turnEngine).liveExecution.hasActive(session)
    }

    /** Seed actual scoped-read evidence, not a transcript hidden in the reference metadata. */
    private fun readSharedFixture(
        coordinator: TurnRoundCoordinator,
        messageId: String,
        reference: ConversationReferenceSnapshotInput,
        source: MessageEntity,
        next: () -> String,
    ) = runBlocking {
        val storage = compose.container().storage
        val callId = next()
        val args = referenceReadArguments(messageId, reference, source)
        beginReferenceRead(coordinator, callId, args)
        val call =
            storage.toolCalls.append(
                callId,
                coordinator.id,
                callId,
                "session.read",
                "1",
                args.toString(),
                "RUNNING",
                origin =
                    ToolCallOrigin(coordinator.snapshot().modelCallId, 0),
            )
        val registry =
            ToolRegistry()
        SessionTools
            .register(registry, storage.sessionHistory)
        val result =
            requireNotNull(
                registry.executor(
                    ToolName("session.read"),
                    ToolVersion(1),
                ),
            ).execute(
                ExecutableToolCall(
                    callId,
                    "session.read",
                    "1",
                    args,
                    ExecutionTargetType.LOCAL_ANDROID,
                    Instant.now().plusSeconds(30),
                    NoCancellation,
                    storage.turns.resolve(coordinator.id).sessionId,
                    coordinator.id,
                ),
            ) as ToolExecutorResult.Completed
        assertTrue(result.output.toString().contains("ORANGE-42"))
        storage.toolCalls.updateState(call, ToolCallState.COMPLETED)
        val draft = referenceResultDraft(callId, result)
        coordinator.settleBatchCall(callId, false)
        coordinator.openNextModelCall(listOf(draft), next())
    }

    private fun beginReferenceRead(
        coordinator: TurnRoundCoordinator,
        callId: String,
        args: JsonObject,
    ) = runBlocking {
        val stream = coordinator.beginModelStream()
        stream.apply(
            ModelEvent.ToolCallStarted(
                0,
                ToolCallId(callId),
                "session.read",
            ),
        )
        stream.apply(ModelEvent.ToolArgumentsDelta(0, args.toString()))
        stream.apply(ModelEvent.ToolCallFinished(0))
        coordinator.beginToolBatch(listOf(callId))
        val step =
            kotlinx.serialization.json.buildJsonObject {
                put("id", callId)
                put("name", "session.read")
                put("arguments", args.toString())
            }
        coordinator.commitModelToolStep("[$step]")
    }

    private fun referenceReadArguments(
        messageId: String,
        reference: ConversationReferenceSnapshotInput,
        source: MessageEntity,
    ): JsonObject {
        val args =
            kotlinx.serialization.json.buildJsonObject {
                put("messageId", source.id)
                put(
                    "contentHash",
                    ContentRef
                        .parse(requireNotNull(source.contentRef))
                        .sha256,
                )
                put("offset", 0)
                put("referenceMessageId", messageId)
                put("referenceHash", reference.contentSha256)
            }
        return args
    }

    private fun referenceResultDraft(
        callId: String,
        result: ToolExecutorResult.Completed,
    ): TurnMessageDraft {
        val encoded = result.output.toString()
        val draft =
            ChatToolMessageEncoder { _, _ -> "" }.toolResultDraft(
                SettledCall(
                    callId,
                    "session.read",
                    ToolDispatchOutcome.Succeeded(
                        BoundToolResult(
                            encoded,
                            Sha256(
                                FileContentStore
                                    .sha256Hex(encoded.toByteArray()),
                            ),
                            false,
                            1,
                        ),
                    ),
                ),
            )
        return draft
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
        val referenceOnly =
            includeConstraint &&
                InstrumentationRegistry.getArguments().getString("contextReferenceFixture") == "true"
        val prompts =
            listOf(
                (if (referenceOnly) "Read the accepted conversation reference. " else constraint) +
                    "Routine obsolete progress log; no further constraints. ".repeat(150),
                "The project is a local file manager. Preserve original files and conversation history. " +
                    "Routine obsolete progress log; no further constraints. ".repeat(150),
                "We will discuss the next steps later. Do not perform any file operations now.",
            )
        prompts.forEachIndexed { index, prompt ->
            val next = { UUID.randomUUID().toString() }
            val coordinator =
                startFixtureTurn(
                    storage,
                    clock,
                    next,
                    TurnStartSpec(session, next(), next(), "{}", prompt),
                )
            if (referenceOnly && index == 0) {
                val message = storage.messages.listBySession(session).last { it.role == "USER" }
                assertTrue(storage.messages.readContent(message)?.contains("ORANGE-42") == false)
                val source = next()
                storage.sessions.create(source, "Accepted project constraints", null, null, 1)
                referenceSources += source
                val sourceMessage = storage.messages.append(next(), source, null, "USER", "TEXT", constraint)
                val reference =
                    ConversationReferenceSnapshotInput(
                        source,
                        "Accepted project constraints",
                        ConversationReferenceKind.HISTORY,
                        emptyList(),
                        storage.sessionHistory.captureReference(source).encode(),
                    )
                storage.withTransaction {
                    storage.messageReferenceSnapshots.bind(message.id, listOf(reference), System.currentTimeMillis())
                }
                readSharedFixture(coordinator, message.id, reference, sourceMessage, next)
                println("LIVE_BOUND_REFERENCE=scoped_tool_read originalBodyHasCode=false")
            }
            val stream = kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }
            stream.apply(ModelEvent.TextDelta("Understood."))
            stream.apply(ModelEvent.Completed("stop"))
            coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
        }
    }
}
