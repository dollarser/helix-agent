package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import com.helix.app.voice.ConfiguredSpeechProviders
import com.helix.app.voice.LocalSpeechModel
import com.helix.app.voice.OfflineAsrModel
import com.helix.app.voice.ReadAloudService
import com.helix.app.voice.SpeechServiceSettings
import com.helix.app.voice.SpeechSettingsStore
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.wire.HttpFailureDetails
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Production settings UI, isolated Room fixture, no login, network, microphone, or audio output. */
class CloudSpeechSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun label(id: Int) = context.getString(id)

    private fun click(id: Int) = compose.onNodeWithText(label(id)).performScrollTo().performClick()

    @Test fun cloudSelfHostedAndKeySelectionsKeepDistinctSavedRoutes() {
        ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
            addKeyProvider(fixture)
            val settings = SpeechSettingsStore(InMemoryLineStore(), ConfiguredSpeechProviders { fixture.storage })
            settings.save(SpeechServiceSettings(httpEnabled = true, useAccountLogin = true))
            val model =
                LocalSpeechModel(
                    context,
                    File(fixture.directory, "speech-ui"),
                    { error("No network") },
                    listOf(LocalSpeechModel.Part("model.bin", "https://example.test/model", 1, "0".repeat(64))),
                )
            val reader =
                ReadAloudService(
                    model,
                    { error("No synthesis") },
                    { error("No audio focus") },
                    { error("No audio output") },
                )
            val account = account()
            val offline = OfflineAsrModel(model)
            try {
                compose.setContent {
                    MaterialTheme {
                        CloudSpeechSettingsEntry(settings, account, offline, reader)
                    }
                }
                open()
                compose.onAllNodesWithText(label(R.string.cloud_speech_route_http)).assertCountEquals(2)
                click(R.string.cloud_speech_self_hosted_ws)
                compose.onAllNodesWithText(label(R.string.cloud_speech_route_ws)).assertCountEquals(2)
                click(R.string.speech_settings_save)
                assertTrue(settings.read().asrWebSocket && settings.read().ttsWebSocket)
                assertEquals("wss://api.hilex.me/ws/asr", settings.read().asrEndpoint)
                assertNull(settings.read().providerId)
                open()
                compose.onNodeWithText("Speech fixture key").performScrollTo().performClick()
                compose.onAllNodesWithText(label(R.string.cloud_speech_route_http)).assertCountEquals(2)
                click(R.string.speech_settings_save)
                val key = settings.read()
                assertEquals("speech-key", key.providerId)
                assertFalse(key.useAccountLogin || key.webSocket)
                assertEquals("https://example.test:443/v1/audio/transcriptions", key.asrEndpoint)
                assertEquals("gpt-transcribe", key.asrModel)
                open()
                click(R.string.speech_use_account_login)
                click(R.string.speech_settings_save)
                val restored = settings.read()
                assertTrue(restored.useAccountLogin)
                assertNull(restored.providerId)
                assertEquals("https://api.hilex.me/v1/audio/speech", restored.ttsEndpoint)
            } finally {
                compose.runOnUiThread(reader::close)
            }
        }
    }

    @Test fun speechFailureShowsServerDetailAndCopiesRequestId() {
        compose.setContent {
            MaterialTheme {
                InlineVoiceControls(
                    state =
                        com.helix.app.voice.VoiceCapture.State(
                            phase = com.helix.app.voice.VoiceCapture.Phase.FAILED,
                            failure = com.helix.app.voice.VoiceCapture.Failure.CONNECTION,
                            errorDetails =
                                HttpFailureDetails(
                                    429,
                                    "Synthetic quota detail",
                                    "speech-request-fixture",
                                    1200,
                                ),
                        ),
                    permission = true,
                    uploadConsent = true,
                    uploadsAudio = true,
                    destination = "https://example.test/v1/audio/transcriptions",
                    supportsPartial = false,
                    onCancel = {},
                    onConsent = {},
                    onGrant = {},
                    onFinish = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Synthetic quota detail").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.account_retry_after, 2L)).assertIsDisplayed()
        compose.onNodeWithTag("account-copy-request-id").performClick()
        compose.onNodeWithText(label(R.string.account_request_id_copied)).assertIsDisplayed()
        compose.runOnIdle {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(
                "speech-request-fixture",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }
    }

    private fun addKeyProvider(fixture: ProviderModelsIntegrationDeviceTest.Fixture) {
        fixture.storage.providerConfigs.save(
            ProviderConfigSpec(
                id = "speech-key",
                displayName = "Speech fixture key",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://example.test/v1",
                model = "synthetic",
                headersJson = "{}",
                secretAlias = "unused-synthetic-alias",
                authKind = "SECRET",
                provisioningKind = "USER_CONFIGURED",
                capabilitySnapshot =
                    fixture.storage.providerConfigs
                        .resolve("p")
                        .capabilitySnapshot,
            ),
        )
    }

    private fun open() {
        compose.onNodeWithText(label(R.string.speech_services_title)).performClick()
        compose.onNodeWithTag("speech-services-sheet").assertExists()
    }

    private fun account() =
        AccountSession(
            object : AccountTokenStorage {
                override fun load(): AccountTokens? = null

                override fun save(tokens: AccountTokens) = error("No login")

                override fun clear() = Unit
            },
            object : WireClient {
                override suspend fun open(request: WireRequest) = error("No network")
            },
        )
}
