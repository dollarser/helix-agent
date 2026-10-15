package com.helix.app.skills

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/** One bounded read-only guide journey against the explicitly selected local model server. */
class HelixDocsLiveModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test
    fun discoversReadsCitesVersionAndBoundsAnUnmatchedQuestion() =
        runBlocking {
            val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            val port = requireNotNull(args.getString("helixDocsPort")).toInt()
            val model = requireNotNull(args.getString("helixDocsModel"))
            require(model == "Qwen3.8-27B")
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        "generic-openai",
                        "Helix Docs acceptance fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model,
                        "{}",
                        false,
                        CleartextWarning("127.0.0.1", port),
                        emptyList(),
                    ),
                    null,
                )
            val profile = c.profileStore.profile
            var session: String? = null
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                session = c.chatService.createSession("Helix docs acceptance", provider, model)
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                c.chatService.openTestSessionControl(session, AgentMode.ACT, TurnBudgets(12, 10, 131072, 4096, 139264))
                c.chatService.sendTestMessage(
                    session,
                    "Use Helix's built-in versioned user guide only. Discover it, read its manifest and " +
                        "index, then read the relevant section. State the documented preview version, " +
                        "distribution(s), exact section ID and heading, full guide revision hash, and " +
                        "whether the channel section says the preview is debug-signed or store-ready. " +
                        "Also answer whether installing over a OnePlus can preserve existing app data. " +
                        "If the guide does not document that, state that it is not documented; do not infer. " +
                        "Do not use the web, phone, other apps, or perform actions.",
                )
                val deadline = android.os.SystemClock.elapsedRealtime() + 300_000L
                var turn: com.helix.core.storage.entity.TurnEntity? = null
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    turn = c.storage.turns.listBySession(session).lastOrNull()
                    if (turn != null && TurnState.valueOf(turn.state).isTerminal) break
                    kotlinx.coroutines.delay(250)
                }
                val completedTurn = requireNotNull(turn) { "model did not create a turn" }
                check(completedTurn.state == "COMPLETED") { "model turn ended ${completedTurn.state}: ${completedTurn.errorCode}" }
                val calls = c.storage.toolCalls.listByTurn(completedTurn.id)
                assertTrue("model must use the catalog-discovered built-in guide; calls=${calls.map { it.name }}", calls.any { it.name == "skills.read" })
                val resourceCalls = calls.filter { it.name == "skills.read_resource" }
                assertTrue("model must read the guide manifest and chapters", resourceCalls.size >= 3)
                val paths = resourceCalls.mapNotNull { call ->
                    runCatching { Json.parseToJsonElement(call.argsJson).jsonObject["path"]?.jsonPrimitive?.content }.getOrNull()
                }
                assertTrue("manifest was not read: $paths", "references/manifest.json" in paths)
                assertTrue("index was not read: $paths", "references/index.md" in paths)
                assertTrue("unexpected or unbounded resource read: $paths", paths.all { it in setOf(
                    "references/manifest.json", "references/index.md",
                    "references/sections/index.md", "references/sections/channels.md",
                    "references/sections/getting-started.md", "sections/channels.md",
                    "sections/getting-started.md",
                ) })
                assertTrue("manifest read did not settle", resourceCalls.any {
                    Json.parseToJsonElement(it.argsJson).jsonObject["path"]?.jsonPrimitive?.content == "references/manifest.json" &&
                        c.storage.toolResults.byToolCall(it.callId)?.status == "SUCCEEDED"
                })
                assertTrue("index read did not settle", resourceCalls.any {
                    Json.parseToJsonElement(it.argsJson).jsonObject["path"]?.jsonPrimitive?.content == "references/index.md" &&
                        c.storage.toolResults.byToolCall(it.callId)?.status == "SUCCEEDED"
                })
                assertTrue("channel section read did not settle", resourceCalls.any {
                    Json.parseToJsonElement(it.argsJson).jsonObject["path"]?.jsonPrimitive?.content == "references/sections/channels.md" &&
                        c.storage.toolResults.byToolCall(it.callId)?.status == "SUCCEEDED"
                })
                val response = c.storage.messages.listBySession(session)
                    .filter { it.role == "ASSISTANT" && it.kind == "TEXT" && it.turnId == completedTurn.id }
                    .mapNotNull { c.storage.messages.readContent(it) }
                    .joinToString("\n")
                val normalized = response.lowercase()
                assertTrue("answer must state version 0.0.5", "0.0.5" in response)
                assertTrue("answer must cite channel section", "channels" in normalized)
                assertTrue("answer must include the full revision hash", Regex("(?i)\\b[a-f0-9]{64}\\b").containsMatchIn(response))
                assertTrue("undocumented install/data policy must be admitted", listOf(
                    "not documented", "not specified", "not stated", "does not document", "cannot determine",
                ).any { it in normalized })
                println("HELIX_DOCS_LIVE_MODEL=PASS model=$model calls=${calls.map { it.name }} paths=$paths")
                println("HELIX_DOCS_LIVE_ANSWER=$response")
            } finally {
                c.chatService.stop()
                c.chatService.closeSession()
                c.profileStore.switchTo(profile)
                session?.let { c.privacyDeletionService.deleteSession(it) }
                c.providerService.delete(provider)
            }
        }
}
