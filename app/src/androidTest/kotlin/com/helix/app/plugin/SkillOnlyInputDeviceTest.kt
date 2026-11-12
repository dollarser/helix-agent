package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.SessionInputProtocolServer
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.model.input.InputSkillActivation
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real admission, durable activation and outbound model payload, against an emulator-local fixture only. */
class SkillOnlyInputDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    @Suppress("LongMethod")
    fun skillOnlyInputPreservesEmptyDraftAndDeliversExactBody() =
        runBlocking {
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            SessionInputProtocolServer(ProviderProtocol.OPENAI_CHAT_COMPLETIONS).use { wire ->
                wire.start()
                val provider =
                    c.providerService.create(
                        ProviderDraft(
                            null,
                            "Skill-only fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${wire.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                        ),
                        null,
                    )
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                check(c.providerService.runCapabilityTest(provider) is ProbeOutcome.Ok)
                val name = "chip-${UUID.randomUUID()}"
                val marker = "Atomic skill body ${UUID.randomUUID()}"
                val bundle =
                    PluginPackageReader().parse(
                        mapOf(
                            ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                            "skills/$name/SKILL.md" to
                                (
                                    "---\nname: $name\ndescription: Synthetic guide\n" +
                                        "disable-model-invocation: true\n---\n$marker"
                                ).toByteArray(),
                        ),
                    )
                val installed = c.pluginService.install(bundle, "fixture:$name")
                val session = c.chatService.createSession("Skill-only fixture", provider, "fixture-model-a")
                try {
                    c.pluginService.selectFromUser(session, installed.id, true)
                    val key = installed.skills.single()
                    val prepared = c.skillRepository.prepareUserActivation(key, session, "")
                    c.chatService.openTestSessionControl(
                        session,
                        AgentMode.ACT,
                        TurnBudgets(maxOutputTokens = 4096),
                    )
                    com.helix.app.test
                        .discoverFixtureTool(c.toolPipeline, session, "time.now")
                    wire.exercise = true
                    wire.release.countDown()
                    val empty = ChatSubmission(session, 0, UUID.randomUUID().toString(), "")
                    val emptyReceipt = c.chatService.sendSubmission(empty).await()
                    assertTrue(emptyReceipt.outcome is ChatSubmissionOutcome.Rejected)
                    assertEquals(0, wire.requestCount)
                    val activation =
                        InputSkillActivation(
                            key.source.name,
                            key.name,
                            key.snapshotHash,
                            prepared.arguments,
                            key.ownerId,
                        )
                    val request =
                        empty.copy(
                            clientRequestId = UUID.randomUUID().toString(),
                            skillActivations = listOf(activation),
                        )
                    val receipt = c.chatService.sendSubmission(request).await()
                    assertEquals(request, receipt.submission)
                    val accepted = receipt.outcome is ChatSubmissionOutcome.Accepted
                    val enqueued = receipt.outcome is ChatSubmissionOutcome.Enqueued
                    assertTrue("Skill-only admission: ${receipt.outcome}", accepted || enqueued)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        val latest =
                            c.storage.turns
                                .listBySession(session)
                                .lastOrNull()
                        if (latest != null && TurnState.valueOf(latest.state).isTerminal) break
                        delay(100)
                    }
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .last()
                    assertEquals(turn.errorCode, TurnState.COMPLETED.name, turn.state)
                    val stored = requireNotNull(c.storage.sessionInputs.get(request.clientRequestId))
                    assertEquals("", c.storage.sessionInputs.readText(stored))
                    assertEquals(listOf(activation), stored.skillActivations)
                    assertTrue(stored.requestModelCallId != null)
                    wire.assertHealthy()
                    assertTrue(wire.requestCount > 0)
                    val body = wire.request(0)
                    assertTrue(body.contains(marker))
                    assertTrue(body.contains(key.name))
                    assertTrue(body.contains(key.snapshotHash))
                    assertTrue(body.contains(key.ownerId))
                    assertTrue(body.contains("Use skill: $name"))
                    assertTrue(c.skillRepository.listForModel(session, "another-turn").none { it.key == key })
                } finally {
                    c.storage.turns
                        .listBySession(session)
                        .forEach { c.chatService.stopTurn(it.id) }
                    c.chatService.closeSession()
                    c.pluginService.remove(installed)
                    c.storage.deleteSessionPermanently(session)
                    c.providerService.delete(provider)
                }
            }
        }
}
