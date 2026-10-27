package com.helix.app.provider

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.mcp.oauth.McpOAuthAttemptCodec
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliModelRequestCodec
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Two invocations around a data-preserving APK replacement; synthetic files only. */
class InternalFormatUpgradeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory
        get() =
            File(context.filesDir, "internal-format-upgrade-fixture").also {
                check(context.packageName.endsWith(".asrvalidation"))
            }

    @Test fun seedBeforeReplacement() {
        // This method runs against the original pre-change target APK, proving valid old shapes.
        assertEquals("helix-fixture", CliModelRequestCodec.decode(OLD_REQUEST.encodeToByteArray()).model)
        assertEquals("synthetic-old-attempt", McpOAuthAttemptCodec.decode(OLD_OAUTH).state)
        check(!directory.exists()) { "Do not overwrite an earlier incomplete fixture" }
        check(directory.mkdir())
        File(directory, "old-request-v1.json").writeText(OLD_REQUEST)
        File(directory, "old-oauth-v2.json").writeText(OLD_OAUTH)
        File(directory, "old-prompt-v3.json").writeText(OLD_PROMPT)
        File(directory, "sentinel.txt").writeText("synthetic-content-must-survive-update")
    }

    @Test fun verifyAfterReplacement() =
        runBlocking {
            assertEquals(OLD_REQUEST, File(directory, "old-request-v1.json").readText())
            assertEquals(OLD_OAUTH, File(directory, "old-oauth-v2.json").readText())
            assertEquals(OLD_PROMPT, File(directory, "old-prompt-v3.json").readText())
            assertEquals("synthetic-content-must-survive-update", File(directory, "sentinel.txt").readText())
            assertThrows(RuntimeException::class.java) { CliModelRequestCodec.decode(OLD_REQUEST.encodeToByteArray()) }
            assertThrows(IllegalArgumentException::class.java) { McpOAuthAttemptCodec.decode(OLD_OAUTH) }
            // A retained audit is evidence, never a live request or a startup migration input.
            assertEquals(OLD_PROMPT, File(directory, "old-prompt-v3.json").readText())
            val request = ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "synthetic")))
            val client = CliModelJobClient(CliRuntimeSupervisor(context))
            CliModelProvider.entries.forEach { provider ->
                val encoded = CliModelRequestCodec.encode(request, provider)
                assertTrue(!encoded.decodeToString().contains("\"version\""))
                assertEquals(provider, CliModelRequestCodec.decodeEnvelope(encoded).provider)
                val id =
                    "job_" +
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(12)
                val result =
                    client.submitAndAwait(
                        id,
                        request,
                        provider = provider,
                        timeoutMs = 10_000,
                        pollIntervalMs = 20,
                    )
                assertTrue(result is CliModelJobClient.AwaitOutcome.Terminal)
                val record = (result as CliModelJobClient.AwaitOutcome.Terminal).record
                assertEquals(CliModelJobState.SUCCEEDED, record.state)
                assertTrue(client.acknowledgeResult(record) is CliModelJobClient.StateOutcome.Ok)
            }
            // Only this test's enumerated synthetic files, never subscription secrets or app data.
            directory.listFiles().orEmpty().forEach { check(it.delete()) }
            check(directory.delete())
        }

    private companion object {
        const val OLD_REQUEST =
            """{"version":1,"model":"helix-fixture","messages":[{"role":"USER","text":"synthetic","toolCalls":[]}],""" +
                """"tools":[],"stopSequences":[],"reasoning":"OFF"}"""
        const val OLD_OAUTH =
            """{"version":2,"state":"synthetic-old-attempt","attemptId":"fixture","serverId":"fixture",""" +
                """"issuer":"https://issuer.example","tokenEndpoint":"https://issuer.example/token",""" +
                """"clientId":"fixture","redirectUri":"helix://oauth/mcp/callback","scope":"read",""" +
                """"createdAtMs":1,"expiresAtMs":2,"resource":"https://issuer.example",""" +
                """"revocationEndpoint":null,"issuerParameterRequired":true}"""
        const val OLD_PROMPT = """{"version":3,"contextKind":"TASK","sections":[]}"""
    }
}
