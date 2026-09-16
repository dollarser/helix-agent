package com.helix.app.provider

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.core.model.ProviderProtocol
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
    @Test fun claudeAccountUsesItsOwnExplicitActivity() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val intent = SubscriptionProviderModule.accountIntent(app, SubscriptionProviderModule.CLAUDE_ID)
            assertEquals("com.helix.runtime.cli.app.ClaudeLoginActivity", intent.component?.className)
            assertEquals(app.packageName, intent.component?.packageName)
            assertEquals(
                ManagedProviderAccountResult.OPENED,
                app.appContainer.providerService.openManagedAccount(SubscriptionProviderModule.CLAUDE_ID),
            )
        }

    @Test fun managedAccountOpensTheExplicitRuntimeUi() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val expected =
                InstrumentationRegistry
                    .getArguments()
                    .getString("managedAccountExpected")
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

    @Test fun copilotAccountUsesItsOwnExplicitActivity() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            assertEquals(
                "com.helix.runtime.cli.app.CopilotLoginActivity",
                SubscriptionProviderModule
                    .accountIntent(
                        app,
                        SubscriptionProviderModule.COPILOT_ID,
                    ).component
                    ?.className,
            )
            assertEquals(
                ManagedProviderAccountResult.OPENED,
                app.appContainer.providerService.openManagedAccount(SubscriptionProviderModule.COPILOT_ID),
            )
        }

    @Test fun grokAccountUsesItsOwnExplicitActivity() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            assertEquals(
                "com.helix.runtime.cli.app.GrokLoginActivity",
                SubscriptionProviderModule.accountIntent(app, SubscriptionProviderModule.GROK_ID).component?.className,
            )
            assertEquals(
                ManagedProviderAccountResult.OPENED,
                app.appContainer.providerService.openManagedAccount(SubscriptionProviderModule.GROK_ID),
            )
        }

    private fun verifyProvider(providerId: String) =
        runBlocking {
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
                if (providerId == SubscriptionProviderModule.CODEX_ID) {
                    verifyNoAccountCodexFixture(container, providerId, probe)
                    return@runBlocking
                }
                assertTrue(probe is ProbeOutcome.Ok)
                val row =
                    container.providerService.rows.value
                        .single { it.id == providerId }
                assertTrue(row.chatSelectable)
                assertTrue(row.managedExternally)
                val capabilities = requireNotNull(row.capabilities)
                assertFalse(capabilities.toolCalls)
                assertFalse(capabilities.vision)
                assertTrue(runCatching { container.providerService.declareVisionCapability(row.id, true) }.isFailure)
                assertTrue(runCatching { container.providerService.delete(row.id) }.isFailure)

                SubscriptionProviderContractCheck.verify(container, row.id, row.model)
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

    private suspend fun verifyNoAccountCodexFixture(
        container: com.helix.app.AppContainer,
        providerId: String,
        probe: ProbeOutcome,
    ) {
        // Codex now probes its authenticated catalog and tool/vision support. The
        // text-only no-account fixture must not falsely advertise those capabilities.
        assertTrue("No-account catalog probe must fail: $probe", probe is ProbeOutcome.Failed)
        val events =
            container.providerService
                .modelProviderFor(providerId)
                .stream(
                    com.helix.core.model.ModelRequest(
                        "helix-fixture",
                        listOf(
                            com.helix.core.model
                                .ModelMessage(com.helix.core.model.ModelRole.USER, "hello"),
                        ),
                    ),
                ).toList()
        assertTrue(
            events.contains(
                com.helix.core.model.ModelEvent
                    .TextDelta("HELIX_OK"),
            ),
        )
        assertTrue(
            events.contains(
                com.helix.core.model.ModelEvent
                    .Completed("stop"),
            ),
        )
    }
}
