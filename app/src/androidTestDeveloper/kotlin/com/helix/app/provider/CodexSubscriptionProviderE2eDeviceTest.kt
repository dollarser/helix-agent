package com.helix.app.provider

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexSubscriptionProviderE2eDeviceTest {
    @Test fun claudeAccountUsesItsOwnExplicitActivity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val intent = SubscriptionProviderModule.accountIntent(SubscriptionProviderModule.CLAUDE_ID)
        assertEquals("com.helix.runtime.cli.app.ClaudeLoginActivity", intent.component?.className)
        assertEquals("com.helix.runtime.cli", intent.component?.packageName)
        assertEquals(ManagedProviderAccountResult.OPENED, app.appContainer.providerService.openManagedAccount(SubscriptionProviderModule.CLAUDE_ID))
    }
    @Test fun managedAccountOpensTheExplicitRuntimeUi() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val expected =
            InstrumentationRegistry.getArguments().getString("managedAccountExpected")
                ?.let(ManagedProviderAccountResult::valueOf)
                ?: ManagedProviderAccountResult.OPENED
        assertEquals(
            expected,
            app.appContainer.providerService.openManagedAccount(SubscriptionProviderModule.CODEX_ID),
        )
    }

    @Test fun developerProviderUsesTheNormalModelContract() = verifyProvider(SubscriptionProviderModule.CODEX_ID)

    @Test fun claudeProviderUsesTheNormalModelContract() = verifyProvider(SubscriptionProviderModule.CLAUDE_ID)
    @Test fun grokProviderUsesTheNormalModelContract() = verifyProvider(SubscriptionProviderModule.GROK_ID)
    @Test fun copilotProviderUsesTheNormalModelContract() = verifyProvider(SubscriptionProviderModule.COPILOT_ID)

    @Test fun copilotAccountUsesItsOwnExplicitActivity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        assertEquals("com.helix.runtime.cli.app.CopilotLoginActivity", SubscriptionProviderModule.accountIntent(SubscriptionProviderModule.COPILOT_ID).component?.className)
        assertEquals(ManagedProviderAccountResult.OPENED, app.appContainer.providerService.openManagedAccount(SubscriptionProviderModule.COPILOT_ID))
    }

    @Test fun grokAccountUsesItsOwnExplicitActivity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        assertEquals("com.helix.runtime.cli.app.GrokLoginActivity", SubscriptionProviderModule.accountIntent(SubscriptionProviderModule.GROK_ID).component?.className)
        assertEquals(ManagedProviderAccountResult.OPENED, app.appContainer.providerService.openManagedAccount(SubscriptionProviderModule.GROK_ID))
    }

    private fun verifyProvider(providerId: String) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val repository = container.storage.providerConfigs
        val original = repository.resolve(providerId)
        try {
            repository.overwrite(
                ProviderConfigSpec(
                    id = original.id,
                    displayName = original.displayName,
                    protocol = ProviderProtocol.parse(original.protocol),
                    endpoint = original.endpoint,
                    model = "helix-fixture",
                    headersJson = original.headersJson,
                    secretAlias = original.secretAlias,
                    capabilitySnapshot = original.capabilitySnapshot,
                ),
            )
            val probe = container.providerService.runConnectionTest(providerId)
            assertTrue(probe is ProbeOutcome.Ok)
            val row = container.providerService.rows.value.single { it.id == providerId }
            assertTrue(row.chatSelectable)
            assertTrue(row.managedExternally)
            val capabilities = requireNotNull(row.capabilities)
            assertFalse(capabilities.toolCalls)
            assertFalse(capabilities.vision)
            assertTrue(runCatching { container.providerService.declareVisionCapability(row.id, true) }.isFailure)
            assertTrue(runCatching { container.providerService.delete(row.id) }.isFailure)

            val events = container.providerService.modelProviderFor(row.id).stream(
                ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "hello"))),
            ).toList()
            assertEquals(
                listOf<ModelEvent>(ModelEvent.TextDelta("HELIX_OK"), ModelEvent.Usage(2, 1), ModelEvent.Completed("stop")),
                events,
            )

            val sessionId = container.chatService.createSession("subscription", row.id, row.model)
            container.chatService.openSession(sessionId)
            await("session opens") { container.chatService.screen.value.openSessionId == sessionId }
            container.chatService.send("hello from chat")
            await("chat turn completes") {
                container.chatService.screen.value.activeTurn?.state == TurnState.COMPLETED
            }
            assertEquals("HELIX_OK", container.chatService.screen.value.messages.last().content)
            val turn = container.storage.turns.listBySession(sessionId).single()
            assertEquals(TurnState.COMPLETED.name, turn.state)
            assertEquals(1, container.storage.modelCalls.listByTurn(turn.id).size)
        } finally {
            repository.overwrite(
                ProviderConfigSpec(
                    original.id,
                    original.displayName,
                    ProviderProtocol.parse(original.protocol),
                    original.endpoint,
                    original.model,
                    original.headersJson,
                    original.secretAlias,
                    original.capabilitySnapshot,
                ),
            )
            ProviderTestStatusStore(PrefsLineStore(app, "helix-ui")).clear(providerId)
            container.providerService.refresh()
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("timed out: $label")
    }
}
