package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.app.provider.ProviderDraft
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real model, original Dispatcher, ordinary tool discovery and independent native-record oracle. */
class MobileTaskContractModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val fixture = MobileInteractionFixture()
    private val target = InstrumentationRegistry.getInstrumentation().context.packageName
    private val uri get() = Uri.parse("content://$target.record-identity")

    @Test
    @Suppress("LongMethod") // Preserve setup, all independent outcomes and cleanup in one live acceptance.
    fun realTasksPreserveIdentityScopeUnitsAndAnswerFormat(): Unit =
        runBlocking {
            require(InstrumentationRegistry.getArguments().getString("helixTaskContractModel") == "true")
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
            val oldScope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
            val oldRoute = lines.lines(MobileUseGrantStore.EXECUTION_KEY)
            val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
            val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
            val evidence = File(app.filesDir, "mobile-task-contract").apply { mkdirs() }
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Synthetic task contract",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://10.0.2.2:30008/v1"),
                        "Qwen3.8-27B",
                        "{}",
                        false,
                    ),
                    null,
                )
            val outcomes = mutableListOf<Boolean>()
            try {
                val probe = c.providerService.runConnectionTest(provider)
                File(evidence, "provider-probe.txt").writeText(probe.toString())
                check(probe is com.helix.provider.api.ProbeOutcome.Ok) { "Configured model probe failed: $probe" }
                store.configureGlobal(setOf(target), wholePhone = false)
                store.configureExecution(background = false, allowMoveFromMain = false)
                c.pluginService.setEnabled(plugin.id, true)
                val prompts =
                    listOf(
                        "In the open Documents app, create a new document with the exact filename Report27, " +
                            "without an extension, with body alpha and quantity 2 servings, then save it.",
                        "In the open Documents app, create a new document named Report27.md " +
                            "with body alpha and quantity 2 servings, then save it.",
                        "What quantity is recorded for the document with body beta in the open Documents app? " +
                            "Express your answer in the format <amount> <unit>, exactly as recorded.",
                        "Explain what information is shown for the existing document in the open Documents app.",
                    )
                for ((index, prompt) in prompts.withIndex()) {
                    launch(index + 1)
                    val session = c.chatService.createSession("Synthetic contract $index", provider, "Qwen3.8-27B")
                    c.pluginService.catalog.select(session, plugin.id, true)
                    c.sessionPermissionEdit.saveSessionConfig(
                        session,
                        SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                        System.currentTimeMillis(),
                    )
                    var turn: String? = null
                    try {
                        turn =
                            c.agentRuntime
                                .submit(
                                    SubmitTurnCommand(
                                        SessionId(session),
                                        ProviderId(provider),
                                        AgentMode.ACT,
                                        prompt,
                                        budgets = TurnBudgets(maxOutputTokens = 4096),
                                        clientRequestId = UUID.randomUUID().toString(),
                                        directUserRequest = true,
                                    ),
                                ).value
                        withTimeout(360_000) {
                            while (c.storage.turns
                                    .resolve(turn)
                                    .state !in terminal
                            ) {
                                delay(200)
                            }
                        }
                        val state =
                            c.storage.turns
                                .resolve(turn)
                                .state
                        val answer =
                            c.storage.messages
                                .listBySession(session)
                                .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                                .mapNotNull(c.storage.messages::readContent)
                                .lastOrNull()
                                .orEmpty()
                        val records = records()
                        val pass = state == "COMPLETED" && verifies(index, records, answer)
                        outcomes += pass
                        val calls = JSONArray()
                        c.storage.toolCalls.listByTurn(turn).forEach { call ->
                            calls.put(
                                JSONObject()
                                    .put("id", call.id)
                                    .put("name", call.name)
                                    .put("args", call.argsJson)
                                    .put(
                                        "state",
                                        call.state,
                                    ).put(
                                        "result",
                                        c.storage.toolResults
                                            .byToolCall(call.id)
                                            ?.let(c.storage.toolResults::readContent),
                                    ),
                            )
                        }
                        File(evidence, "case-$index.json").writeText(
                            JSONObject()
                                .put("prompt", prompt)
                                .put("sessionId", session)
                                .put("turnId", turn)
                                .put("state", state)
                                .put("answer", answer)
                                .put("records", records)
                                .put(
                                    "passed",
                                    pass,
                                ).put("calls", calls)
                                .put("requests", requestRecords(turn))
                                .put(
                                    "modelCalls",
                                    c.storage.modelCalls
                                        .listByTurn(turn)
                                        .size,
                                ).toString(2),
                        )
                    } finally {
                        turn?.let(c.chatService::stopTask)
                        c.storage.sessions.archive(session, System.currentTimeMillis())
                    }
                }
            } finally {
                AutomationPermissionCenter(app).stopSession()
                lines.setLines(MobileUseGrantStore.CONFIG_KEY, oldScope)
                lines.setLines(MobileUseGrantStore.EXECUTION_KEY, oldRoute)
                c.pluginService.setEnabled(plugin.id, plugin.enabled)
            }
            assertTrue(
                "Read all raw outcomes; completed Turn is not task success: $outcomes",
                outcomes.size == 4 && outcomes.all { it },
            )
        }

    private fun launch(id: Int) {
        app.startActivity(
            Intent()
                .setComponent(ComponentName(target, RecordIdentityFixtureActivity::class.java.name))
                .putExtra("launchId", id)
                .putExtra("reset", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        fixture.await("fresh records activity") {
            app.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() && it.getInt(1) == id } ==
                true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun records(): JSONArray =
        requireNotNull(app.contentResolver.query(uri, null, null, null, null)).use {
            check(it.moveToFirst())
            JSONArray(it.getString(0))
        }

    private fun verifies(
        index: Int,
        records: JSONArray,
        answer: String,
    ): Boolean {
        val rows = (0 until records.length()).map(records::getJSONObject)
        val old = rows.singleOrNull { it.getString("id") == "old" }
        val intact =
            matches(
                old,
                mapOf(
                    "body" to "beta",
                    "quantity" to "1 serving",
                    "name" to "Report27",
                    "extension" to ".md",
                ),
            )
        return intact &&
            when (index) {
                0, 1 -> verifiesCreated(rows, index)
                2 -> rows.size == 1 && answer == "1 serving"
                else -> rows.size == 1 && answer.contains("beta") && answer.length > 30
            }
    }

    private fun verifiesCreated(
        rows: List<JSONObject>,
        index: Int,
    ): Boolean {
        val created = rows.singleOrNull { it.getString("id") != "old" } ?: return false
        val name = created.getString("name") + created.getString("extension")
        return rows.size == 2 && name == (if (index == 0) "Report27" else "Report27.md") &&
            matches(created, mapOf("body" to "alpha", "quantity" to "2 servings"))
    }

    private fun matches(
        row: JSONObject?,
        expected: Map<String, String>,
    ): Boolean = row != null && expected.all { (key, value) -> row.getString(key) == value }

    private fun requestRecords(turn: String): JSONArray =
        JSONArray().also { result ->
            c.storage.modelCalls.listByTurn(turn).forEach { call ->
                result.put(
                    JSONObject()
                        .put("id", call.id)
                        .put("state", call.state)
                        .put("promptSections", call.promptSections)
                        .put("requestManifest", call.requestManifest)
                        .put("usage", call.usage),
                )
            }
        }

    private companion object {
        val terminal = setOf("COMPLETED", "FAILED", "CANCELLED", "NEEDS_REVIEW", "INTERRUPTED")
    }
}
