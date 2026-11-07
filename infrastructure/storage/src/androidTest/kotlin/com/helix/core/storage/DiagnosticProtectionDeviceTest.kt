package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Actual SQLite protection query; compiling this is not a device pass. */
class DiagnosticProtectionDeviceTest {
    @Test fun collectedTerminalTurnStillProtectsUnsettledToolEffects() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build()
        try {
            db.sessionDao().insert(SessionEntity("session", "Synthetic", null, null, 1, null))
            val states = listOf("PENDING", "RUNNING", "AWAITING_APPROVAL", "NEEDS_REVIEW", "INTERRUPTED", "COMPLETED")
            states.forEachIndexed { index, state ->
                val id = "turn-$index"
                db.turnDao().insert(TurnEntity(id, "session", "FAILED", 1, 1, 2, "synthetic", resultCollectedAt = 3))
                db.toolCallDao().insert(
                    ToolCallEntity("call-$index", id, "provider-$index", "read", "1", "{}", "hash", state),
                )
                db.toolResultDao().insert(
                    ToolResultEntity("result-$index", "call-$index", "SUCCESS", "synthetic", null, true),
                )
            }
            assertEquals((0..4).map { "turn-$it" }.toSet(), db.turnDao().diagnosticProtectedOwners().toSet())
        } finally {
            db.close()
        }
    }
}
