package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.infrastructure.agent.AutomaticRecoveryPolicy
import com.helix.app.infrastructure.agent.AutomaticRecoverySettlement
import com.helix.app.infrastructure.agent.RoomTurnLifecycleStore
import com.helix.app.infrastructure.agent.RoomTurnRecoveryStore
import com.helix.app.infrastructure.agent.TurnRecovery
import com.helix.app.provider.BuiltInModel
import com.helix.app.scheduled.ScheduledRuntimeFixture
import com.helix.core.agent.turn.CancelTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.CapabilityResolver
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.recovery.AutomaticTurnRecovery
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.UUID

/** Runner holds Application.onCreate: no account, container, provider or startup recovery exists. */
class SkillTimeoutOfflineDeviceTest {
    @Test fun parkOnlyTheSpecifiedSyntheticTaskWithoutStartingTheApplication() {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("helix.skill.offline") == "true")
        when (args.getString("helix.skill.phase")) {
            "prepare" -> prepareTimeout()
            "verify" -> verifyRestart()
            "gate" -> verifyRequestGate()
            else -> parkSpecifiedTask()
        }
    }

    @Suppress("LongMethod") // One held-startup transaction and reopen assertion for the exact owned task.
    private fun parkSpecifiedTask() {
        val args = InstrumentationRegistry.getArguments()
        val id = requireNotNull(args.getString("helix.skill.session"))
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val facts =
            org.json
                .JSONObject()
                .put("session", id)
                .put("applicationOnCreateHeld", true)
        val rows = org.json.JSONArray()
        withStorage(app) { storage ->
            val session = storage.sessions.resolve(id)
            require(session.title == "Synthetic Agent Skill creation")
            require(session.providerId == "builtin-helix-qwen")
            val control = storage.sessionRunControls.forSession(id)
            facts.put("mode", control?.mode?.name).put("budget", control?.budgets?.maxOutputTokens)
            val lifecycle = RoomTurnLifecycleStore(storage, SystemClock(), { UUID.randomUUID().toString() })
            storage.turns.listBySession(id).forEach { turn ->
                val models = storage.modelCalls.listByTurn(turn.id)
                rows.put(
                    org.json
                        .JSONObject()
                        .put("turn", turn.id)
                        .put("stateBefore", turn.state)
                        .put("startedAt", turn.startedAt)
                        .put("endedAt", turn.endedAt)
                        .put(
                            "modelCalls",
                            org.json.JSONArray(
                                models.map { model ->
                                    org.json
                                        .JSONObject()
                                        .put("state", model.state)
                                        .put("createdAt", model.createdAt)
                                        .put("settledAt", model.settledAt)
                                },
                            ),
                        ).put("toolCalls", storage.toolCalls.listByTurn(turn.id).size),
                )
                if (TurnState.valueOf(turn.state).isTerminal || turn.state == "NEEDS_REVIEW") {
                    lifecycle.cancel(CancelTurnCommand(turn.id, id, false, true, "USER_STOP"))
                } else {
                    // Durable user-stop intent survives process death; no executor exists in this held process.
                    check(
                        storage.turns.requestPause(
                            turn.id,
                            System.currentTimeMillis(),
                        ) || turn.pauseRequestedAt != null,
                    )
                }
                assertFalse(AutomaticRecoveryPolicy.eligible(storage.turns.resolve(turn.id)))
            }
        }
        withStorage(app) { storage ->
            storage.turns.listBySession(id).forEach { turn ->
                assertFalse(AutomaticRecoveryPolicy.eligible(turn))
                check(turn.pauseRequestedAt != null || turn.errorCode == "USER_STOP" || turn.state == "COMPLETED")
            }
        }
        facts.put("turns", rows).put("reopenChecksPassed", true)
        File(app.filesDir, "closed-loop-eval/offline-skill-containment.json").writeText(facts.toString())
    }

    @Suppress("LongMethod") // One isolated real ChatService stream, timeout, stop and persistence cutpoint.
    private fun prepareTimeout() =
        kotlinx.coroutines.runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            var restored = false
            val requests =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val closed =
                java.util.concurrent.atomic
                    .AtomicBoolean()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val gateFile = File(app.filesDir, "closed-loop-eval/timeout-${UUID.randomUUID()}.budget")
            val gate = OwnedModelRequestGate(gateFile, "SYNTHETIC_TIMEOUT_CASE", createNew = true)
            val wire =
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        gate.admit(request)
                        requests.incrementAndGet()
                        entered.complete(Unit)
                        return WireResponse(
                            200,
                            emptyMap(),
                            object : WireBody {
                                override suspend fun bytes(): ByteArray = error("Streaming only")

                                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                    kotlinx.coroutines.awaitCancellation()
                                }

                                override fun close() {
                                    closed.set(true)
                                }
                            },
                        )
                    }
                }
            val capabilities =
                CapabilityCenter(
                    object : CapabilityResolver {
                        override fun resolve(capability: Capability): CapabilityGrant =
                            error("This isolated fixture must not access platform capabilities")
                    },
                )
            ScheduledRuntimeFixture(
                wire = wire,
                capabilityCenter = capabilities,
                retainStorage = true,
            ).use { fixture ->
                fixture.ready()
                configureOwnedModelSession(fixture.storage, fixture.chat, "bound", AgentMode.PLAN)
                fixture.chat
                    .sendSubmission(
                        ChatSubmission(
                            "bound",
                            0,
                            UUID.randomUUID().toString(),
                            "SYNTHETIC_TIMEOUT_CASE: answer briefly.",
                        ),
                    ).await()
                kotlinx.coroutines.withTimeout(10_000) { entered.await() }
                try {
                    kotlinx.coroutines.withTimeout(100) {
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            finishOwnedCase(
                                fixture.storage,
                                fixture.chat,
                                "bound",
                                gate::close,
                                { error("SYNTHETIC_EVIDENCE_FAILURE") },
                                { stopped ->
                                    check(stopped)
                                    restored = true
                                },
                            )
                        }
                    }
                } catch (failure: IllegalStateException) {
                    check(failure.message == "SYNTHETIC_EVIDENCE_FAILURE")
                }
                kotlinx.coroutines.withTimeout(10_000) {
                    while (fixture.storage.turns.listBySession("bound").any {
                            !TurnState.valueOf(it.state).isTerminal
                        }
                    ) {
                        kotlinx.coroutines.delay(20)
                    }
                }
                val turn =
                    fixture.storage.turns
                        .listBySession("bound")
                        .single()
                check(turn.state == "CANCELLED")
                check(closed.get() && requests.get() == 1 && restored)
                File(app.filesDir, "closed-loop-eval/timeout-regression.json").writeText(
                    org.json
                        .JSONObject()
                        .put("database", fixture.databaseName)
                        .put("gateFile", gateFile.name)
                        .put("turn", turn.id)
                        .put("pid", android.os.Process.myPid())
                        .put("state", turn.state)
                        .put("requests", requests.get())
                        .put("streamClosed", closed.get())
                        .put("restoredAfterEvidenceFailure", restored)
                        .toString(),
                )
            }
        }

    private fun verifyRestart() =
        kotlinx.coroutines.runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val facts = org.json.JSONObject(File(app.filesDir, "closed-loop-eval/timeout-regression.json").readText())
            check(facts.getInt("pid") != android.os.Process.myPid())
            val name = facts.getString("database")
            val storage = HelixStorage.open(app, name, File(app.cacheDir, "$name/content"))
            try {
                TurnRecovery(storage, SystemClock()).recover()
                var starts = 0
                val settlement = AutomaticRecoverySettlement(storage, SystemClock())
                AutomaticTurnRecovery(
                    RoomTurnRecoveryStore(storage, Any(), settlement::finish),
                    SystemClock(),
                    { _, _ ->
                        starts++
                        error("Cancelled task must not admit a provider request")
                    },
                    { throw it },
                ).recover(facts.getString("turn"))
                check(starts == 0)
                check(
                    storage.turns
                        .listBySession("bound")
                        .single { it.id == facts.getString("turn") }
                        .state == "CANCELLED",
                )
                check(storage.modelCalls.listByTurn(facts.getString("turn")).size == 1)
                val gate =
                    OwnedModelRequestGate(
                        File(app.filesDir, "closed-loop-eval/${facts.getString("gateFile")}"),
                        "SYNTHETIC_TIMEOUT_CASE",
                    )
                org.junit.Assert.assertThrows(java.io.IOException::class.java) { gate.admit(syntheticRequest()) }
                verifyExplicitUserAdmission(storage)
                facts
                    .put("restartPid", android.os.Process.myPid())
                    .put("restartStarts", starts)
                    .put("restartGateRejected", true)
                    .put("explicitUserTurnAdmitted", true)
                File(app.filesDir, "closed-loop-eval/timeout-regression-verified.json").writeText(facts.toString())
            } finally {
                storage.close()
            }
        }

    private fun verifyExplicitUserAdmission(storage: HelixStorage) {
        val next = UUID.randomUUID().toString()
        val store = RoomTurnLifecycleStore(storage, SystemClock(), { UUID.randomUUID().toString() })
        val result =
            store.admit(
                com.helix.core.agent.turn.TurnAdmissionCommand(
                    com.helix.core.agent.turn.TurnAdmissionIdentity(
                        "bound",
                        next,
                        UUID.randomUUID().toString(),
                        "explicit-$next",
                        "synthetic-input",
                    ),
                    com.helix.core.agent.turn.TurnModelBinding(
                        BuiltInModel.PROVIDER_ID,
                        BuiltInModel.MODEL_ID,
                        "synthetic explicit request",
                    ),
                    com.helix.core.agent.turn
                        .TurnAdmissionInput("Owner explicitly starts a new synthetic task."),
                    com.helix.core.agent
                        .RunControlConfig(AgentMode.PLAN, false, TurnBudgets(4096)),
                    com.helix.core.agent.GoalWakeReason.USER_OPEN,
                    BuiltInModel.MODEL_ID,
                ),
            )
        check(result is com.helix.core.agent.turn.TurnAdmissionResult.Started)
        // Admission only: there is no model driver in this held process. Preserve a stopped final fixture.
        check(storage.turns.requestPause(next, System.currentTimeMillis()))
        TurnRecovery(storage, SystemClock()).recover()
        store.cancel(CancelTurnCommand(next, "bound", false, true, "USER_STOP"))
    }

    private fun syntheticRequest() =
        WireRequest(
            "POST",
            BuiltInModel.GENERATION_URL,
            emptyMap(),
            org.json
                .JSONObject()
                .put("model", "Qwen3.8-27B")
                .put("max_tokens", 4096)
                .put(
                    "messages",
                    org.json.JSONArray().put(
                        org.json
                            .JSONObject()
                            .put("role", "user")
                            .put("content", "SYNTHETIC_TIMEOUT_CASE"),
                    ),
                ).toString()
                .toByteArray(),
        )

    private fun verifyRequestGate() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val root = File(app.cacheDir, "gate-${UUID.randomUUID()}").apply { mkdirs() }
        var now = 1000L

        fun gate(name: String) =
            OwnedModelRequestGate(
                File(root, name),
                "SYNTHETIC_TIMEOUT_CASE",
                { now },
                { now },
                createNew = !File(root, name).exists(),
            )
        val request = syntheticRequest()
        repeat(24) { gate("quota").admit(request) }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { gate("quota").admit(request) }
        gate("deadline").admit(request)
        now += 240_000
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { gate("deadline").admit(request) }
        val unboundedFile = File(root, "unbounded")
        OwnedModelRequestGate(
            unboundedFile,
            "SYNTHETIC_TIMEOUT_CASE",
            { now },
            { now },
            createNew = true,
            bounded = false,
        )
        now += 240_000
        val unbounded = OwnedModelRequestGate(unboundedFile, "SYNTHETIC_TIMEOUT_CASE", { now }, { now })
        repeat(25) { unbounded.admit(request) }
        unbounded.close()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { unbounded.admit(request) }
        gate("closed").close()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { gate("closed").admit(request) }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            gate("destination").admit(
                WireRequest(
                    "POST",
                    "https://example.invalid",
                    emptyMap(),
                    request.body,
                ),
            )
        }
        for ((field, value) in listOf("model" to "another-model", "max_tokens" to 4097, "messages" to "foreign-task")) {
            val payload = org.json.JSONObject(requireNotNull(request.body).toString(Charsets.UTF_8)).put(field, value)
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                gate("payload").admit(WireRequest("POST", request.url, emptyMap(), payload.toString().toByteArray()))
            }
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            OwnedModelRequestGate(File(root, "missing"), "SYNTHETIC_TIMEOUT_CASE")
        }
        File(root, "corrupt").writeBytes(byteArrayOf(0, 8, 1))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            OwnedModelRequestGate(File(root, "corrupt"), "SYNTHETIC_TIMEOUT_CASE")
        }
    }

    private fun withStorage(
        app: HelixApplication,
        block: (HelixStorage) -> Unit,
    ) {
        val storage = HelixStorage.create(app)
        try {
            block(storage)
        } finally {
            storage.close()
        }
    }
}
