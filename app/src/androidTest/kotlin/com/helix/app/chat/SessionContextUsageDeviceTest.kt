package com.helix.app.chat

import com.helix.app.agent.ChatContextProjection
import com.helix.app.agent.TurnStartSpec
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import com.helix.app.test.settleFixtureTerminal
import com.helix.app.test.startFixtureTurn
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.TurnState
import com.helix.core.storage.entity.transportIdentity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SessionContextUsageDeviceTest {
    @Test fun anEmptyUnboundSessionIsZeroButImportedHistoryWithoutTelemetryIsUnknown() {
        ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
            f.storage.sessions.create("empty-unbound", "Empty", null, null, 1)
            assertEquals(0L, ChatContextProjection.read(f.storage, "empty-unbound", f.service).usedTokens)
            f.storage.messages.append("imported-message", "empty-unbound", null, "USER", "TEXT", "Imported history")
            assertNull(ChatContextProjection.read(f.storage, "empty-unbound", f.service).usedTokens)
        }
    }

    @Test fun usageBelongsToTheSessionAndSurvivesPendingAndDiscardedSummaryCalls() {
        ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
            var time = 1000L
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(time++)
                }
            val next = { UUID.randomUUID().toString() }
            val config = f.storage.providerConfigs.resolve("p")
            val snapshot =
                buildJsonObject {
                    put("transportIdentity", config.transportIdentity)
                    put("model", "a")
                }.toString()
            f.storage.sessions.create("a", "A", "p", "a", time++)
            f.storage.sessions.create("b", "B", "p", "a", time++)

            fun start(session: String) =
                startFixtureTurn(
                    f.storage,
                    clock,
                    next,
                    TurnStartSpec(session, next(), next(), snapshot, "fixture"),
                )

            fun completed(
                session: String,
                input: Long,
            ) {
                val turn = start(session)
                val stream = kotlinx.coroutines.runBlocking { turn.beginModelStream() }
                stream.apply(ModelEvent.TextDelta("ok"))
                stream.apply(ModelEvent.Usage(input, 1))
                stream.apply(ModelEvent.Completed("stop"))
                turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            }
            assertEquals(0L, ChatContextProjection.read(f.storage, "a", f.service).usedTokens)
            val initial = start("a")
            assertNull(ChatContextProjection.read(f.storage, "a", f.service).usedTokens)
            initial.settleFixtureTerminal(ModelStreamTerminal(TurnState.FAILED, "FIXTURE_NO_REQUEST"))
            completed("a", 7474)
            completed("b", 12345)

            fun input(session: String) = ChatContextProjection.read(f.storage, session, f.service).inputTokens
            assertEquals(7474L, input("a"))
            assertEquals(12345L, input("b"))
            val pending = start("a")
            assertEquals(7474L, input("a"))
            val stream = kotlinx.coroutines.runBlocking { pending.beginModelStream(compacting = true) }
            kotlinx.coroutines.runBlocking { pending.recordDiagnostic("budget.admitted", """{"kind":"summary"}""") }
            stream.apply(ModelEvent.TextDelta("oversized summary"))
            stream.apply(ModelEvent.Usage(872, 971))
            stream.apply(ModelEvent.Completed("stop"))
            assertEquals(7474L, input("a"))
            pending.settleFixtureTerminal(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_NO_GAIN"))
            assertEquals(7474L, input("a"))
            assertEquals(12345L, input("b"))
            completed("a", 8000)
            assertEquals(8000L, input("a"))
            assertEquals(12345L, input("b"))
            val working = start("a")
            kotlinx.coroutines.runBlocking { working.beginModelStream() }.apply {
                apply(ModelEvent.Usage(9200, 12))
                apply(ModelEvent.Completed("tool_calls"))
            }
            kotlinx.coroutines.runBlocking { working.beginToolBatch(listOf("tool")) }
            kotlinx.coroutines.runBlocking { working.commitModelToolStep("[]") }
            assertEquals(TurnState.RUNNING_TOOL, working.snapshot().phase)
            assertEquals(9200L, input("a"))
            assertEquals(12345L, input("b"))
        }
    }
}
