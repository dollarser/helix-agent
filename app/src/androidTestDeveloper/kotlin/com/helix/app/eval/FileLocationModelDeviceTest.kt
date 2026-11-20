package com.helix.app.eval

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.proot.LogComparisonWire
import com.helix.app.proot.NaturalFixtureAvailability
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.workspace.FileScopePath
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

/** Separate autonomous task acceptance from explicitly requested search-tool coverage. */
class FileLocationModelDeviceTest {
    @Test
    fun autonomouslyLocatesCurrentReleaseEvidence() = runCase(FileLocationAcceptance.AUTONOMOUS)

    @Test
    fun consumesGlobAndGrepResultsToLocateEvidence() = runCase(FileLocationAcceptance.SEARCH_TOOLS)

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun runCase(acceptance: FileLocationAcceptance) =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString(acceptance.argument) == "true")
            val layout = FileLocationLayout.valueOf(args.getString("fileLocationLayout") ?: "BASELINE")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            check(app.packageName in setOf("com.helix.agent.developer", "com.helix.agent.developer.asrvalidation"))
            val c = app.awaitAppContainer()
            app.account.restore()
            check(app.account.signedIn.value) { "Model account unavailable: ${app.account.status.value}" }
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            check(c.memory?.list(com.helix.core.workspace.memory.MemoryScope.Global).isNullOrEmpty())
            val suffix = UUID.randomUUID().toString().take(8)
            val directory = File(app.cacheDir, "file-location-model/$suffix").apply { check(mkdirs()) }
            val session =
                c.chatService.createSession(
                    "File location ${acceptance.name.lowercase()} $suffix",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val binding = requireNotNull(c.storage.workspaces.binding(session))
            val root =
                c.storage.workspaces
                    .managedDirectory(binding.workspaceId)
                    .resolve(binding.relativePath)
                    .toFile()
            val fixture = File(root, "search-$suffix").apply { check(mkdirs()) }
            val token = "CLEARANCE_${UUID.randomUUID().toString().replace("-", "") }"
            val relative = "${layout.targetDirectory}/release-$suffix.md"
            File(fixture, relative).apply {
                parentFile!!.mkdirs()
                writeText(
                    "# ORCHID release\nState: CURRENT\nOwner: Synthetic Flight\n" +
                        "Approval token: $token\nRollback: restore synthetic checkpoint SEED-$suffix\n",
                )
            }
            repeat(24) { index ->
                File(fixture, "${layout.oldDirectory}/batch-$index/release-$index.md").apply {
                    parentFile!!.mkdirs()
                    writeText("# ORCHID release\nState: SUPERSEDED\nApproval token: OLD-$index\n")
                }
            }
            File(fixture, layout.otherPath).apply {
                parentFile!!.mkdirs()
                writeText("# JASMINE release\nState: CURRENT\nApproval token: WRONG-PROJECT\n")
            }
            val child = listOf(binding.relativePath, fixture.name).filter { it.isNotEmpty() }.joinToString("/")
            val reference = FileScopePath(binding.workspaceId, child).toModelReference()
            val factory = requireNotNull(field(c.providerService, "factory").get(c.providerService))
            val wireField = field(factory, "builtInWire")
            val original = wireField.get(factory) as WireClient
            val wire =
                LogComparisonWire(
                    (original as? BuiltInAccountWire)?.newInvocation() ?: original,
                    directory,
                    captureSyntheticResponses = true,
                ) { "" }
            val allowed = setOf("tools_search", "tools_read_result", "glob", "grep", "read", "files_list", "files_stat")
            val availability =
                NaturalFixtureAvailability(
                    c.storage.toolAvailability,
                    c.toolPipeline.registry
                        .all()
                        .map { it.origin.canonicalOf() to it.name.value },
                    session,
                )
            val began = android.os.SystemClock.elapsedRealtime()
            var fixtureApprovals = 0
            val admission = SyntheticModelSubmission(session) { fixtureApprovals++ }
            var completed = false
            var cleanup = false

            fun evidence() {
                val turns = c.storage.turns.listBySession(session)
                val calls = turns.flatMap { c.storage.toolCalls.listByTurn(it.id) }
                val result =
                    JSONObject()
                        .put("completed", completed)
                        .put("acceptance", acceptance.name)
                        .put("layout", layout.name)
                        .put("turnCompleted", turns.lastOrNull()?.state == "COMPLETED")
                        .put("realModel", true)
                        .put(
                            "modelIdentities",
                            JSONArray(
                                turns.flatMap { c.storage.modelCalls.listByTurn(it.id) }.map {
                                    val snapshot = JSONObject(it.providerSnapshot)
                                    JSONObject()
                                        .put("providerId", snapshot.getString("providerId"))
                                        .put("modelId", snapshot.getString("model"))
                                },
                            ),
                        ).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - began)
                        .put("humanInterventions", 0)
                        .put("fixtureApprovals", fixtureApprovals)
                        .put("submissionReceipts", admission.receipts)
                        .put("expectedToken", token)
                        .put("expectedPath", relative)
                        .put("httpStatuses", JSONArray(wire.statuses))
                        .put("wireRequests", wire.calls)
                        .put(
                            "modelCalls",
                            turns.sumOf {
                                c.storage.modelCalls
                                    .listByTurn(it.id)
                                    .size
                            },
                        ).put(
                            "answer",
                            turns
                                .lastOrNull()
                                ?.let { liveResourceAnswer(c, session, it.id) }
                                .orEmpty(),
                        ).put(
                            "turns",
                            JSONArray(
                                turns.map {
                                    JSONObject().put("id", it.id).put("state", it.state).put("errorCode", it.errorCode)
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
                                        ).put("result", saved?.let { c.storage.toolResults.readContent(it) })
                                },
                            ),
                        )
                File(directory, if (cleanup) "cleanup-result.json" else "result.json").writeText(result.toString(2))
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
                        "在合成测试目录 $reference 中" + layout.request +
                            "告诉我准确相对文件路径、Approval token 和 Rollback 原文，" +
                            "并说明如何排除旧版。" +
                            "只读这个目录，不修改文件，不访问网络。" + acceptance.prompt
                    val request = ChatSubmission(session, 0, UUID.randomUUID().toString(), prompt)
                    val receipt = admission.submit(c, request)
                    evidence()
                    assertTrue(receipt.outcome is ChatSubmissionOutcome.Accepted)
                    withTimeout(300_000) {
                        while (true) {
                            val turns = c.storage.turns.listBySession(session)
                            turns
                                .flatMap { c.storage.toolCalls.listByTurn(it.id) }
                                .filter { it.state == "AWAITING_APPROVAL" }
                                .forEach { call ->
                                    check(call.name in allowed)
                                    fixtureApprovals += 1
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
                        "Require ${acceptance.name} evidence: $directory",
                        verifyFileLocationEvidence(directory, acceptance),
                    )
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
        }

    private fun field(
        owner: Any,
        name: String,
    ) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
