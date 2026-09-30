package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.entity.TurnRuntimeRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real Room query regression; host compilation does not claim that this device test ran. */
class FutureSessionDefaultsDeviceTest {
    @Test fun activeSettingsCallChangesFutureDefaultsWithoutMutatingFrozenRuntime() =
        withDatabase { db ->
            assertEquals(0, db.sessionDao().selectModel("s", "p", "model-b"))
            assertEquals(1, db.sessionDao().selectFutureModel("s", "p", "model-b", "t", "c"))
            assertEquals("model-b", db.sessionDao().byId("s")?.modelId)
            assertEquals("RUNNING_TOOL", db.turnDao().byId("t")?.state)
            assertEquals("model-a", db.turnRuntimeRecordDao().byTurn("t")?.modelId)
        }

    @Test fun wrongCallStoppedTurnAndArchivedSessionCannotChangeDefaults() =
        withDatabase { db ->
            assertEquals(0, db.sessionDao().selectFutureModel("s", "p", "model-b", "t", "other"))
            assertEquals(0, db.sessionDao().selectFutureModel("s", "p", "model-b", "foreign", "c"))
            db.toolCallDao().updateState("c", "COMPLETED")
            assertEquals(0, db.sessionDao().selectFutureModel("s", "p", "model-b", "t", "c"))
            db.toolCallDao().updateState("c", "RUNNING")
            assertEquals(1, db.turnDao().updateState("t", "RUNNING_TOOL", 0, "CANCELLING", 0, null, "USER_STOP"))
            assertEquals(0, db.sessionDao().selectFutureModel("s", "p", "model-b", "t", "c"))
            db.sessionDao().archive("s", 2)
            assertEquals("model-a", db.sessionDao().byId("s")?.modelId)
        }

    @Test fun latestStartupQueryReturnsOneTurnPerLiveSession() =
        withDatabase { db ->
            db.turnDao().insert(TurnEntity("new", "s", "COMPLETED", 0, 1, 1, null))
            db.sessionDao().insert(SessionEntity("old", "Archived", null, null, 1, 2))
            db.turnDao().insert(TurnEntity("hidden", "old", "INTERRUPTED", 0, 5, 5, null))
            assertEquals(listOf("new"), db.turnDao().latestForUnarchivedSessions().map { it.id })
        }

    private fun withDatabase(test: (HelixDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build()
        try {
            db.providerConfigDao().insert(
                ProviderConfigEntity(
                    "p",
                    "Fixture",
                    "OPENAI_CHAT",
                    "https://example.com/v1",
                    "model-a",
                    "{}",
                    null,
                    "{}",
                ),
            )
            db.sessionDao().insert(SessionEntity("s", "Fixture", "p", "model-a", 1, null))
            db.turnDao().insert(TurnEntity("t", "s", "RUNNING_TOOL", 0, 1, null, null))
            db.toolCallDao().insert(
                ToolCallEntity("c", "t", "c", "helix.settings.apply", "1", "{}", "a".repeat(64), "RUNNING"),
            )
            db.turnRuntimeRecordDao().insert(
                TurnRuntimeRecordEntity(
                    turnId = "t",
                    version = 1,
                    providerId = "p",
                    modelId = "model-a",
                    providerSnapshot = "{}",
                    mode = "ACT",
                    chatToolsEnabled = true,
                    budgetsJson = "{}",
                    reasoning = "OFF",
                    goalBudgetsJson = "{}",
                    consumedModelCalls = 0,
                    consumedTokens = 0,
                    admittedToolRounds = 0,
                ),
            )
            test(db)
        } finally {
            db.close()
        }
    }
}
