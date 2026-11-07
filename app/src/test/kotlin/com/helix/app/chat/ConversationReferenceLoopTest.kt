package com.helix.app.chat

import com.helix.app.agent.ContextHistoryContent
import com.helix.core.agent.HeadlessLoopFixture
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.TurnLoopResult
import com.helix.core.model.BoundToolResult
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.NoCancellation
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnState
import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.MessageReferenceSnapshotEntity
import com.helix.core.storage.repository.ConversationReferenceSnapshot
import com.helix.core.storage.repository.SessionHistoryRepository
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** Actual AgentLoop and registered session executors; only the provider and durable DAO are fake. */
class ConversationReferenceLoopTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dao = SessionToolsTest.HistoryDao()
    private val store by lazy { FileContentStore(temporary.root) }
    private val body = "😀中".repeat(24_000) + "TAIL_EVIDENCE"

    private fun prepare(extraRows: Int = 0): ConversationReferenceSnapshot {
        dao.rows.clear()
        dao.snapshots.clear()
        dao.sources.clear()
        dao.sources["source"] = dao.session.copy(id = "source")
        val source = store.write(body)
        dao.rows += MessageEntity("long", "source", null, "USER", "TEXT", source.toStorageString(), 1)
        repeat(extraRows) { index ->
            val content = store.write("Later source message $index").toStorageString()
            dao.rows += MessageEntity("extra-$index", "source", null, "USER", "TEXT", content, index + 2L)
        }
        val question = store.write("question").toStorageString()
        dao.rows += MessageEntity("binding", "s", "first", "USER", "TEXT", question, 1)
        val content = SessionHistoryRepository(dao, store).captureReference("source").encode()
        val ref = store.write(content)
        dao.snapshots +=
            MessageReferenceSnapshotEntity(
                messageId = "binding",
                ordinal = 0,
                sourceSessionId = "source",
                sourceSessionTitle = "Selected source",
                selectionKind = "HISTORY",
                sourceMessageIdsJson = "[]",
                contentRef = ref.toStorageString(),
                contentSha256 = ref.sha256,
                createdAtEpoch = 1,
            )
        return ConversationReferenceSnapshot(
            "source",
            "Selected source",
            ConversationReferenceKind.HISTORY,
            emptyList(),
            content,
            ref.sha256,
        )
    }

    private fun scope(
        reference: ConversationReferenceSnapshot,
        fields: JsonObject,
    ): JsonObject {
        val identity =
            mapOf(
                "referenceMessageId" to JsonPrimitive("binding"),
                "referenceHash" to JsonPrimitive(reference.contentSha256),
            )
        return JsonObject(fields + identity)
    }

    private fun call(
        name: String,
        args: JsonObject,
    ): List<ModelEvent> =
        listOf(
            ModelEvent.ToolCallStarted(0, ToolCallId("wire"), name),
            ModelEvent.ToolArgumentsDelta(0, args.toString()),
            ModelEvent.ToolCallFinished(0),
            ModelEvent.Completed("tool_calls"),
        )

    private fun wire(
        fixture: HeadlessLoopFixture,
        reference: ConversationReferenceSnapshot,
    ) {
        fixture.journal.history.clear()
        fixture.journal.history +=
            ModelMessage(
                ModelRole.USER,
                requireNotNull(ContextHistoryContent.materialize("Analyze this source", listOf(reference), "binding")),
            )
        fixture.tools =
            SessionToolDescriptors.names.filter { it != "session.rename" }.map {
                val descriptor = SessionToolDescriptors.descriptor(it)
                ModelToolSchema(descriptor.name, descriptor.description, descriptor.inputSchema.toString())
            }
        fixture.dispatch = { request ->
            dao.activeTurn = fixture.journal.id
            dao.activeCall = request.callId
            val registry = ToolRegistry()
            SessionTools.register(registry, SessionHistoryRepository(dao, store))
            val result =
                requireNotNull(registry.executor(ToolName(request.name), ToolVersion(1))).execute(
                    ExecutableToolCall(
                        request.callId,
                        request.name,
                        "1",
                        Json.parseToJsonElement(request.arguments).jsonObject,
                        ExecutionTargetType.LOCAL_ANDROID,
                        Instant.now().plusSeconds(5),
                        NoCancellation,
                        "s",
                        fixture.journal.id,
                    ),
                )
            when (result) {
                is ToolExecutorResult.Completed -> {
                    ToolDispatchOutcome.Succeeded(
                        BoundToolResult(
                            result.output.toString(),
                            Sha256(FileContentStore.sha256Hex(result.output.toString().toByteArray())),
                            false,
                            1,
                        ),
                    )
                }

                is ToolExecutorResult.Failed -> {
                    ToolDispatchOutcome.ExecutionFailed(
                        DispatchOutcomeCode.TOOL_FAILED,
                        result.detail,
                        requiresReview = false,
                    )
                }

                else -> {
                    error("Unexpected fixture result: $result")
                }
            }
        }
    }

    @Test fun loopReadsLongSourceAndFollowup() =
        runBlocking {
            val reference = prepare()
            lateinit var fixture: HeadlessLoopFixture
            fixture =
                HeadlessLoopFixture("first") { step ->
                    when (step) {
                        0 -> {
                            val initial =
                                fixture.requests
                                    .last()
                                    .messages
                                    .joinToString { it.text }
                            assertFalse(initial.contains("TAIL_EVIDENCE"))
                            call("session.search", scope(reference, buildJsonObject { put("query", "TAIL_EVIDENCE") }))
                        }

                        1 -> {
                            val output = toolOutput(fixture)
                            val hit =
                                output
                                    .getValue("entries")
                                    .jsonArray
                                    .single()
                                    .jsonObject
                            call(
                                "session.read",
                                scope(
                                    reference,
                                    buildJsonObject {
                                        put("messageId", hit.getValue("messageId"))
                                        put("contentHash", hit.getValue("contentHash"))
                                        put("offset", hit.getValue("readOffset"))
                                    },
                                ),
                            )
                        }

                        else -> {
                            assertTrue(
                                fixture.requests
                                    .last()
                                    .messages
                                    .last()
                                    .text
                                    .contains("TAIL_EVIDENCE"),
                            )
                            listOf(ModelEvent.TextDelta("Observed TAIL_EVIDENCE"), ModelEvent.Completed("stop"))
                        }
                    }
                }
            wire(fixture, reference)
            val expected = TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals(expected, fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control))
            assertEquals(3, fixture.requests.size)
            val calls = fixture.dispatched.flatMap { it.calls }.map { it.name }
            assertEquals(listOf("session.search", "session.read"), calls)
            assertFollowup(reference, expected)
        }

    private suspend fun assertFollowup(
        reference: ConversationReferenceSnapshot,
        expected: TurnLoopResult,
    ) {
        val followup =
            HeadlessLoopFixture("follow-up") { step ->
                if (step == 0) {
                    call("session.history", scope(reference, buildJsonObject {}))
                } else {
                    listOf(ModelEvent.TextDelta("Continued"), ModelEvent.Completed("stop"))
                }
            }
        wire(followup, reference)
        assertEquals(expected, followup.loop().runToolLoop("s", followup.journal, "p", null, followup.control))
        assertTrue(
            followup.requests
                .last()
                .messages
                .last()
                .text
                .contains("long"),
        )
    }

    private fun toolOutput(fixture: HeadlessLoopFixture): JsonObject {
        val text =
            fixture.requests
                .last()
                .messages
                .last()
                .text
        return Json.parseToJsonElement(text).jsonObject
    }

    private suspend fun probeFailure(
        reference: ConversationReferenceSnapshot,
        args: JsonObject,
        code: String,
    ) {
        lateinit var fixture: HeadlessLoopFixture
        fixture =
            HeadlessLoopFixture("probe") { step ->
                if (step == 0) {
                    call("session.history", args)
                } else {
                    val result =
                        fixture.requests
                            .last()
                            .messages
                            .last()
                            .text
                    assertTrue(result, result.contains(code))
                    assertTrue(result.contains("Reselect"))
                    assertFalse(result.contains("TAIL_EVIDENCE"))
                    listOf(ModelEvent.TextDelta("Source cannot be read"), ModelEvent.Completed("stop"))
                }
            }
        wire(fixture, reference)
        val result = fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control)
        assertEquals(TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null)), result)
        assertEquals(2, fixture.requests.size)
    }

    @Test fun loopReportsEditsDeletionAndForgedReferences() =
        runBlocking {
            var reference = prepare()
            val old = dao.rows.first()
            dao.rows[0] = old.copy(contentRef = store.write("Replaced content").toStorageString())
            probeFailure(reference, scope(reference, buildJsonObject {}), "REFERENCE_SOURCE_CHANGED")
            reference = prepare()
            dao.sources.remove("source")
            probeFailure(reference, scope(reference, buildJsonObject {}), "REFERENCE_SOURCE_UNAVAILABLE")
            reference = prepare()
            val forged = scope(reference, buildJsonObject {}) + ("referenceHash" to JsonPrimitive("0".repeat(64)))
            probeFailure(reference, JsonObject(forged), "REFERENCE_IDENTITY_CHANGED")
        }

    @Test fun loopPagesPastEmptySearchWithoutReadingNewAppends() =
        runBlocking {
            val reference = prepare(extraRows = 45)
            val appended = store.write("LATER_PRIVATE").toStorageString()
            dao.rows += MessageEntity("appended", "source", null, "USER", "TEXT", appended, 100)
            lateinit var fixture: HeadlessLoopFixture
            fixture =
                HeadlessLoopFixture("paging") { step ->
                    when (step) {
                        0 -> {
                            call("session.search", scope(reference, buildJsonObject { put("query", "TAIL_EVIDENCE") }))
                        }

                        1 -> {
                            val output = toolOutput(fixture)
                            assertTrue(output.getValue("entries").jsonArray.isEmpty())
                            call(
                                "session.search",
                                scope(
                                    reference,
                                    buildJsonObject {
                                        put("query", "TAIL_EVIDENCE")
                                        put("beforeSequence", output.getValue("nextBeforeSequence"))
                                    },
                                ),
                            )
                        }

                        2 -> {
                            assertTrue(toolOutput(fixture).toString().contains("TAIL_EVIDENCE"))
                            call("session.search", scope(reference, buildJsonObject { put("query", "LATER_PRIVATE") }))
                        }

                        else -> {
                            assertTrue(toolOutput(fixture).getValue("entries").jsonArray.isEmpty())
                            listOf(ModelEvent.TextDelta("Only the selected revision"), ModelEvent.Completed("stop"))
                        }
                    }
                }
            wire(fixture, reference)
            val result = fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control)
            assertEquals(TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null)), result)
            assertEquals(4, fixture.requests.size)
        }
}
