package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundModelProviderTest {
    @Test fun ordinaryBackfillCompactionAndContinuationKeepTheExactTarget() =
        runBlocking {
            val delegate = Fixture()
            val bound = BoundModelProvider(delegate, "b") { true }
            val initial =
                ModelRequest(
                    model = "b",
                    messages = listOf(ModelMessage(ModelRole.USER, "task")),
                    reasoning = ReasoningEffort.HIGH,
                )
            val result =
                ModelMessage(
                    role = ModelRole.TOOL,
                    text = "verified result",
                    toolCallId = ToolCallId("call-1"),
                    toolName = ToolName("read"),
                )
            val requests =
                listOf(
                    initial,
                    initial.copy(messages = initial.messages + result),
                    initial.copy(
                        messages = listOf(ModelMessage(ModelRole.USER, "Summarize known results")),
                        reasoning = ReasoningEffort.OFF,
                    ),
                    initial.copy(messages = listOf(ModelMessage(ModelRole.USER, "Continue from saved results"))),
                )
            requests.forEach { assertTrue(bound.stream(it).toList().last() is ModelEvent.Completed) }
            assertEquals(requests, delegate.requests)
            assertTrue(delegate.requests.all { it.model == "b" })
        }

    @Test fun changedAccountStopsLaterCallsWithoutReplayingTheFirst() =
        runBlocking {
            val delegate = Fixture()
            var current = true
            val bound = BoundModelProvider(delegate, "b") { current }
            val request = ModelRequest("b", listOf(ModelMessage(ModelRole.USER, "task")))
            bound.stream(request).toList()
            current = false
            assertEquals(ModelErrorCode.AUTH, (bound.stream(request).toList().single() as ModelEvent.Error).code)
            assertEquals(1, delegate.requests.size)
        }

    @Test fun mismatchedModelCannotReachTransport() =
        runBlocking {
            val delegate = Fixture()
            val bound = BoundModelProvider(delegate, "b") { true }
            val result = bound.stream(ModelRequest("a", listOf(ModelMessage(ModelRole.USER, "task")))).toList()
            assertEquals(ModelErrorCode.PROTOCOL, (result.single() as ModelEvent.Error).code)
            assertTrue(delegate.requests.isEmpty())
        }

    @Test fun cancellationIsNotConvertedToSuccess() =
        runBlocking {
            val delegate = Fixture()
            val bound = BoundModelProvider(delegate, "b") { throw CancellationException("fixture") }
            val result =
                runCatching {
                    bound.stream(ModelRequest("b", listOf(ModelMessage(ModelRole.USER, "task")))).toList()
                }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(delegate.requests.isEmpty())
        }

    private class Fixture : ModelProvider {
        val requests = mutableListOf<ModelRequest>()
        override val descriptor =
            ProviderDescriptor(
                "p",
                "Fixture",
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "b",
                NormalizedEndpoint.parse("https://example.test/v1"),
            )

        override suspend fun listModels() = ModelCatalogResult.Listed(listOf("a", "b"))

        override suspend fun validateConfiguration() = ProviderCheckResult.Ok

        override fun stream(request: ModelRequest) =
            flowOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop"))
                .also { requests += request }
    }
}
