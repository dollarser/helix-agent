package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.provider.api.ProviderConfig
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSubscriptionProviderTest {
    @Test fun successEventsPassThroughUnchanged() =
        runBlocking {
            val expected = listOf<ModelEvent>(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop"))
            val provider = provider { terminal(CliModelJobState.SUCCEEDED, expected) }
            assertEquals(expected, provider.stream(request()).toList())
        }

    @Test fun failedAndTimedOutJobsBecomeTotalErrorStreams() =
        runBlocking {
            val failed = provider { terminal(CliModelJobState.FAILED) }.stream(request()).toList()
            assertEquals(listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false)), failed)
            val timedOut = provider { CliModelJobClient.AwaitOutcome.TimedOut }.stream(request()).toList()
            assertEquals(listOf(ModelEvent.Error(ModelErrorCode.TRANSPORT, true)), timedOut)
        }

    @Test fun collectionCancellationReachesTheJobExecutor() =
        runBlocking {
            var cancelled = false
            val provider =
                provider {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            val collection = launch { provider.stream(request()).toList() }
            yield()
            collection.cancel()
            collection.join()
            assertTrue(cancelled)
        }

    private fun provider(execute: suspend (ModelRequest) -> CliModelJobClient.AwaitOutcome) =
        CodexSubscriptionProvider(config(), SubscriptionJobExecutor(execute))

    private fun config() =
        ProviderConfig(
            SubscriptionProviderModule.CODEX_ID,
            "Codex Subscription (experimental)",
            ProviderProtocol.OPENAI_RESPONSES,
            NormalizedEndpoint.parse("https://chatgpt.com/backend-api/codex"),
            "model",
            emptyMap(),
            SecretAlias(ProviderFactory.NO_KEY_ALIAS),
            "{}",
        )

    private fun request() = ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "hello")))

    private fun terminal(
        state: CliModelJobState,
        events: List<ModelEvent>? = null,
    ): CliModelJobClient.AwaitOutcome.Terminal =
        CliModelJobClient.AwaitOutcome.Terminal(
            CliModelJobRecord(
                "job_123456789abc",
                "a".repeat(64),
                state,
                1,
                terminalAtEpochMillis = 2,
                model = if (state == CliModelJobState.SUCCEEDED) "model" else null,
                outputSha256 = if (state == CliModelJobState.SUCCEEDED) "b".repeat(64) else null,
            ),
            events,
        )
}
