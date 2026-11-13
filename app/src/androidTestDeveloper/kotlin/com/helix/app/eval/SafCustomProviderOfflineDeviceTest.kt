package com.helix.app.eval

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.proot.NaturalFixtureAvailability
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.wire.WireClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Offline scripted transport through the production custom-provider adapter and actual system SAF. */
class SafCustomProviderOfflineDeviceTest {
    @get:org.junit.Rule val compose =
        androidx.compose.ui.test.junit4.v2
            .createComposeRule()

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Test
    fun scriptedProviderConsumesActualSafEvidence() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("safCustomProviderOffline") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            check(app.packageName == "com.helix.agent.developer")
            val c = app.awaitAppContainer()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            check(c.memory?.list(com.helix.core.workspace.memory.MemoryScope.Global).isNullOrEmpty())
            val suffix = UUID.randomUUID().toString().take(8)
            val directory = File(app.cacheDir, "saf-custom-provider-offline/$suffix").apply { check(mkdirs()) }
            val fixture =
                SafModelFixture.open(
                    compose,
                    app,
                    c,
                    requireNotNull(args.getString("helixSafReliabilityFolder")),
                    suffix,
                )
            try {
                val relative = "teams/flight/releases/release-$suffix.md"
                val token = "CLEARANCE_${UUID.randomUUID().toString().replace("-", "") }"
                fixture.put(
                    relative,
                    "# ORCHID release\nState: CURRENT\nOwner: Synthetic Flight\n" +
                        "Approval token: $token\n" +
                        "Rollback: restore synthetic checkpoint SEED-$suffix\n",
                )
                repeat(
                    24,
                ) { index ->
                    fixture.put(
                        "archive/batch-$index/release-$index.md",
                        "# ORCHID release\nState: SUPERSEDED\nApproval token: OLD-$index\n",
                    )
                }
                fixture.put(
                    "teams/other/release-current.md",
                    "# JASMINE release\nState: CURRENT\nApproval token: WRONG-PROJECT\n",
                )
                val reference = fixture.reference
                val provider =
                    c.providerService.create(
                        com.helix.app.provider.ProviderDraft(
                            null,
                            "Offline SAF fixture $suffix",
                            com.helix.core.model.ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            com.helix.core.model.NormalizedEndpoint
                                .parse("http://127.0.0.1:1/v1"),
                            "saf-offline",
                            "{}",
                            false,
                        ),
                        null,
                    )
                try {
                    val factory = requireNotNull(field(c.providerService, "factory").get(c.providerService))
                    val wireField = field(factory, "wire")
                    val original = wireField.get(factory) as WireClient
                    try {
                        val wire = SafOfflineModelWire(directory, reference, relative)
                        wireField.set(factory, wire)
                        check(c.providerService.runConnectionTest(provider) is com.helix.provider.api.ProbeOutcome.Ok)
                        val session =
                            c.chatService.createSession(
                                "SAF offline adapter $suffix",
                                provider,
                                "saf-offline",
                            )
                        val allowed =
                            setOf(
                                "tools.search",
                                "tools.read_result",
                                "glob",
                                "grep",
                                "read",
                                "files.list",
                                "files.stat",
                            )
                        val availability =
                            NaturalFixtureAvailability(
                                c.storage.toolAvailability,
                                c.toolPipeline.registry
                                    .all()
                                    .map { it.origin.canonicalOf() to it.name.value },
                                session,
                            )
                        var completed = false
                        var cleanup = false

                        fun evidence() {
                            val turns = c.storage.turns.listBySession(session)
                            val calls = turns.flatMap { c.storage.toolCalls.listByTurn(it.id) }
                            val result =
                                JSONObject()
                                    .put("completed", completed)
                                    .put("realModel", false)
                                    .put("realSaf", true)
                                    .put("scriptedTransport", true)
                                    .put("expectedToken", token)
                                    .put("expectedPath", relative)
                                    .put("httpStatuses", JSONArray(wire.statuses))
                                    .put("wireRequests", wire.calls)
                                    .put(
                                        "answer",
                                        turns.lastOrNull()?.let { liveResourceAnswer(c, session, it.id) }.orEmpty(),
                                    ).put(
                                        "turns",
                                        JSONArray(
                                            turns.map {
                                                JSONObject()
                                                    .put(
                                                        "id",
                                                        it.id,
                                                    ).put("state", it.state)
                                                    .put("errorCode", it.errorCode)
                                            },
                                        ),
                                    ).put(
                                        "tools",
                                        JSONArray(
                                            calls.map { call ->
                                                val saved = c.storage.toolResults.byToolCall(call.callId)
                                                JSONObject()
                                                    .put("id", call.id)
                                                    .put("callId", call.callId)
                                                    .put("turnId", call.turnId)
                                                    .put("name", call.name)
                                                    .put("state", call.state)
                                                    .put("args", JSONObject(call.argsJson))
                                                    .put(
                                                        "resultStatus",
                                                        saved?.status,
                                                    ).put(
                                                        "result",
                                                        saved?.let { c.storage.toolResults.readContent(it) },
                                                    )
                                            },
                                        ),
                                    )
                            File(
                                directory,
                                if (cleanup) "cleanup-result.json" else "result.json",
                            ).writeText(result.toString(2))
                        }
                        try {
                            availability.configure(allowed)
                            c.sessionPermissionEdit.saveSessionConfig(
                                session,
                                SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
                                System.currentTimeMillis(),
                            )
                            configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.ACT)
                            wireField.set(factory, wire)
                            ActivityScenario.launch(MainActivity::class.java).use {
                                val prompt =
                                    "在合成测试目录 $reference 中定位 ORCHID 项目当前有效的发布记录。" +
                                        "目录有历史版本和其他项目，请自行查找并核实正文。" +
                                        "告诉我准确相对文件路径、Approval token 和 Rollback 原文，" +
                                        "并说明如何排除旧版。" +
                                        "只读这个目录，不修改文件，不访问网络。"
                                val receipt =
                                    c.chatService
                                        .sendSubmission(
                                            ChatSubmission(session, 0, UUID.randomUUID().toString(), prompt),
                                        ).await()
                                assertTrue(receipt.outcome is ChatSubmissionOutcome.Accepted)
                                withTimeout(300_000) {
                                    while (true) {
                                        val turns = c.storage.turns.listBySession(session)
                                        turns
                                            .flatMap { c.storage.toolCalls.listByTurn(it.id) }
                                            .filter { it.state == "AWAITING_APPROVAL" }
                                            .forEach { call ->
                                                check(call.name in allowed)
                                                c.chatService.approveApproval(
                                                    requireNotNull(c.storage.approvals.byToolCall(call.id)).id,
                                                )
                                            }
                                        evidence()
                                        check(turns.none { it.state in setOf("FAILED", "CANCELLED") })
                                        if (turns.lastOrNull()?.state == "COMPLETED") break
                                        delay(250)
                                    }
                                }
                                evidence()
                                assertTrue(
                                    "Require grounded answer and actual glob/grep/read consumption: $directory",
                                    verifyFileLocationEvidence(directory),
                                )
                                verifyOfflineEvidenceRejection(directory)
                                completed = true
                                evidence()
                            }
                        } finally {
                            cleanup = true
                            finishOwnedCase(c.storage, c.chatService, session, {}, { evidence() }) { stopped ->
                                if (stopped) wireField.set(factory, original)
                                availability.restore()
                                c.storage.sessions.archive(session, System.currentTimeMillis())
                                c.chatService.closeSession()
                            }
                        }
                    } finally {
                        wireField.set(factory, original)
                    }
                } finally {
                    c.providerService.delete(provider)
                }
            } finally {
                fixture.close()
            }
        }

    private fun field(
        owner: Any,
        name: String,
    ) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
