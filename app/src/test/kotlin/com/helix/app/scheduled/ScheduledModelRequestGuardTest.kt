package com.helix.app.scheduled

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.ProviderDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScheduledModelRequestGuardTest {
    private val provider =
        ProviderDescriptor(
            "p",
            "Fixture",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            "model",
            NormalizedEndpoint.parse("https://example.test/v1"),
        )
    private val request = ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "hello")))

    @Test
    fun optionalGuardPreservesOrdinaryRequests() =
        runBlocking {
            ScheduledModelRequestGuard().verify("turn", "call", provider, request)
        }

    @Test
    fun registrationCannotReplaceAuthorizationAndEveryCallKeepsItsIdentity() =
        runBlocking {
            val guard = ScheduledModelRequestGuard()
            val calls = mutableListOf<Pair<String, String>>()
            guard.install { turn, call, actualProvider, actualRequest ->
                assertEquals(provider, actualProvider)
                assertEquals(request, actualRequest)
                calls += turn to call
            }
            assertThrows(IllegalStateException::class.java) { guard.install { _, _, _, _ -> } }
            guard.verify("turn", "first", provider, request)
            guard.verify("turn", "compaction", provider, request)
            assertEquals(listOf("turn" to "first", "turn" to "compaction"), calls)
        }

    @Test
    fun refusalAndCancellationPropagateBeforeModelExecution() {
        val refused = ScheduledModelRequestGuard().apply { install { _, _, _, _ -> error("scope changed") } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { refused.verify("turn", "call", provider, request) }
        }
        val cancelled =
            ScheduledModelRequestGuard().apply {
                install { _, _, _, _ -> throw CancellationException("stopped") }
            }
        assertThrows(CancellationException::class.java) {
            runBlocking { cancelled.verify("turn", "call", provider, request) }
        }
    }
}
