package com.helix.app.provider

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SecretAlias
import com.helix.core.model.ToolCallId
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderConfig
import com.helix.runtime.cli.client.CliModelCatalog
import com.helix.runtime.cli.client.CliModelInfo
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCapabilityProbeTest {
    @Test fun allTicksRequireRequestsIncludingFutureEffortsAndToolBackfill() =
        runBlocking {
            val executor = FixtureExecutor()
            val result = CodexCapabilityProbe(provider(executor)).run() as ProbeOutcome.Ok
            assertTrue(result.capabilities.streaming && result.capabilities.toolCalls)
            assertTrue(result.capabilities.vision && result.capabilities.reasoning)
            assertFalse(result.capabilities.jsonSchemaOutput)
            assertEquals(
                listOf("low", "adaptive_next"),
                executor.requests
                    .filter {
                        it.reasoning != ReasoningEffort.OFF
                    }.map { it.reasoning.name.lowercase(java.util.Locale.ROOT) },
            )
            assertTrue(executor.requests.any { it.messages.last().role == ModelRole.TOOL })
            assertTrue(executor.requests.any { it.messages.any { message -> message.images.isNotEmpty() } })
        }

    @Test fun bufferedFinalResponseCannotClaimStreaming() =
        runBlocking {
            val executor = FixtureExecutor(incremental = false)
            val result = CodexCapabilityProbe(provider(executor)).run() as ProbeOutcome.Failed
            assertEquals(3, result.phase)
            assertEquals(1, executor.requests.size)
        }

    @Test fun wrongToolArgumentsStopBeforeVisionAndReasoning() =
        runBlocking {
            val executor = FixtureExecutor(wrongArguments = true)
            val result = CodexCapabilityProbe(provider(executor)).run() as ProbeOutcome.Failed
            assertEquals(4, result.phase)
            assertEquals(2, executor.requests.size)
        }

    private fun provider(executor: FixtureExecutor) =
        CodexSubscriptionProvider(
            ProviderConfig(
                "fixture",
                "fixture",
                ProviderProtocol.OPENAI_RESPONSES,
                NormalizedEndpoint.parse("https://fixture.invalid"),
                "fixture",
                emptyMap(),
                SecretAlias("fixture"),
                "{}",
            ),
            executor,
            { CliModelCatalog.Listed(listOf(CliModelInfo("fixture", true, listOf("low", "adaptive_next"), 272000))) },
        )

    private class FixtureExecutor(
        private val incremental: Boolean = true,
        private val wrongArguments: Boolean = false,
    ) : SubscriptionJobExecutor {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun execute(request: ModelRequest) = execute(request) {}

        override suspend fun execute(
            request: ModelRequest,
            onProgress: (List<ModelEvent>) -> Unit,
        ): CliModelJobClient.AwaitOutcome {
            requests += request
            val events = reply(request)
            if (incremental) onProgress(events.take(1))
            val record =
                CliModelJobRecord(
                    "job_134000000007",
                    "a".repeat(64),
                    CliModelJobState.SUCCEEDED,
                    1,
                    terminalAtEpochMillis = 2,
                    model = "fixture",
                    outputSha256 = "b".repeat(64),
                )
            return CliModelJobClient.AwaitOutcome.Terminal(record, events)
        }

        private fun reply(request: ModelRequest): List<ModelEvent> {
            val last = request.messages.last()
            if (request.tools.isNotEmpty() && last.role != ModelRole.TOOL) {
                val nonce = if (wrongArguments) "wrong" else Regex("HELIX_[a-f0-9]+").find(last.text)!!.value
                return listOf(
                    ModelEvent.ToolCallStarted(
                        0,
                        ToolCallId("call_fixture"),
                        request.tools
                            .single()
                            .name.value,
                    ),
                    ModelEvent.ToolArgumentsDelta(0, """{"text":"$nonce"}"""),
                    ModelEvent.ToolCallFinished(0),
                    ModelEvent.Completed("tool_calls"),
                )
            }
            val text =
                when {
                    last.role == ModelRole.TOOL -> last.text
                    last.images.isNotEmpty() -> "red"
                    request.reasoning != ReasoningEffort.OFF -> "323"
                    else -> "1 2 3"
                }
            return listOf(ModelEvent.TextDelta(text), ModelEvent.Completed("stop"))
        }
    }
}
