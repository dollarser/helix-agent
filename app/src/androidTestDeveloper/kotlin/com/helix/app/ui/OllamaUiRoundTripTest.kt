package com.helix.app.ui

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.core.model.ModelRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * HXA-028 developer instrumented UI round-trip against the dev-machine Ollama
 * server (provider doc 2.5, emulator host bridge `10.0.2.2`): the WHOLE user
 * path through the production UI —
 *
 *   create provider from the Ollama template (cleartext http shows the
 *   per-host:port risk display; save is blocked until the explicit checkbox)
 *   → connection test passes all four phases → the provider becomes
 *   chat-selectable → new session bound to it → send a message → the stream
 *   completes and the assistant content is persisted.
 *
 * Assumption-guarded like SelfHostedSmokeTest: when no Ollama listens on the
 * bridge the test is SKIPPED with a reason (records absence, never fakes
 * success).
 */
@RunWith(AndroidJUnit4::class)
class OllamaUiRoundTripTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var serverModel: String = ""
    private val runTag = System.currentTimeMillis()
    private val providerName = "Ollama UI $runTag"
    private val sessionTitle = "UI RT $runTag"

    @Before
    fun setUp() {
        val version = fetchText("http://$HOST:$PORT/api/version")
        assumeTrue(
            "no Ollama on the emulator host bridge $HOST:$PORT — UI round-trip skipped " +
                "(start: ollama serve + ollama pull <model> on the dev machine)",
            version != null,
        )
        val models = fetchText("http://$HOST:$PORT/v1/models")
        assumeTrue("Ollama /v1/models returned nothing — UI round-trip skipped", models != null)
        val first = firstModelId(requireNotNull(models))
        assumeTrue("Ollama has no pulled model — UI round-trip skipped (ollama pull <model>)", first != null)
        serverModel = requireNotNull(first)
        Log.d(TAG, "UI round-trip model: $serverModel")

        composeRule.resetDeterministicUiState()
        deleteAllProviders(composeRule.container())
    }

    // The step order (create → test → session → send → terminal → evidence →
    // cleanup) IS the assertion: this is one linear end-to-end round trip.
    @Suppress("LongMethod")
    @Test
    fun ollamaProviderThroughUiPassesTestAndCompletesAChatTurn() {
        val container = composeRule.container()
        val networkOpsBefore = container.providerService.networkOperations.value

        // --- 1. create from the Ollama template; cleartext gate is explicit ---
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("provider-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-template-ollama").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-form-name").performTextClearance()
        composeRule.onNodeWithTag("provider-form-name").performTextInput(providerName)
        composeRule.onNodeWithTag("provider-form-endpoint").performTextClearance()
        composeRule.onNodeWithTag("provider-form-endpoint").performTextInput("http://$HOST:$PORT/v1")
        composeRule.onNodeWithTag("provider-form-model").performTextInput(serverModel)
        // The cleartext http endpoint shows the exact host:port risk display…
        composeRule.onNodeWithText("明文 HTTP：请求将不加密发往 $HOST:$PORT").assertIsDisplayed()
        // …and save is blocked until the explicit per-host:port confirmation.
        composeRule.onNodeWithTag("provider-form-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("provider-cleartext-confirm").performClick()
        composeRule.onNodeWithTag("provider-form-save").assertIsEnabled()
        composeRule.onNodeWithTag("provider-form-save").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-form-dialog").assertIsNotDisplayed()
        composeRule.onNodeWithText(providerName).assertIsDisplayed()
        composeRule.onNodeWithTag("provider-status-untested").assertIsDisplayed()

        // --- 2. the four-phase connection test passes against real Ollama ---
        composeRule.onNodeWithTag("provider-test").performClick()
        composeRule.waitUntil(180_000) {
            composeRule.onAllNodesWithTag("provider-status-passed").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("provider-status-passed").assertIsDisplayed()
        composeRule.onNodeWithText("已通过 · 能力已探测").assertIsDisplayed()

        // --- 3. new session bound to the tested provider ---
        composeRule.navigateTo("sessions")
        composeRule.onNodeWithTag("chat-new-session").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat-new-session-title").performTextInput(sessionTitle)
        composeRule.onNodeWithText("$providerName（$serverModel）").performClick()
        composeRule.onNodeWithTag("chat-new-session-confirm").performClick()
        composeRule.waitForIdle()

        // The conversation header shows the provider badge + the Standard profile.
        composeRule.onNodeWithText("$providerName · $serverModel").assertIsDisplayed()
        composeRule.onNodeWithText("配置：Standard").assertIsDisplayed()

        // --- 4. send; the stream completes and the assistant content persists ---
        composeRule.onNodeWithTag("chat-input").performTextInput("Reply with exactly: ok")
        composeRule.onNodeWithTag("chat-send").performClick()

        // Terminal signal: the send button returns (the turn is no longer in flight).
        composeRule.waitUntil(180_000) {
            composeRule.onAllNodesWithTag("chat-send").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("chat-turn-error").assertIsNotDisplayed()
        composeRule.onNodeWithTag("chat-message-user").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-message-assistant").assertIsDisplayed()

        // --- 5. evidence: persisted rows + at least probe + stream network ops ---
        val session =
            container.chatService.sessions.value
                .first { it.title == sessionTitle }
        val assistantRows =
            container.storage.messages
                .listBySession(session.id)
                .filter { it.role == ModelRole.ASSISTANT.name }
        val nonBlank =
            assistantRows.firstOrNull {
                container.storage.messages
                    .readContent(it)
                    ?.isNotBlank() == true
            }
        assertTrue("the completed turn must have persisted an assistant row", assistantRows.isNotEmpty())
        assertTrue("the assistant content must be non-blank (the model replied)", nonBlank != null)
        assertTrue(
            "at least the probe and the stream entered the wire",
            container.providerService.networkOperations.value >= networkOpsBefore + 2,
        )
        Log.d(
            TAG,
            "UI round-trip evidence: provider=$providerName model=$serverModel " +
                "assistantChars=${nonBlank?.let {
                    container.storage.messages
                        .readContent(it)
                        ?.length
                } ?: 0}",
        )

        // --- cleanup: archive the session, delete the provider ---
        container.chatService.archiveSession(session.id)
        runBlocking {
            container.providerService.delete(
                container.providerService.rows.value
                    .first { it.displayName == providerName }
                    .id,
            )
        }
        assertEquals(
            "cleanup must remove the round-trip provider",
            0,
            container.providerService.rows.value
                .count { it.displayName == providerName },
        )
    }

    /** Plain-HTTP GET (pre-check only); null when unreachable or non-2xx. */
    private fun fetchText(url: String): String? =
        try {
            val connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2_000
                    readTimeout = 5_000
                    requestMethod = "GET"
                }
            val code = connection.responseCode
            if (code !in 200..299) {
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: UnknownHostException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}")
            null
        } catch (e: ConnectException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}")
            null
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}")
            null
        } catch (e: IOException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}")
            null
        }

    /** The first `id` string of an OpenAI-compatible models body (fail closed: null). */
    private fun firstModelId(modelsBody: String): String? {
        val from = modelsBody.indexOf("\"data\"")
        if (from < 0) return null
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(modelsBody.substring(from))?.groupValues?.get(1)
    }

    private companion object {
        const val TAG = "HelixUiRoundTrip"
        const val HOST = "10.0.2.2"
        const val PORT = 11434
    }
}
