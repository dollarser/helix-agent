package com.helix.app.chat

import com.helix.app.agent.ChatContextProjection
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.TurnState
import com.helix.core.storage.entity.transportIdentity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SessionContextUsageDeviceTest {
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
                TurnCoordinator.start(
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
                val stream = turn.beginModelStream()
                stream.apply(ModelEvent.TextDelta("ok"))
                stream.apply(ModelEvent.Usage(input, 1))
                stream.apply(ModelEvent.Completed("stop"))
                turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            }
            completed("a", 7474)
            completed("b", 12345)

            fun input(session: String) = ChatContextProjection.read(f.storage, session, f.service).inputTokens
            assertEquals(7474L, input("a"))
            assertEquals(12345L, input("b"))
            val pending = start("a")
            assertEquals(7474L, input("a"))
            val stream = pending.beginModelStream(compacting = true)
            pending.recordDiagnostic("budget.admitted", """{"kind":"summary"}""")
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
        }
    }
}
