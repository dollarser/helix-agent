package com.helix.app.eval

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.chat.ConversationContent
import com.helix.app.chat.conversationEntries
import com.helix.app.chat.groupCompletedTools
import com.helix.app.chat.sseResponse
import com.helix.app.chat.textAnswerStream
import com.helix.app.chat.toolCallStream
import com.helix.app.provider.BuiltInModel
import com.helix.app.ui.container
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.bindingOwner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Product Engine/Dispatcher/Room events, not injected UI rows. In-memory model never delegates network. */
class ConversationToolFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val c get() = compose.container()
    private val app get() = compose.activity.application as HelixApplication
    private val directory get() = File(app.getExternalFilesDir(null), "conversation-noise").apply { mkdirs() }

    @Test fun inspectOnlyTheOwnedRecordedRealModelCase() =
        runBlocking {
            val id = requireNotNull(InstrumentationRegistry.getArguments().getString("noise.session"))
            val stored = c.storage.sessions.resolve(id)
            check(stored.title == "Synthetic Agent Skill creation" && stored.providerId == BuiltInModel.PROVIDER_ID)
            val previous = c.chatService.screen.value.openSessionId
            try {
                open(id)
                val report = report(id)
                File(directory, "recorded-case.json").writeText(report.toString(2))
                groups(id).firstOrNull()?.values?.firstOrNull()?.let {
                    reveal("tool-group-toggle-${it.callId}")
                }
                screenshot("recorded-case")
                println("RECORDED_CASE_UI=$report")
            } finally {
                previous?.let(c.chatService::openSession) ?: c.chatService.closeSession()
            }
        }

    @Suppress("LongMethod") // Own one run from admission through approval, unknown settlement and restoration.
    @Test
    fun sequentialModelResponsesGroupWhileRunningApprovalAndUnknownRemainVisible() =
        runBlocking {
            app.runtimeRecoveryReady.await()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            val suffix =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
            val read = "noise.read_$suffix"
            val write = "noise.write_$suffix"
            val names = setOf(read, write)
            val release = CountDownLatch(1)
            val executions = AtomicInteger()
            register(read, ToolOperationClass.READ_ONLY, release, executions)
            register(write, ToolOperationClass.LOCAL_MUTATION, release, executions)

            val previous = c.chatService.screen.value.openSessionId
            val session =
                c.chatService.createSession(
                    "Synthetic tool grouping $suffix",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val factory = requireNotNull(field(c.providerService, "factory").get(c.providerService))
            val wireField = field(factory, "builtInWire")
            val original = wireField.get(factory)
            val step = AtomicInteger()
            var closed = false
            wireField.set(
                factory,
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        if (closed || request.url != BuiltInModel.GENERATION_URL ||
                            suffix !in request.body?.toString(Charsets.UTF_8).orEmpty()
                        ) {
                            throw IOException("OWNED_NOISE_CASE_ONLY")
                        }
                        val i = step.getAndIncrement()
                        val name =
                            when (i) {
                                0 -> "tools.search"
                                4 -> write
                                else -> read
                            }
                        val label =
                            when (i) {
                                3 -> "HOLD"
                                4 -> "FAIL"
                                7 -> "UNKNOWN"
                                else -> "FIND_NOISE_${i}_$suffix"
                            }
                        val arguments =
                            if (i == 0) {
                                JSONObject().put("query", "noise $suffix")
                            } else {
                                JSONObject().put("label", label)
                            }
                        return if (i < 8) {
                            sseResponse(toolCallStream("step-$i", JSONObject.quote(name), arguments.toString()))
                        } else {
                            sseResponse(textAnswerStream("SYNTHETIC_NOISE_END"))
                        }
                    }
                },
            )
            try {
                check(c.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
                    System.currentTimeMillis(),
                )
                configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.ACT)
                val input =
                    ChatSubmission(
                        session,
                        0,
                        UUID.randomUUID().toString(),
                        "Synthetic grouping $suffix; only the owned fixture tools.",
                    )
                val receipt = c.chatService.sendSubmission(input).await()
                check(receipt.outcome is ChatSubmissionOutcome.Accepted)
                compose.waitUntil(60_000) {
                    executions.get() >= 3 &&
                        c.chatService.screen.value.toolTimeline
                            .any { it.state == ToolCallState.RUNNING }
                }
                val running =
                    c.chatService.screen.value.toolTimeline
                        .single { it.state == ToolCallState.RUNNING }
                val first =
                    c.chatService.screen.value.toolTimeline
                        .first()
                        .callId
                reveal("tool-row-${running.callId}")
                compose.onNodeWithTag("tool-row-state-${running.callId}", true).assertIsDisplayed()
                reveal("tool-group-toggle-$first")
                compose.onNodeWithTag("tool-row-$first").assertDoesNotExist()
                screenshot("running-and-collapsed")
                release.countDown()
                compose.waitUntil(60_000) {
                    c.chatService.screen.value.toolTimeline
                        .any { it.state == ToolCallState.AWAITING_APPROVAL }
                }
                val approval =
                    c.chatService.screen.value.toolTimeline.single {
                        it.state == ToolCallState.AWAITING_APPROVAL
                    }
                val card = requireNotNull(approval.card)
                reveal("approval-approve-${card.approvalId}")
                screenshot("approval-visible")
                compose.onNodeWithTag("approval-approve-${card.approvalId}").performClick()
                compose.waitUntil(60_000) {
                    c.chatService.screen.value.toolTimeline
                        .any { it.state == ToolCallState.NEEDS_REVIEW }
                }
                compose.waitUntil(30_000) {
                    c.chatService.screen.value.messages
                        .any { it.content == "SYNTHETIC_NOISE_END" }
                }
                val state = c.chatService.screen.value
                val failed = state.toolTimeline.single { it.state == ToolCallState.FAILED }
                val unknown = state.toolTimeline.single { it.state == ToolCallState.NEEDS_REVIEW }
                reveal("tool-row-${failed.callId}")
                compose.onNodeWithTag("tool-row-state-${failed.callId}", true).assertIsDisplayed()
                reveal("tool-row-${unknown.callId}")
                compose.onNodeWithTag("tool-row-state-${unknown.callId}", true).assertIsDisplayed()
                val grouped = groups(session)
                assertEquals(listOf(4, 2), grouped.map { it.values.size })
                assertTrue(
                    grouped
                        .first()
                        .values
                        .map { it.position!!.sequence }
                        .distinct()
                        .size == 4,
                )
                reveal("tool-group-toggle-$first")
                compose.onNodeWithTag("tool-group-toggle-$first").performClick()
                reveal("tool-details-$first")
                compose.onNodeWithTag("tool-details-$first").performClick()
                compose.onNodeWithText("调用已完成；业务结果仍需核验").assertDoesNotExist()
                compose.onNodeWithTag("tool-row-state-$first", true).assertDoesNotExist()
                reveal("tool-details-$first")
                screenshot("expanded-quiet-results")
                c.chatService.closeSession()
                open(session)
                assertEquals(listOf(4, 2), groups(session).map { it.values.size })
                compose.onNodeWithTag("chat-search-toggle").performClick()
                compose.onNodeWithTag("chat-search-input").performTextReplacement("FIND_NOISE_2_$suffix")
                val target = state.toolTimeline.single { "FIND_NOISE_2_$suffix" in it.requestSummary }.callId
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag("tool-row-$target")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("tool-row-$target").assertIsDisplayed()
                screenshot("search-reopened-history")
                File(directory, "product-flow.json").writeText(
                    report(session)
                        .put("executions", executions.get())
                        .put("modelRequests", step.get())
                        .put("wire", "in-memory")
                        .toString(2),
                )
            } finally {
                release.countDown()
                finishOwnedCase(c.storage, c.chatService, session, { closed = true }, {}, { stopped ->
                    if (stopped) wireField.set(factory, original)
                    val retained =
                        c.toolPipeline.registry
                            .snapshot()
                            .filter {
                                it.descriptor.origin == ToolOrigin.BuiltInOrigin && it.descriptor.name.value !in names
                            }.map { it.binding }
                    c.toolPipeline.registry.replaceOwner(bindingOwner(ToolOrigin.BuiltInOrigin), retained)
                    c.storage.sessions.archive(session, System.currentTimeMillis())
                    previous?.let(c.chatService::openSession) ?: c.chatService.closeSession()
                })
            }
        }

    private fun register(
        name: String,
        operation: ToolOperationClass,
        release: CountDownLatch,
        executions: AtomicInteger,
    ) {
        val schema = """{
            "type":"object","properties":{"label":{"type":"string"}},
            "required":["label"],"additionalProperties":false
        }"""
        val descriptor =
            ToolDescriptor(
                ToolName(name),
                ToolVersion(1),
                "Owned synthetic noise test",
                Json.parseToJsonElement(schema) as JsonObject,
                JsonObject(emptyMap()),
                operation,
                30.seconds,
                4096,
                emptySet(),
                Idempotency.IDEMPOTENT,
                ExecutionTargetType.LOCAL_ANDROID,
                ToolOrigin.BuiltInOrigin,
            )
        c.toolPipeline.registry.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    executions.incrementAndGet()
                    val label = call.args["label"].toString().trim('"')
                    if (label == "HOLD") check(release.await(30, TimeUnit.SECONDS))
                    return when (label) {
                        "FAIL" -> {
                            ToolExecutorResult.Failed("Synthetic safe failure", sideEffectFree = true)
                        }

                        "UNKNOWN" -> {
                            ToolExecutorResult.Failed(
                                "Synthetic dispatched result unknown",
                                requiresReview = true,
                            )
                        }

                        else -> {
                            ToolExecutorResult.Completed(buildJsonObject { put("label", label) })
                        }
                    }
                }
            },
        )
    }

    private fun groups(id: String) =
        conversationEntries(c.chatService.screen.value)
            .filter {
                it.key in
                    c.storage.turns
                        .listBySession(id)
                        .map { t -> t.id }
            }.flatMap { groupCompletedTools(it.timeline) }
            .filterIsInstance<ConversationContent.ToolGroup>()

    private fun open(id: String) {
        c.chatService.openSession(id)
        compose.waitUntil(30_000) {
            c.chatService.screen.value.openSessionId == id &&
                c.chatService.screen.value.toolTimeline
                    .isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun reveal(tag: String) {
        compose.onNodeWithTag("chat-timeline").performScrollToNode(hasTestTag(tag))
    }

    private fun field(
        owner: Any,
        name: String,
    ) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun screenshot(name: String) {
        File(directory, "$name.png").outputStream().use {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun report(id: String): JSONObject {
        val s = c.chatService.screen.value
        val entries = conversationEntries(s)
        val messages =
            s.messages.map {
                JSONObject()
                    .put("sequence", it.sequence)
                    .put("presentation", it.presentation.name)
                    .put("characters", it.content.length)
            }
        val calls =
            s.toolTimeline.map {
                JSONObject()
                    .put("id", it.callId)
                    .put("turn", it.turnId)
                    .put("sequence", it.position?.sequence)
                    .put("state", it.state.name)
            }
        val groupIds = groups(id).map { it.values.map { row -> row.callId } }
        val keys = entries.flatMap { it.timeline }.map { it.key }
        return JSONObject().apply {
            put("session", id)
            put("groups", JSONArray(groupIds))
            put("messages", JSONArray(messages))
            put("calls", JSONArray(calls))
            put("timeline", JSONArray(keys))
        }
    }
}
