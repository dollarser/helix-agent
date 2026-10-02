package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ProviderContextDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // Actual settings UI, persisted per-model boundaries and cleanup.
    fun settingsPersistAndModelChoicesRemainIndependent() =
        runBlocking {
            compose.resetDeterministicUiState()
            val service = compose.container().providerService
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val id =
                    service.create(
                        ProviderDraft(
                            null,
                            "Context settings fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                            CleartextWarning("127.0.0.1", server.port),
                            emptyList(),
                        ),
                        null,
                    )
                try {
                    check(service.runConnectionTest(id) is ProbeOutcome.Ok)
                    val transportIdentity = service.storedConfig(id).transport.cacheKey
                    compose.navigateTo("models")
                    compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
                    compose.onNodeWithTag("provider-context-$id").performScrollTo().performClick()
                    compose.waitUntil(10_000) {
                        compose
                            .onNodeWithTag("provider-context-save")
                            .fetchSemanticsNode()
                            .config
                            .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                            .not()
                    }
                    compose.onNodeWithTag("provider-auto-window").performScrollTo().performClick()
                    compose.onNodeWithTag("provider-context-window").performScrollTo().performTextReplacement("64000")
                    compose.onNodeWithTag("provider-context-threshold").performScrollTo().performTextReplacement("99")
                    compose.onNodeWithTag("provider-context-save").assertIsNotEnabled()
                    compose.onNodeWithTag("provider-context-threshold").performScrollTo().performTextReplacement("70")
                    androidx.test.espresso.Espresso
                        .closeSoftKeyboard()
                    compose.onNodeWithTag("provider-auto-compact").performScrollTo().performClick()
                    compose.onNodeWithTag("provider-context-save").performClick()
                    compose.waitUntil(10_000) {
                        service.contextSettingsStore
                            .read(id, transportIdentity, "fixture-model-a")
                            .manualWindow == 64000L
                    }
                    val settings = service.contextSettings(id, "fixture-model-a")
                    assertEquals(64000L, settings.window)
                    assertEquals(70, settings.triggerPercent)
                    assertFalse(settings.autoCompact)
                    compose.onNodeWithTag("provider-context-$id").performScrollTo().performClick()
                    compose.onNodeWithTag("provider-context-model").performScrollTo().performClick()
                    compose.onNodeWithTag("provider-context-choice-fixture-model-b").performClick()
                    compose.onNodeWithTag("provider-context-window").performScrollTo().assertIsDisplayed()
                    assertEquals(200000L, service.contextSettings(id, "fixture-model-b").window)
                } finally {
                    if (compose.onAllNodesWithTag("provider-context-close").fetchSemanticsNodes().isNotEmpty()) {
                        compose.onNodeWithTag("provider-context-close").performClick()
                    }
                    service.delete(id)
                }
            }
        }
}
