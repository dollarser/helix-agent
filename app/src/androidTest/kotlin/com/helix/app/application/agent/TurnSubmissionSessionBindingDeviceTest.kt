package com.helix.app.application.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.TurnStartSpec
import com.helix.app.infrastructure.agent.createRoomTurnEngine
import com.helix.app.test.fixtureAdmissionCommand
import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.agent.turn.TurnAdmissionResult
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Closed admission rechecks prepared identity inside the real Room transaction. */
class TurnSubmissionSessionBindingDeviceTest {
    @Test
    fun archiveAfterPreparationRefusesAtomicAdmissionWithoutWritingTurnOrHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "submission-binding-${UUID.randomUUID()}.db"
        val files = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, files)
        val worker = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            seedSession(storage)
            val prepared = CountDownLatch(1)
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(2000L)
                }
            val engine =
                createRoomTurnEngine(
                    storage,
                    clock,
                    { UUID.randomUUID().toString() },
                )
            val result =
                worker.submit<TurnAdmissionResult> {
                    val original = storage.sessions.resolve("session")
                    val binding = TurnSessionAdmissionBinding(requireNotNull(original.providerId), original.modelId)
                    assertTrue(binding.matches(original))
                    prepared.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    engine.admit(preparedCommand(original.modelId))
                }
            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            storage.sessions.archive("session", 1500L)
            release.countDown()
            assertEquals(TurnAdmissionResult.FreshRejected, result.get(5, TimeUnit.SECONDS))
            assertTrue(storage.turns.listBySession("session").isEmpty())
            assertTrue(storage.messages.listBySession("session").isEmpty())
            assertNull(storage.turns.resolveByClientRequestId("original-request"))
        } finally {
            release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            storage.close()
            context.deleteDatabase(name)
            files.deleteRecursively()
        }
    }

    private fun preparedCommand(modelId: String?) =
        fixtureAdmissionCommand(
            spec =
                TurnStartSpec(
                    "session",
                    "turn",
                    "model-call",
                    "snapshot",
                    "synthetic input",
                    clientRequestId = "original-request",
                    inputFingerprint = "original-fingerprint",
                ),
            control = RunControlConfig(AgentMode.CHAT, false, TurnBudgets(maxOutputTokens = 1024)),
            wakeReason = GoalWakeReason.USER_OPEN,
            providerId = "provider",
            modelId = "model",
            expectedSessionModelId = modelId,
            goal =
                com.helix.core.agent.turn.TurnGoalIntent
                    .Explicit("must-not-be-resolved"),
        )

    private fun seedSession(storage: HelixStorage) {
        storage.providerConfigs.save(
            ProviderConfigSpec(
                id = "provider",
                displayName = "Synthetic provider",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://admission-fixture.invalid/v1",
                model = "model",
                headersJson = "{}",
                secretAlias = "synthetic-unused-alias",
                capabilitySnapshot = "{}",
            ),
        )
        storage.sessions.create("session", "Synthetic admission race", "provider", "model", 1000L)
    }
}
