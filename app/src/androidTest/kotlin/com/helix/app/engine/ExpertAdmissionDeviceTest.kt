package com.helix.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.TurnStartSpec
import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ExpertProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class ExpertAdmissionDeviceTest {
    @Test
    fun expertEditsAffectFutureTurnsOnlyAndNeverChangePermission() =
        fixture { storage ->
            val permission = requireNotNull(storage.sessionPermissionConfigs.forSession(SESSION))
            val firstExpert = ExpertProfile("expert-a", "Reviewer A", "Prefer architecture review.")
            storage.sessionExperts.setForSession(SESSION, firstExpert, 1)

            val first =
                admit(storage, "turn-a", "request-a") as TurnAdmissionResult.Started
            assertEquals(
                firstExpert,
                TurnRuntimeRecordCodec.decode(storage.turnRuntimeRecords.resolve("turn-a")).expert,
            )
            first.turn.coordinator.beginModelStream()
            first.turn.coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))

            val secondExpert = ExpertProfile("expert-b", "Reviewer B", "Prefer implementation review.")
            storage.sessionExperts.setForSession(SESSION, secondExpert, 2)

            assertEquals(
                firstExpert,
                TurnRuntimeRecordCodec.decode(storage.turnRuntimeRecords.resolve("turn-a")).expert,
            )
            assertEquals(permission, storage.sessionPermissionConfigs.forSession(SESSION))

            admit(storage, "turn-b", "request-b") as TurnAdmissionResult.Started
            assertEquals(
                secondExpert,
                TurnRuntimeRecordCodec.decode(storage.turnRuntimeRecords.resolve("turn-b")).expert,
            )
            assertEquals(permission, storage.sessionPermissionConfigs.forSession(SESSION))
        }

    private fun admit(
        storage: HelixStorage,
        turnId: String,
        requestId: String,
    ): TurnAdmissionResult =
        TurnAdmission(storage, clock) { UUID.randomUUID().toString() }
            .start(
                spec =
                    TurnStartSpec(
                        sessionId = SESSION,
                        turnId = turnId,
                        firstModelCallId = "model-$turnId",
                        providerSnapshot = "snapshot",
                        userText = "work",
                        clientRequestId = requestId,
                        inputFingerprint = "fp-$requestId",
                    ),
                control = control,
                wakeReason = GoalWakeReason.USER_OPEN,
                providerId = "provider",
                modelId = "model",
            )

    private val control =
        RunControlConfig(
            mode = AgentMode.ACT,
            chatToolsEnabled = true,
            budgets = TurnBudgets(4, 4, 8_000, 2_000, 16_000),
            reasoning = ReasoningEffort.LOW,
            goalBudgets = GoalBudgetDefaults.VALUE,
        )

    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(1_000)
        }

    private fun fixture(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "expert-admission-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, root)
        try {
            storage.sessions.create(SESSION, "Expert admission", null, null, 1)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private companion object {
        const val SESSION = "expert-session"
    }
}
