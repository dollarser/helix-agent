package com.helix.runtime.cli.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, bounded diagnostic; never exports raw bodies, headers, tokens or account metadata. */
class CopilotRealAccountDeviceTest {
    @Test fun accountModelCatalog() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realCopilotCatalog") == "true")
        val vault = CliSubscriptionCredentialVault(ApplicationProvider.getApplicationContext<android.content.Context>())
        val request =
            okhttp3.Request
                .Builder()
                .url("https://api.githubcopilot.com/models")
                .header("Authorization", "Bearer ${vault.load(CliSubscriptionProvider.COPILOT).accessToken}")
                .header("Editor-Version", "vscode/1.107.0")
                .header("Editor-Plugin-Version", "copilot-chat/0.35.0")
                .header("Copilot-Integration-Id", "vscode-chat")
                .build()
        OkHttpClient
            .Builder()
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                assertTrue("catalog HTTP ${response.code}", response.isSuccessful)
                val text =
                    response.body
                        .source()
                        .readBoundedByteArray(512 * 1024L)
                        .toString(Charsets.UTF_8)
                val data = org.json.JSONObject(text).getJSONArray("data")
                val rows =
                    (0 until minOf(data.length(), 100)).map { data.getJSONObject(it) }.map { row ->
                        val candidateId = row.optString("id")
                        val id = candidateId.takeIf { it.matches(Regex("[A-Za-z0-9._/-]{1,100}")) } ?: "redacted"
                        val endpoints = row.optJSONArray("supported_endpoints")
                        val paths =
                            if (endpoints == null) {
                                emptyList()
                            } else {
                                (0 until endpoints.length()).map { endpoints.optString(it) }
                            }
                        val disabled = row.optJSONObject("policy")?.optString("state") == "disabled"
                        "$id picker=${row.optBoolean("model_picker_enabled")} " +
                            "disabled=$disabled " +
                            "pathsPresent=${endpoints != null} " +
                            "chat=${"/chat/completions" in paths} " +
                            "responses=${"/responses" in paths}"
                    }
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    android.os.Bundle().apply {
                        putString("stream", rows.joinToString("\n"))
                    },
                )
            }
    }

    @Test fun boundedTextRequest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realCopilot") == "true")
        val vault = CliSubscriptionCredentialVault(ApplicationProvider.getApplicationContext<android.content.Context>())
        assertTrue("Copilot login required", vault.contains(CliSubscriptionProvider.COPILOT))
        var diagnostic = "no HTTP response"
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val body = if (!response.isSuccessful) response.peekBody(8192).string() else ""
                    diagnostic = "HTTP ${response.code}; max_tokens=${body.contains("max_tokens")}; " +
                        "model=${body.contains("model", true)}; unsupported=${body.contains("unsupported", true)}; " +
                        "invalid=${body.contains("invalid", true)}"
                    response
                }.build()
        OkHttpCopilotDeviceTransport().use { transport ->
            val oauth = CopilotLoginController(vault, transport)
            CopilotSubscriptionModel(vault, oauth::refresh, client).use { model ->
                val result =
                    model.run(
                        ModelRequest(
                            "claude-haiku-4.5",
                            listOf(ModelMessage(ModelRole.USER, "Reply exactly HELIX_OK.")),
                            maxOutputTokens = 8,
                        ),
                    )
                assertTrue(diagnostic, result.events.last() is ModelEvent.Completed)
            }
        }
    }
}
