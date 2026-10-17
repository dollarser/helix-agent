package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Explicit loopback model profile; reads synthetic/native guidance only, no phone actions. */
class PluginSkillModelDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    @Suppress("LongMethod") // One explicit profile, bounded real task, assertions and owned cleanup.
    fun modelReadsTheSelectedNativeGuideThroughNormalSkillTools() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = requireNotNull(args.getString("pluginSkillPort")).toInt()
            val model = requireNotNull(args.getString("pluginSkillModel"))
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Plugin skill fixture",
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
            var session: String? = null
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                val id = c.chatService.createSession("Native guide model fixture", provider, model)
                session = id
                c.pluginService.selectFromUser(id, "bundled-plugin:chat-reply", true)
                c.chatService.openTestSessionControl(id, AgentMode.ACT, TurnBudgets(maxOutputTokens = 32768))
                c.chatService.sendTestMessage(
                    id,
                    "This is a read-only plugin documentation check. Call skills.list, find chat-reply, " +
                        "then call skills.read with its exact source/name/snapshotHash. " +
                        "Report its first Markdown heading. " +
                        "Do not call any phone tool, send messages, or modify files.",
                )
                val deadline = android.os.SystemClock.elapsedRealtime() + 180000
                while (android.os.SystemClock.elapsedRealtime() < deadline &&
                    c.storage.turns
                        .listBySession(id)
                        .lastOrNull()
                        ?.let { TurnState.valueOf(it.state).isTerminal } != true
                ) {
                    delay(250)
                }
                val turn =
                    c.storage.turns
                        .listBySession(id)
                        .last()
                assertEquals(turn.errorCode, "COMPLETED", turn.state)
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                assertTrue(calls.any { it.name == "skills.list" })
                assertTrue(calls.any { it.name == "skills.read" && it.state == "COMPLETED" })
                assertTrue(calls.none { it.name.startsWith("ui.") || it.name == "chat.reply" })
                println("PLUGIN_SKILL_MODEL=PASS calls=${calls.size}")
            } finally {
                session?.let {
                    c.storage.turns
                        .listBySession(it)
                        .lastOrNull()
                        ?.let { turn -> c.chatService.stopTurn(turn.id) }
                    c.chatService.closeSession()
                    c.storage.deleteSessionPermanently(it)
                }
                c.providerService.delete(provider)
            }
        }
}
