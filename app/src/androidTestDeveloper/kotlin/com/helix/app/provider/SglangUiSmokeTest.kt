package com.helix.app.provider

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.ui.container
import com.helix.app.ui.deleteAllProviders
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import org.junit.After
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
 * HXA-059 real-endpoint smoke (developer instrumented test, companion of
 * [SelfHostedSmokeTest]): the provider model auto-discovery full UI chain against the
 * dev-machine SGLang server (HXA-056 environment: `10.0.2.2:30008/v1`, empty key).
 *
 * Assumption-guarded like [SelfHostedSmokeTest]: when no sglang service listens on the
 * bridge the test is SKIPPED with a reason instead of failing — the smoke records
 * absence, it does not fake success.
 *
 * The flow: create the provider (the server's REAL model id — the five-phase probe
 * streams with the stored model, so a placeholder would 404 on the real server) →
 * connection test PASSES → the row surfaces 「后端可用模型 (N)」 with the real id as a
 * chip → selecting the chip opens the edit form, which is SAVED (never auto-saved) →
 * the persisted row model is the selected id. Cleanup deletes the provider.
 *
 * The cross-value prefill proof (selected id ≠ stored id) is the fixture's job
 * (ProviderModelDiscoveryUiTest — the merged form-field semantics carry only the label,
 * so the persisted value is the only authoritative read of the prefill).
 */
@RunWith(AndroidJUnit4::class)
class SglangUiSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var smokeModel: String = ""
    private var smokeCount: Int = 0

    @Before
    fun setUp() {
        composeRule.resetDeterministicUiState()
        deleteAllProviders(composeRule.container())
    }

    @After
    fun tearDown() {
        // Leave no real-endpoint provider behind (its cleartext binding is pruned with it).
        run {
            val rows =
                composeRule
                    .container()
                    .providerService.rows.value
            if (rows.any { it.displayName == NAME }) {
                kotlinx.coroutines.runBlocking {
                    composeRule.container().providerService.delete(
                        rows.first { it.displayName == NAME }.id,
                    )
                }
            }
        }
    }

    @Test
    fun modelDiscoveryAgainstTheRealSglangEndpoint() {
        // --- pre-check (guard): the server's model list, straight over the bridge ---
        val body = fetchText("http://$host:$PORT/v1/models")
        assumeTrue(
            "no sglang service on $host:$PORT — smoke skipped (start sglang on the dev machine)",
            body != null,
        )
        val models = modelIds(requireNotNull(body))
        assumeTrue("sglang /v1/models returned no parseable model id — smoke skipped", models.isNotEmpty())
        smokeModel = requireNotNull(models.firstOrNull())
        smokeCount = models.size
        Log.d(
            TAG,
            "sglang UI smoke pre-check: host=$host:$PORT models=$models " +
                "(endpoint $endpoint, empty key — HXA-056 environment)",
        )

        // --- create the provider against the real endpoint (empty key, cleartext confirmed) ---
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("provider-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-template-ollama").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-form-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-form-name").performTextClearance()
        composeRule.onNodeWithTag("provider-form-name").performTextInput(NAME)
        composeRule.onNodeWithTag("provider-form-endpoint").performTextClearance()
        composeRule.onNodeWithTag("provider-form-endpoint").performTextInput(endpoint)
        composeRule.onNodeWithTag("provider-form-model").performTextClearance()
        composeRule.onNodeWithTag("provider-form-model").performTextInput(smokeModel)
        composeRule.onNodeWithTag("provider-cleartext-confirm").performClick()
        composeRule.onNodeWithTag("provider-form-save").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(NAME).assertIsDisplayed()

        // --- the five-phase connection test PASSES against the real server (generous
        // budget: real 27B text/tool/vision generations, not a loopback fixture) ---
        composeRule.onNodeWithTag("provider-test").performClick()
        composeRule.waitUntil(PROBE_BUDGET_MILLIS) {
            composeRule.onAllNodesWithTag("provider-status-passed").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("provider-status-passed").assertIsDisplayed()
        Log.d(TAG, "sglang UI smoke: five-phase connection test PASSED against $endpoint")

        // --- the row surfaces the REAL backend list ---
        composeRule.onNodeWithTag("provider-models-section").assertIsDisplayed()
        composeRule.onNodeWithText("后端可用模型 ($smokeCount)").assertIsDisplayed()
        composeRule.onNodeWithText(smokeModel).assertExists()
        Log.d(TAG, "sglang UI smoke: 后端可用模型 ($smokeCount) surfaces $smokeModel")

        // --- chip → edit form → SAVE (never auto-saved) → the persisted row model ---
        composeRule.onNodeWithTag("provider-model-chip-0").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("provider-cleartext-confirm").performClick()
        composeRule.onNodeWithTag("provider-form-save").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("模型：$smokeModel", substring = true).assertIsDisplayed()
        Log.d(TAG, "sglang UI smoke: chip prefill saved — row persists 模型：$smokeModel")
    }

    // --- helpers (mirror SelfHostedSmokeTest's guard/fetch pattern) -------------------------

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
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}: ${e.message}")
            null
        } catch (e: ConnectException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}: ${e.message}")
            null
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}: ${e.message}")
            null
        } catch (e: IOException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}: ${e.message}")
            null
        }

    /** Every `id` inside the OpenAI-compatible `data` array (fail closed: empty). */
    private fun modelIds(modelsBody: String): List<String> {
        val from = modelsBody.indexOf("\"data\"")
        if (from < 0) return emptyList()
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(modelsBody.substring(from))
            .map { it.groupValues[1] }
            .toList()
    }

    /** Emulator bridge by default; overridable for a LAN-reachable dev machine. */
    private val host: String =
        InstrumentationRegistry
            .getArguments()
            .getString(HOST_ARGUMENT)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_HOST

    private val endpoint: String
        get() = "http://$host:$PORT/v1"

    private companion object {
        const val TAG = "HelixSmoke"
        const val HOST_ARGUMENT = "helix.smoke.host"
        const val DEFAULT_HOST = "10.0.2.2"
        const val PORT = 30008
        const val NAME = "SGLang 真实 smoke"
        const val PROBE_BUDGET_MILLIS = 240_000L
    }
}
