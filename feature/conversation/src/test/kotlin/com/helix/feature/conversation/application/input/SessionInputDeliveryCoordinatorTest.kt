package com.helix.feature.conversation.application.input

import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SessionInputConfiguration
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.TurnBudgets
import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.SessionInputDelivery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionInputDeliveryCoordinatorTest {
    @Test fun proposedSteerRechecksOriginalTargetRatherThanReloadingTheQueueMeaning() =
        runBlocking {
            val fixture = Fixture()
            assertNotNull(fixture.coordinator.revalidate(fixture.key))
            fixture.store.targetSnapshot = "other-original-provider"
            assertNull(fixture.coordinator.revalidateForSteering(fixture.key, "target"))
            assertEquals(listOf("target"), fixture.store.targets)
            assertEquals(SessionInputDelivery.QUEUE, fixture.store.input.delivery)
            assertTrue(fixture.store.parked.isEmpty())
        }

    @Test fun proposedSteerUsesOriginalTargetControlAndCannotChangeAcceptedConfiguration() =
        runBlocking {
            val fixture = Fixture()
            fixture.runtime.targetControl = fixture.control.copy(chatToolsEnabled = false)
            assertNull(fixture.coordinator.revalidateForSteering(fixture.key, "target"))
            assertTrue(fixture.store.parked.isEmpty())
            fixture.runtime.targetControl = fixture.control
            assertNotNull(fixture.coordinator.revalidateForSteering(fixture.key, "target"))
            assertEquals(SessionInputDelivery.QUEUE, fixture.store.input.delivery)
        }

    @Test fun corruptAcceptedContentParksOnlyTheCapturedRevision() =
        runBlocking {
            val fixture = Fixture()
            fixture.store.loadFailure = IllegalArgumentException("Changed bound content")
            assertNull(fixture.coordinator.revalidate(fixture.key))
            assertEquals(listOf(fixture.key), fixture.store.parked)
        }

    private class Fixture {
        val control = RunControlConfig(AgentMode.ACT, true, TurnBudgets(maxOutputTokens = 1000))
        val key = InputDeliveryKey("input", "session", 7)
        val store =
            Store(
                AcceptedInputSnapshot(
                    key,
                    SessionInputDelivery.QUEUE,
                    null,
                    InputConfiguration(
                        "provider",
                        "model",
                        "ACT",
                        "v1:${SessionInputConfiguration.hash("provider-facts", control, "ACT")}:selection",
                    ),
                    emptyList(),
                    false,
                    null,
                    emptyList(),
                    emptyList(),
                    null,
                ),
            )
        val runtime = Runtime(control)
        val coordinator =
            SessionInputDeliveryCoordinator(
                store,
                Provider(),
                runtime,
                { PreparedSessionInput("Accepted content", emptyList()) },
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(10)
                },
                Any(),
                {},
                { true },
                { _, _, _ -> true },
                { _, _ -> true },
            )
    }

    private class Store(
        val input: AcceptedInputSnapshot,
    ) : SessionInputDeliveryStore {
        var targetSnapshot = "provider-facts"
        var loadFailure: RuntimeException? = null
        val targets = mutableListOf<String>()
        val parked = mutableListOf<InputDeliveryKey>()

        override fun load(key: InputDeliveryKey): AcceptedInputSnapshot? {
            loadFailure?.let { throw it }
            return input.takeIf {
                it.key ==
                    key
            }
        }

        override fun selection(sessionId: String) = InputSessionSelection("provider", "model")

        override fun headSteer(
            sessionId: String,
            turnId: String,
        ): InputDeliveryKey? = null

        override fun steerProviderSnapshot(
            sessionId: String,
            turnId: String,
        ): String {
            targets += turnId
            return targetSnapshot
        }

        override fun park(
            key: InputDeliveryKey,
            reason: String,
            at: Long,
        ) {
            parked += key
        }

        override fun parkAdmissionFailureIfIdle(
            key: InputDeliveryKey,
            at: Long,
        ) = error("not delivering")
    }

    private class Runtime(
        private val control: RunControlConfig,
    ) : InputDeliveryRuntime {
        var targetControl = control

        override fun hasLiveTurn(sessionId: String) = false

        override fun selectedControl(sessionId: String) = control

        override fun steerControl(turnId: String?) = targetControl

        override fun queueStillConsumable(
            key: InputDeliveryKey,
            requireHead: Boolean,
        ) = true

        override suspend fun deliverQueued(
            key: InputDeliveryKey,
            prepared: PreparedSessionInput,
            control: RunControlConfig,
            requireHead: Boolean,
        ): String? = error("not delivering")
    }

    private class Provider : InputDeliveryProvider {
        override fun selectable(providerId: String) = true

        override suspend fun defaultModel(providerId: String) = "model"

        override suspend fun supportsVision(
            providerId: String,
            modelId: String?,
        ) = true

        override suspend fun supportsTools(
            providerId: String,
            modelId: String?,
        ) = true

        override suspend fun snapshot(
            providerId: String,
            modelId: String?,
        ) = "provider-facts"
    }
}
