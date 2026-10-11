package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.ChatSubmission
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.InputSkillActivation
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class SkillUserInvocationModelDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    @Suppress("LongMethod") // Explicit bounded loopback profile and synthetic package ownership.
    fun onlyTrustedAcceptedInputMakesUserOnlySkillReadable() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = requireNotNull(args.getString("pluginSkillPort")).toInt()
            val model = requireNotNull(args.getString("pluginSkillModel"))
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Invocation fixture",
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
            val name = "user-flow-${UUID.randomUUID()}"
            val bundle =
                PluginPackageReader().parse(
                    mapOf(
                        ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                        "skills/$name/SKILL.md" to
                            (
                                "---\nname: $name\ndescription: Synthetic read-only guide\n" +
                                    "disable-model-invocation: true\n---\n# User-only workflow\n" +
                                    "Report the user's parameter without external actions."
                            ).toByteArray(),
                    ),
                )
            check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
            val installed = c.pluginService.install(bundle, "fixture:$name")
            val id = c.chatService.createSession("Invocation model fixture", provider, model)
            val key = installed.skills.single()
            try {
                c.pluginService.setSkillEnabled(key, true)
                c.pluginService.selectFromUser(id, installed.id, true)
                assertTrue(c.skillRepository.listForModel(id, "fake").none { it.key == key })
                assertThrows(IllegalArgumentException::class.java) { c.skillRepository.read(key, id, "fake") }
                val activation = c.skillRepository.prepareUserActivation(key, id, "中文参数")
                c.chatService.openTestSessionControl(id, AgentMode.ACT, TurnBudgets(12, 10, 524288, 32768, 557056))
                val prompt =
                    "Read-only check: call skills.read for source=${key.source.name}, name=${key.name}, " +
                        "snapshotHash=${key.snapshotHash}. Report the first heading and my parameter. " +
                        "No external actions."
                val input = InputSkillActivation(key.source.name, key.name, key.snapshotHash, activation.arguments)
                val submission =
                    ChatSubmission(id, 0, UUID.randomUUID().toString(), prompt, skillActivations = listOf(input))
                c.chatService.sendSubmission(submission).await()
                val deadline = android.os.SystemClock.elapsedRealtime() + 180000

                fun terminal(): Boolean =
                    c.storage.turns.listBySession(id).lastOrNull()?.let {
                        TurnState.valueOf(it.state).isTerminal
                    } == true
                while (android.os.SystemClock.elapsedRealtime() < deadline && !terminal()) delay(250)
                val turn =
                    c.storage.turns
                        .listBySession(id)
                        .last()
                assertEquals(turn.errorCode, "COMPLETED", turn.state)
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                assertTrue(calls.any { it.name == "skills.read" && it.state == "COMPLETED" })
                assertTrue(c.skillRepository.listForModel(id, "another-turn").none { it.key == key })
                println("USER_SKILL_MODEL=PASS")
            } finally {
                c.storage.turns
                    .listBySession(id)
                    .lastOrNull()
                    ?.let { c.chatService.stopTurn(it.id) }
                c.chatService.closeSession()
                c.pluginService
                    .list()
                    .filter { it.id == installed.id }
                    .forEach(c.pluginService::remove)
                c.storage.deleteSessionPermanently(id)
                c.providerService.delete(provider)
            }
        }
}
