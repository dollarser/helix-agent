package com.helix.core.agent

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestGuardTest {
    @Test fun guardRejectsBeforeFirstProviderStream(): Unit =
        runBlocking {
            val fixture = HeadlessLoopFixture { error("Must not stream") }
            var rejected = false
            try {
                fixture
                    .loop { turn, call, provider, request ->
                        assertEquals("turn", turn)
                        assertEquals(fixture.journal.snapshot().modelCallId, call)
                        assertEquals("p", provider.id)
                        assertEquals("fixture", request.model)
                        error("Scope drift")
                    }.runToolLoop("s", fixture.journal, "p", null, fixture.control)
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(fixture.requests.isEmpty())
        }

    @Test fun guardAlsoPrecedesCompactionStream(): Unit =
        runBlocking {
            val fixture = HeadlessLoopFixture { error("Must not stream summary") }
            fixture.journal.history.clear()
            fixture.journal.history += ModelMessage(ModelRole.USER, ContextCommands.COMPACT)
            fixture.source =
                object : ContextCompactionSource {
                    override suspend fun inputFloor(model: String) = 0L

                    override suspend fun plan(
                        request: TurnContextRequest,
                        control: RunControlConfig,
                        settings: ProviderContextSettings,
                        force: Boolean,
                        inputScale: Double,
                    ): ContextCompactionPlan {
                        assertTrue(force)
                        return ContextCompactionPlan(
                            1,
                            ModelRequest(
                                "fixture",
                                listOf(ModelMessage(ModelRole.USER, "Synthetic summary input")),
                                maxOutputTokens = 512,
                            ),
                            request,
                        )
                    }
                }
            var guarded = 0
            try {
                fixture
                    .loop { _, _, _, request ->
                        assertEquals("Synthetic summary input", request.messages.last().text)
                        guarded++
                        error("Summary scope drift")
                    }.runToolLoop("s", fixture.journal, "p", null, fixture.control)
            } catch (_: IllegalStateException) {
                // Expected rejection, no request is sent.
            }
            assertEquals(1, guarded)
            assertTrue(fixture.requests.isEmpty())
        }
}
