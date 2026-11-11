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

/** Opt-in public browsing through the actual ChatService, model and browser plugin. */
class BrowserSearchModelDeviceTest {
    @Test
    fun sourceAuditRejectsMissingReadingConsumptionAndCitation() {
        exerciseBrowserSearchEvidenceVerifier()
    }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
    )
    @Test
    fun modelSearchesReadsOpensAndCitesPublicSource() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("browserSearchModelAcceptance") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            check(app.packageName == "com.helix.agent.developer.asrvalidation")
            val c = app.awaitAppContainer()
            check(app.account.signedIn.value)
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            check(c.memory?.list(com.helix.core.workspace.memory.MemoryScope.Global).isNullOrEmpty())
            val suffix = UUID.randomUUID().toString().take(8)
            val directory =
                File(
                    app.cacheDir,
                    "browser-search-model/$suffix",
                ).apply { check(mkdirs()) }
            val session =
                c.chatService.createSession(
                    "Public browser model acceptance $suffix",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val factory =
                requireNotNull(
                    field(
                        c.providerService,
                        "factory",
                    ).get(c.providerService),
                )
            val wireField =
                field(
                    factory,
                    "builtInWire",
                )
            val original = wireField.get(factory) as WireClient
            val wire =
                LogComparisonWire(
                    (original as? BuiltInAccountWire)?.newInvocation() ?: original,
                    directory,
                    captureSyntheticResponses = true,
                ) { "" }
            val allowed =
                setOf(
                    "tools.search",
                    "tools.read_result",
                    "browser.search",
                    "browser.snapshot",
                    "browser.open",
                    "browser.navigate",
                    "browser.click",
                    "browser.tabs",
                    "browser.wait",
                    "browser.scroll",
                    "browser.close",
                )
            val availability =
                NaturalFixtureAvailability(
                    c.storage.toolAvailability,
                    c.toolPipeline.registry.all().map {
                        it.origin.canonicalOf() to it.name.value
                    },
                    session,
                )
            var completed = false

            fun evidence() {
                val turns = c.storage.turns.listBySession(session)
                val calls = turns.flatMap { c.storage.toolCalls.listByTurn(it.id) }
                val messages = c.storage.messages.listBySession(session)
                val result =
                    JSONObject()
                        .put("completed", completed)
                        .put("realModel", true)
                        .put("httpStatuses", JSONArray(wire.statuses))
                        .put("wireRequests", wire.calls)
                        .put(
                            "turns",
                            JSONArray(
                                turns.map {
                                    JSONObject()
                                        .put("id", it.id)
                                        .put("state", it.state)
                                        .put("errorCode", it.errorCode)
                                        .put("answer", liveResourceAnswer(c, session, it.id))
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
                                        .put("resultStatus", saved?.status)
                                        .put("result", saved?.let { c.storage.toolResults.readContent(it) })
                                },
                            ),
                        ).put(
                            "results",
                            JSONArray(
                                messages.filter { it.kind == "TOOL_RESULT" }.map {
                                    c.storage.messages
                                        .readContent(it)
                                        .orEmpty()
                                },
                            ),
                        )
                File(directory, "result.json").writeText(result.toString(2))
            }

            try {
                availability.configure(allowed)
                val plugin = c.pluginService.list().single { it.native?.pluginId == "browser" }
                c.pluginService.selectFromUser(
                    session,
                    plugin.id,
                    true,
                )
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
                    System.currentTimeMillis(),
                )
                configureOwnedModelSession(
                    c.storage,
                    c.chatService,
                    session,
                    AgentMode.ACT,
                )
                wireField.set(
                    factory,
                    wire,
                )
                ActivityScenario.launch(MainActivity::class.java).use {
                    val prompt =
                        "请用浏览器搜索 IANA example domains，读取搜索结果，再打开其中的 IANA 官方网页并读取正文。" +
                            "回答：example.com 和 example.org 用于什么目的，是否可注册或转让？" +
                            "给出你实际打开阅读的官方来源 URL，逐字引用说明不可注册或转让的英文原句。" +
                            "必须先 browser.search，再读取搜索页，打开结果并读取目标页面；" +
                            "不要使用 API 搜索、web_fetch 或已有知识替代本次浏览器核验。" +
                            "只搜索这些公开非敏感词，不登录，不下载，不提交其他表单。"
                    val receipt =
                        c.chatService
                            .sendSubmission(
                                ChatSubmission(
                                    session,
                                    0,
                                    UUID.randomUUID().toString(),
                                    prompt,
                                ),
                            ).await()
                    assertTrue(receipt.outcome is ChatSubmissionOutcome.Accepted)
                    withTimeout(300_000) {
                        while (true) {
                            val turns = c.storage.turns.listBySession(session)
                            val pending =
                                turns.flatMap { c.storage.toolCalls.listByTurn(it.id) }.filter {
                                    it.state == "AWAITING_APPROVAL"
                                }
                            pending.forEach { call ->
                                check(call.name in allowed)
                                val args = JSONObject(call.argsJson)
                                if (call.name == "browser.search") {
                                    check(
                                        args.optString("query").lowercase().let {
                                            "iana" in it &&
                                                "example" in it
                                        },
                                    )
                                }
                                if (call.name in
                                    setOf(
                                        "browser.open",
                                        "browser.navigate",
                                    )
                                ) {
                                    val uri = java.net.URI(args.getString("url"))
                                    check(
                                        uri.scheme == "https" &&
                                            (uri.host == "iana.org" || uri.host.endsWith(".iana.org")),
                                    )
                                }
                                c.chatService.approveApproval(
                                    requireNotNull(c.storage.approvals.byToolCall(call.id)).id,
                                )
                            }
                            evidence()
                            check(
                                turns.none {
                                    it.state in
                                        setOf(
                                            "FAILED",
                                            "CANCELLED",
                                        )
                                },
                            )
                            if (turns.size == 1 &&
                                turns.single().state == "COMPLETED"
                            ) {
                                break
                            }
                            delay(250)
                        }
                    }
                    val calls =
                        c.storage.turns
                            .listBySession(session)
                            .flatMap { c.storage.toolCalls.listByTurn(it.id) }
                    assertTrue(
                        "No successful real browser search",
                        calls.any {
                            it.name == "browser.search" &&
                                it.state == "COMPLETED"
                        },
                    )
                    assertTrue(
                        "No browser page reading",
                        calls.any {
                            it.name == "browser.snapshot" &&
                                it.state == "COMPLETED"
                        },
                    )
                    val answer =
                        liveResourceAnswer(
                            c,
                            session,
                            c.storage.turns
                                .listBySession(session)
                                .single()
                                .id,
                        )
                    assertTrue(
                        "Missing official citation",
                        "iana.org/" in answer,
                    )
                    evidence()
                    assertTrue(
                        "Persisted source text and actual subsequent model consumption are required",
                        verifyBrowserSearchModelEvidence(directory),
                    )
                    completed = true
                }
            } finally {
                finishOwnedCase(
                    c.storage,
                    c.chatService,
                    session,
                    {},
                    { evidence() },
                    { stopped ->
                        if (stopped) {
                            wireField.set(
                                factory,
                                original,
                            )
                        }
                        availability.restore()
                        c.storage.sessions.archive(
                            session,
                            System.currentTimeMillis(),
                        )
                        c.chatService.closeSession()
                    },
                )
            }
        }

    private fun field(
        owner: Any,
        name: String,
    ) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
