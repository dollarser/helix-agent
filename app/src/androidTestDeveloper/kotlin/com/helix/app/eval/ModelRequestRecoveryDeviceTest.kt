package com.helix.app.eval

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.sseResponse
import com.helix.app.chat.textAnswerStream
import com.helix.app.chat.toolCallStream
import com.helix.app.provider.BuiltInModel
import com.helix.app.ui.container
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.wire.WireBody
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Same 12 settled calls → failing model request shape as the reported task. No third-party actions/network. */
class ModelRequestRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val c get() = compose.container()

    @Test fun sixthAttemptSucceedsWithoutRepeatingEarlierTools() {
        runCase("success")
    }

    @Test fun fifthRetryFailureShowsModelStageAndCount() {
        runCase("exhausted")
    }

    @Test fun authenticationFailureStopsWithoutRetry() {
        runCase("auth")
    }

    @Test fun crashLocationsPersistWithoutExceptionMessages() {
        val application = compose.activity.application
        val prefs = application.getSharedPreferences("process-diagnostics-v1", android.content.Context.MODE_PRIVATE)
        val keys = setOf("crash-type", "crash-fingerprint", "crash-frames", "crash-at")
        val original = prefs.all.filterKeys { it in keys }
        try {
            val failure = IllegalStateException("PRIVATE_MESSAGE_SENTINEL")
            failure.stackTrace =
                arrayOf(
                    StackTraceElement(
                        "com.helix.validation.CrashFixture",
                        "execute",
                        "PRIVATE_SOURCE_PATH",
                        42,
                    ),
                )
            com.helix.app.diagnostics
                .ProcessEvidenceStore(application)
                .recordCrash(failure)
            val reopened =
                com.helix.app.diagnostics
                    .ProcessEvidenceStore(application)
            assertEquals(
                listOf(
                    "cause:java.lang.IllegalStateException",
                    "com.helix.validation.CrashFixture.execute:42",
                ),
                reopened.read().crashFrames,
            )
            assertTrue(requireNotNull(reopened.read().crashAtMillis) > 0)
            val preview =
                com.helix.app.diagnostics.DiagnosticBundlePreview
                    .collect(reopened)
                    .encode()
            assertTrue(!preview.contains("PRIVATE"))
        } finally {
            val edit = prefs.edit()
            keys.forEach(edit::remove)
            original.forEach { (key, value) ->
                when (value) {
                    is String -> edit.putString(key, value)
                    is Long -> edit.putLong(key, value)
                    else -> error("Unexpected crash preference type")
                }
            }
            check(edit.commit())
        }
    }

    // Own the entire fixture lifecycle and assert its durable effects.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "SpreadOperator")
    private fun runCase(outcome: String) =
        runBlocking {
            (compose.activity.application as HelixApplication).runtimeRecoveryReady.await()
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
            val name = "diagnostics.step_$suffix"
            val executions = AtomicInteger()
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{"index":{"type":"integer"}}}""")
            c.toolPipeline.registry.register(
                ToolDescriptor(
                    ToolName(name),
                    ToolVersion(1),
                    "Owned recovery fixture $suffix",
                    schema as JsonObject,
                    JsonObject(emptyMap()),
                    ToolOperationClass.READ_ONLY,
                    10.seconds,
                    1024,
                    emptySet(),
                    Idempotency.IDEMPOTENT,
                    ExecutionTargetType.LOCAL_ANDROID,
                    ToolOrigin.BuiltInOrigin,
                ),
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                        executions.incrementAndGet()
                        return ToolExecutorResult.Completed(call.args)
                    }
                },
            )
            val previous = c.chatService.screen.value.openSessionId

            val session =
                c.chatService.createSession(
                    "Synthetic model recovery $outcome $suffix",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val factoryField =
                c.providerService.javaClass
                    .getDeclaredField("factory")
                    .apply { isAccessible = true }
            val factory = requireNotNull(factoryField.get(c.providerService))
            val wireField = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
            val original = wireField.get(factory)
            val requests = AtomicInteger()
            var closed = false
            wireField.set(
                factory,
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        if (closed || request.url != BuiltInModel.GENERATION_URL ||
                            suffix !in request.body?.toString(Charsets.UTF_8).orEmpty()
                        ) {
                            throw IOException("OWNED_FIXTURE_ONLY")
                        }
                        val index = requests.getAndIncrement()
                        return when {
                            outcome == "auth" -> {
                                failure(401)
                            }

                            index == 0 -> {
                                sseResponse(
                                    toolCallStream(
                                        "discover",
                                        JSONObject.quote("tools.search"),
                                        JSONObject().put("query", "diagnostics $suffix").toString(),
                                    ),
                                )
                            }

                            index < 12 -> {
                                sseResponse(
                                    toolCallStream(
                                        "step-$index",
                                        JSONObject.quote(name),
                                        JSONObject().put("index", index).toString(),
                                    ),
                                )
                            }

                            outcome == "exhausted" || index < 17 -> {
                                failure(503)
                            }

                            else -> {
                                sseResponse(textAnswerStream("SYNTHETIC_RECOVERY_COMPLETE"))
                            }
                        }
                    }
                },
            )
            try {
                check(c.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
                configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.ACT)
                val input =
                    ChatSubmission(
                        session,
                        0,
                        UUID.randomUUID().toString(),
                        "Synthetic recovery $suffix; only the owned fixture. No app downloads or installations.",
                    )
                val admission = c.chatService.sendSubmission(input).await()
                check(admission.outcome is ChatSubmissionOutcome.Accepted)
                compose.waitUntil(120_000) {
                    c.storage.turns
                        .listBySession(session)
                        .lastOrNull()
                        ?.state in setOf("COMPLETED", "FAILED")
                }
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .single()
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                val models = c.storage.modelCalls.listByTurn(turn.id)
                val diagnostics =
                    models
                        .flatMap { c.storage.auditEvents.listByCorrelation(it.id) }
                        .filter { it.type in setOf("model.failure", "model.automatic_retry") }
                val expectedRequests = if (outcome == "auth") 1 else 18
                assertEquals(expectedRequests, requests.get())
                assertEquals(expectedRequests, models.size)
                assertEquals(if (outcome == "auth") 0 else 12, calls.size)
                assertEquals(if (outcome == "auth") 0 else 11, executions.get())
                assertTrue(calls.all { it.state == "COMPLETED" })
                assertEquals(
                    if (outcome == "auth") 0 else 5,
                    diagnostics.count { it.type == "model.automatic_retry" },
                )
                assertEquals(if (outcome == "success") "COMPLETED" else "FAILED", turn.state)
                compose.waitUntil(10_000) {
                    c.chatService.screen.value.activeTurn
                        ?.state
                        ?.isTerminal == true
                }
                val label =
                    c.chatService.screen.value.activeTurn
                        ?.errorLabel
                if (outcome != "success") {
                    assertTrue(label.orEmpty().contains("模型请求失败"))
                    if (outcome == "exhausted") assertTrue(label.orEmpty().contains("5"))
                    val english =
                        compose.activity.createConfigurationContext(
                            android.content.res.Configuration(compose.activity.resources.configuration).apply {
                                setLocale(java.util.Locale.ENGLISH)
                            },
                        )
                    val facts = diagnostics.last { it.type == "model.failure" }.redactedPayload
                    val englishLabel =
                        com.helix.app.chat.ModelFailureLabels.format(
                            requireNotNull(turn.errorCode),
                            "Synthetic failure",
                            facts,
                            { id, args -> english.getString(id, *args) },
                        )
                    assertTrue(englishLabel.contains("Model request failed"))
                    if (outcome == "exhausted") assertTrue(englishLabel.contains("5"))
                    c.chatService.closeSession()
                    compose.waitUntil(10_000) { c.chatService.screen.value.openSessionId == null }
                    c.chatService.openSession(session)
                    compose.waitUntil(30_000) {
                        val reopened = c.chatService.screen.value
                        reopened.openSessionId == session && reopened.activeTurn?.id == turn.id &&
                            reopened.activeTurn?.errorLabel == label && !reopened.preparingDraft
                    }
                }
                compose.waitForIdle()
                if (outcome == "exhausted") {
                    compose.waitUntil(30_000) {
                        c.chatService.screen.value.recoveryPanels[turn.id]
                            ?.summary
                            ?.completedActions
                            ?.size == 12
                    }
                    compose.onNodeWithTag("chat-timeline").performScrollToNode(
                        hasTestTag("recovery-completed-${turn.id}"),
                    )
                    compose.onNodeWithTag("recovery-completed-${turn.id}").assertTextContains(
                        compose.activity.getString(com.helix.app.R.string.recovery_completed, 12),
                    )
                    compose.onNodeWithTag("recovery-next-${turn.id}").assertDoesNotExist()
                }
                val directory =
                    File(
                        compose.activity.getExternalFilesDir(null),
                        "model-request-recovery",
                    ).apply { mkdirs() }
                val exported = File(directory, "$outcome-trace.jsonl")
                val checkCancelled = { coroutineContext.ensureActive() }
                val appVersion =
                    requireNotNull(
                        compose.activity.packageManager
                            .getPackageInfo(
                                compose.activity.packageName,
                                0,
                            ).versionName,
                    )
                c.storage.sessionExports
                    .prepare(
                        session,
                        File(directory, "$outcome-export-stage"),
                        appVersion,
                        com.helix.core.storage.export.SessionExportSanitizer { text, _ ->
                            com.helix.app.application.diagnostics.DiagnosticRedaction
                                .redact(text)
                        },
                        checkCancelled,
                    ).use { prepared ->
                        prepared.deliver(exported.outputStream(), checkCancelled)
                    }
                val exportText = exported.readText()
                assertEquals(
                    diagnostics.count { it.type == "model.failure" },
                    exportText.lineSequence().count { it.contains("\"type\":\"model.failure\"") },
                )
                assertEquals(
                    diagnostics.count { it.type == "model.automatic_retry" },
                    exportText.lineSequence().count { it.contains("\"type\":\"model.automatic_retry\"") },
                )
                assertTrue(!exportText.contains("PRIVATE_HTTP_BODY_SENTINEL"))
                val report =
                    JSONObject()
                        .put("session", session)
                        .put("turn", turn.id)
                        .put("outcome", outcome)
                        .put("state", turn.state)
                        .put("requests", requests.get())
                        .put("tools", calls.size)
                        .put("executions", executions.get())
                        .put("label", label)
                        .put(
                            "diagnostics",
                            JSONArray(
                                diagnostics.map {
                                    JSONObject()
                                        .put("modelCallId", it.correlationId)
                                        .put("type", it.type)
                                        .put("data", JSONObject(it.redactedPayload))
                                },
                            ),
                        )
                File(directory, "$outcome.json").writeText(report.toString(2))
                File(directory, "$outcome.png").outputStream().use {
                    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                finishOwnedCase(c.storage, c.chatService, session, { closed = true }, {}, { stopped ->
                    if (stopped) wireField.set(factory, original)
                    val retained =
                        c.toolPipeline.registry
                            .snapshot()
                            .filter {
                                it.descriptor.origin == ToolOrigin.BuiltInOrigin && it.descriptor.name.value != name
                            }.map { it.binding }
                    c.toolPipeline.registry.replaceOwner(bindingOwner(ToolOrigin.BuiltInOrigin), retained)
                    c.storage.sessions.archive(session, System.currentTimeMillis())
                    previous?.let(c.chatService::openSession) ?: c.chatService.closeSession()
                })
            }
        }

    private fun failure(status: Int): WireResponse {
        val bytes = """{"error":{"type":"overloaded_error","message":"PRIVATE_HTTP_BODY_SENTINEL"}}""".toByteArray()
        return WireResponse(
            status,
            mapOf(
                "content-type" to listOf("application/json"),
                "retry-after" to listOf("0"),
                "x-request-id" to listOf("synthetic-request"),
            ),
            object : WireBody {
                override suspend fun bytes() = bytes

                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                    onChunk(bytes)
                }

                override fun close() = Unit
            },
        )
    }
}
