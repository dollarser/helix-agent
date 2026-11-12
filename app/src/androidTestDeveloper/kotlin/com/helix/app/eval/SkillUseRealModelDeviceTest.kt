package com.helix.app.eval

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnState
import com.helix.core.model.input.InputSkillActivation
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.UUID

/** Owner opt-in. Only bundled help and a synthetic session; no scripts, files or account mutations. */
class SkillUseRealModelDeviceTest {
    @Test fun explicitSkillReachesRealModelAndItsReferenceIsActuallyRead() =
        runBlocking {
            require(InstrumentationRegistry.getArguments().getString("helixRealModel") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            ActivityScenario.launch(MainActivity::class.java).use {
                val c = app.appContainer
                withTimeout(30_000) { while (c.chatService.screen.value.openSessionId == null) delay(50) }
                check(
                    c.storage.turns
                        .listActive()
                        .isEmpty(),
                )
                val provider =
                    c.providerService.rows.value.singleOrNull {
                        it.id == BuiltInModel.PROVIDER_ID && it.chatSelectable &&
                            BuiltInModel.MODEL_ID in it.conversationModels
                    }
                check(provider != null) { "CONFIGURED_REAL_MODEL_UNAVAILABLE" }
                runCase(app, c, provider.id)
            }
        }

    private suspend fun runCase(
        app: HelixApplication,
        c: AppContainer,
        provider: String,
    ) {
        val previous = c.chatService.screen.value.openSessionId
        val trial = "skill-use-${UUID.randomUUID()}"
        val session = c.chatService.createSession("Synthetic skill consumption", provider, BuiltInModel.MODEL_ID)
        val probe = SkillConsumptionProbe(app, c, trial)
        val evidence = JSONObject().put("status", "failed").put("trial", trial)
        val start = SystemClock.elapsedRealtime()
        try {
            configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.ACT)
            exercise(c, session, trial, probe, evidence)
            evidence.put("status", "passed")
        } finally {
            finishOwnedCase(c.storage, c.chatService, session, probe::close, {
                evidence
                    .put("firstRequestConsumedSkill", probe.consumed)
                    .put("generationRequests", probe.requests)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - start)
                File(app.cacheDir, "skill-use-real-model.json").writeText(evidence.toString())
            }, { stopped ->
                if (stopped) {
                    probe.restore()
                    if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
                    c.storage.deleteSessionPermanently(session)
                }
            })
        }
    }

    private suspend fun exercise(
        c: AppContainer,
        session: String,
        trial: String,
        probe: SkillConsumptionProbe,
        evidence: JSONObject,
    ) {
        val skill = c.skillRepository.list(session).single { it.key.name == "helix-docs" }
        val activation =
            InputSkillActivation(
                skill.key.source.name,
                skill.key.name,
                skill.key.snapshotHash,
                trial,
                skill.key.ownerId,
            )
        val prompt =
            "Case $trial。仅查阅 Helix 内置帮助：安装技能会不会自动执行附带脚本？" +
                "请按本次技能读取 references/manifest.json，再按其索引读取相关说明，用中文简短回答并标注指南来源。" +
                "不要联网查询，不安装或执行任何内容，不修改设置。"
        val admission =
            c.chatService
                .sendSubmission(
                    ChatSubmission(
                        session,
                        0,
                        trial,
                        prompt,
                        skillActivations = listOf(activation),
                    ),
                ).await()
        check(admission.outcome is ChatSubmissionOutcome.Accepted)
        while (true) {
            val turn =
                c.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null && TurnState.valueOf(turn.state).isTerminal) break
            delay(100)
        }
        val turn =
            c.storage.turns
                .listBySession(session)
                .last()
        val calls = c.storage.toolCalls.listByTurn(turn.id)
        val answer =
            c.storage.messages
                .listBySession(session)
                .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                .mapNotNull { c.storage.messages.readContent(it) }
                .joinToString("\n")
        evidence
            .put("turnState", turn.state)
            .put("toolCalls", calls.size)
            .put("referenceReads", calls.count { it.name == "skills.read_resource" && it.state == "COMPLETED" })
            .put("failedTools", calls.count { it.state == "FAILED" })
            .put("answer", answer)
        check(turn.state == "COMPLETED" && probe.consumed)
        check(calls.any { it.name == "skills.read_resource" && it.state == "COMPLETED" })
        val mutations = setOf("skills.install", "skills.enable", "skills.run", "write", "edit", "bash")
        check(calls.none { it.name in mutations })
        check("脚本" in answer && ("不会" in answer || "不自动" in answer))
    }
}

/** Captures only booleans/counts from the submitted request; headers and request bodies are never logged. */
private class SkillConsumptionProbe(
    app: HelixApplication,
    c: AppContainer,
    trial: String,
) {
    private val gate =
        OwnedModelRequestGate(
            File(app.filesDir, "closed-loop-eval/$trial.budget"),
            trial,
            createNew = true,
            bounded = false,
        )
    private val factory =
        c.providerService.javaClass
            .getDeclaredField("factory")
            .apply { isAccessible = true }
            .get(c.providerService)
    private val field = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
    private val original = field.get(factory) as com.helix.app.account.BuiltInAccountWire

    @Volatile var consumed = false
        private set

    @Volatile var requests = 0
        private set

    init {
        val delegate = original.newInvocation()
        field.set(
            factory,
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    gate.admit(request)
                    requests++
                    if (requests == 1) {
                        val body = request.body?.toString(Charsets.UTF_8).orEmpty()
                        consumed = "# Helix user guide" in body && "[USER SKILL ARGUMENTS]" in body && trial in body
                    }
                    return delegate.open(request)
                }
            },
        )
    }

    fun close() = gate.close()

    fun restore() {
        field.set(factory, original)
    }
}
