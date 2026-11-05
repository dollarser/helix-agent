package com.helix.app.eval

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.chat.sseResponse
import com.helix.app.chat.textAnswerStream
import com.helix.app.provider.BuiltInModel
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnContextAssembler
import com.helix.core.agent.TurnContextRequest
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnAdmissionResult
import com.helix.core.agent.turn.TurnLifecycleStore
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Real container, production admission/context ports, in-memory wire only. No outgoing delegate exists. */
class SkillAdmissionTimingDeviceTest {
    @Suppress("LongMethod") // Install observers once, measure three owned sessions, restore every original port.
    @Test
    fun profileOwnedSessionsWithAnInMemoryModel() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            withTimeout(30_000) { app.runtimeRecoveryReady.await() }
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )

            val previous = c.chatService.screen.value.openSessionId
            val factory = requireNotNull(field(c.providerService, "factory").get(c.providerService))
            val wireField = field(factory, "builtInWire")
            val originalWire = wireField.get(factory)
            val bootstrap = JSONObject()
            val loopStarted = now()
            val loop = (field(c.chatService, "agentLoop\$delegate").get(c.chatService) as Lazy<*>).value!!
            bootstrap.put("loop_lazy_initialization_ms", now() - loopStarted)
            val core = requireNotNull(field(loop, "core").get(loop))
            val contextField = field(core, "contextAssembler")
            val originalContext = contextField.get(core) as TurnContextAssembler
            val engine = requireNotNull(c.turnEngine)
            val storeField = field(engine, "store")
            val originalStore = engine.store
            val times = ConcurrentHashMap<String, Long>()
            val projectionEvents = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
            val scheduler =
                (
                    field(
                        c.chatService,
                        "sessionWorkScheduler\$delegate",
                    ).get(c.chatService) as Lazy<*>
                ).value!!
            val projectionField = field(scheduler, "refreshProjection")

            @Suppress("UNCHECKED_CAST")
            val originalProjection = projectionField.get(scheduler) as () -> Unit
            projectionField.set(scheduler, {
                val start = now()
                try {
                    originalProjection()
                } finally {
                    projectionEvents.add(JSONObject().put("startAt", start).put("elapsedMs", now() - start))
                }
            })
            val rows = JSONArray()
            val samples = ConcurrentHashMap<String, Int>()
            val sampleEnabled = InstrumentationRegistry.getArguments().getString("helix.timing.sample") == "true"
            val iterations = if (sampleEnabled) 1 else 3
            val sampler =
                java.util.concurrent.Executors
                    .newSingleThreadScheduledExecutor()
            var sampling: java.util.concurrent.ScheduledFuture<*>? = null
            var marker = "NOT_STARTED"
            var safe = true
            wireField.set(
                factory,
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        if (request.method != "POST" || request.url != BuiltInModel.GENERATION_URL ||
                            marker !in request.body?.toString(Charsets.UTF_8).orEmpty()
                        ) {
                            throw IOException("OWNED_TIMING_FIXTURE_ONLY")
                        }
                        times["first_wire_at"] = now()
                        return sseResponse(textAnswerStream("SYNTHETIC_TIMING_DONE"))
                    }
                },
            )
            contextField.set(
                core,
                object : TurnContextAssembler by originalContext {
                    override suspend fun build(
                        id: String,
                        turn: String,
                        retry: String?,
                        control: RunControlConfig,
                    ): TurnContextRequest {
                        val start = now()
                        return originalContext.build(id, turn, retry, control).also {
                            times["context_build_ms"] = now() - start
                            times["context_end_at"] = now()
                        }
                    }
                },
            )
            storeField.set(
                engine,
                object : TurnLifecycleStore by originalStore {
                    override fun admit(command: TurnAdmissionCommand): TurnAdmissionResult {
                        times["store_admit_at"] = now()
                        sampling?.cancel(false)
                        return originalStore.admit(command).also {
                            times["store_admit_ms"] = now() - times.getValue("store_admit_at")
                        }
                    }
                },
            )
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    repeat(iterations) { iteration ->
                        times.clear()
                        projectionEvents.clear()
                        marker = "SYNTHETIC_ADMISSION_TIMING_${UUID.randomUUID()}"
                        val created = now()
                        val id =
                            c.chatService.createSession(
                                "Synthetic admission timing",
                                BuiltInModel.PROVIDER_ID,
                                BuiltInModel.MODEL_ID,
                            )
                        times["session_create_ms"] = now() - created
                        val started = now()
                        try {
                            withTimeout(240_000) {
                                var at = now()
                                check(c.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
                                times["profile_switch_ms"] = now() - at
                                at = now()
                                val permissions = SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS)
                                c.sessionPermissionEdit.saveSessionConfig(id, permissions, System.currentTimeMillis())
                                times["permission_save_ms"] = now() - at
                                configureOwnedModelSession(c.storage, c.chatService, id, AgentMode.ACT) { key, ms ->
                                    times[key] = ms
                                }
                                times["send_at"] = now()
                                if (sampleEnabled) {
                                    sampling =
                                        sampler.scheduleAtFixedRate({
                                            sampleOwnStacks(samples)
                                        }, 0, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                                }
                                val prompt = "$marker: return a short answer; do not call tools."
                                val request = ChatSubmission(id, 0, UUID.randomUUID().toString(), prompt)
                                val receipt = c.chatService.sendSubmission(request).await()
                                check(receipt.outcome is ChatSubmissionOutcome.Accepted)
                                times["send_await_ms"] = now() - times.getValue("send_at")
                                while (c.storage.turns
                                        .listBySession(id)
                                        .none { TurnState.valueOf(it.state).isTerminal }
                                ) {
                                    delay(20)
                                }
                            }
                            val row =
                                JSONObject()
                                    .put("iteration", iteration)
                                    .put("session", id)
                                    .put("case_ms", now() - started)
                                    .put("wire", "in-memory")
                            times.forEach { (key, value) -> row.put(key, value) }
                            row.put("schedulerProjectionEvents", JSONArray(projectionEvents.toList()))
                            row.put(
                                "submit_to_store_admit_ms",
                                times.getValue("store_admit_at") -
                                    times.getValue("send_at"),
                            )
                            row.put(
                                "context_end_to_wire_ms",
                                times.getValue("first_wire_at") -
                                    times.getValue("context_end_at"),
                            )
                            val turn =
                                c.storage.turns
                                    .listBySession(id)
                                    .single()
                            row.put(
                                "modelCalls",
                                c.storage.modelCalls
                                    .listByTurn(turn.id)
                                    .size,
                            )
                            rows.put(row)
                        } finally {
                            finishOwnedCase(c.storage, c.chatService, id, {}, {}, { stopped -> safe = safe && stopped })
                        }
                    }
                }
            } finally {
                sampler.shutdownNow()
                contextField.set(core, originalContext)
                storeField.set(engine, originalStore)
                projectionField.set(scheduler, originalProjection)
                if (safe) wireField.set(factory, originalWire)
                if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
                val candidates = (c.storage.turns.pendingTasks() + c.storage.turns.recent()).distinctBy { it.id }.size
                val discovered =
                    if (rows.length() == 0) {
                        emptyList()
                    } else {
                        c.toolPipeline.mcpDiscovery
                            .search(
                                rows.getJSONObject(rows.length() - 1).getString("session"),
                                "skills author install enable run discover",
                                8,
                            ).map { it.name.value }
                    }
                File(app.filesDir, "closed-loop-eval/admission-timing.json").writeText(
                    JSONObject()
                        .put("pid", android.os.Process.myPid())
                        .put("wire", "in-memory")
                        .put("channelProfile", c.profileStore.profile.name)
                        .put("candidateTurns", candidates)
                        .put("postTaskDiscovery", JSONArray(discovered))
                        .put("bootstrap", bootstrap)
                        .put("postTaskReadCosts", readCosts(c))
                        .put("sampling", sampleEnabled)
                        .put(
                            "topStacks",
                            JSONArray(
                                samples.entries.sortedByDescending { it.value }.take(20).map {
                                    JSONObject().put("count", it.value).put("stack", it.key)
                                },
                            ),
                        ).put("rows", rows)
                        .toString(),
                )
            }
        }

    private fun sampleOwnStacks(samples: ConcurrentHashMap<String, Int>) {
        val ownThread = Thread.currentThread()
        Thread.getAllStackTraces().forEach { (thread, stack) ->
            if (thread != ownThread && !thread.name.startsWith("Instr:") &&
                stack.any { it.className.startsWith("com.helix.") }
            ) {
                val key =
                    thread.name + "\n" +
                        (
                            stack.take(
                                4,
                            ) + stack.filter { it.className.startsWith("com.helix.") }
                        ).distinct().joinToString("\n") {
                            "${it.className}.${it.methodName}:${it.lineNumber}"
                        }
                samples.merge(key, 1, Int::plus)
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** Run after the measured cases, never prewarm admission or interpret independent reads as additive. */
    private fun readCosts(c: com.helix.app.AppContainer): JSONObject {
        val costs = JSONObject()

        fun <T> measure(
            key: String,
            block: () -> T,
        ): T {
            val started = now()
            return block().also { costs.put(key, now() - started) }
        }
        val pending = measure("pendingTasksMs") { c.storage.turns.pendingTasks() }
        val recent = measure("recentMs") { c.storage.turns.recent() }
        val turns = (pending + recent).distinctBy { it.id }
        measure("metadataMs") { c.storage.turns.backgroundMetadata(turns.map { it.id }) }
        measure("wholeTaskQueryMs") {
            com.helix.app.chat
                .BackgroundTaskQuery(c.storage)
                .read()
        }
        measure("backgroundJobsMs") {
            com.helix.app.proot.ProotToolModule
                .backgroundJobs(c.storage)
        }
        return costs.put("candidateTurns", turns.size)
    }

    private fun field(
        owner: Any,
        name: String,
    ) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
