package com.helix.app.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ModelCallRecoveryDeviceTest {
    @Test fun recoveryClosesActiveAndPreviouslyOrphanedModelCallsWithoutChangingKnownUsage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "model-call-recovery-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, content)
        try {
            storage.sessions.create("session", "Model recovery fixture", null, null, 1)
            val old = storage.turns.start("old", "session", 1)
            storage.turns.updateState(old, TurnState.INTERRUPTED, 0, 2, null)
            storage.turns.start("active", "session", 1)
            listOf("old", "active").forEach { turn ->
                val call = storage.modelCalls.append("running-$turn", turn, "fixture", "RUNNING")
                storage.modelCalls.update(call, "RUNNING", "{}", "known-request")
                storage.modelCalls.append("completed-$turn", turn, "fixture", "COMPLETED")
            }
            val completed = storage.modelCalls.resolve("completed-active")
            val recovery = RecoveryCoordinatorApp(storage, SystemClock())
            recovery.recover()
            listOf("old", "active").forEach { turn ->
                val call = storage.modelCalls.resolve("running-$turn")
                assertEquals("INTERRUPTED", call.state)
                assertEquals("{}", call.usage)
                assertEquals("known-request", call.requestId)
            }
            assertEquals(completed, storage.modelCalls.resolve("completed-active"))
            val audit = storage.auditEvents.listByCorrelation("process-recovery")
            assertEquals(1, audit.count { it.type == "recovery.model_calls_interrupted" })
            recovery.recover()
            assertEquals(audit, storage.auditEvents.listByCorrelation("process-recovery"))
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }
}
