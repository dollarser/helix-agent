package com.helix.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.TurnState
import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.InputRecoveryClaim
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.repository.SessionInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Independent library APK and UUID-only fixture; never opens the daily application database. */
@RunWith(AndroidJUnit4::class)
class RoomInputRecoveryStoreDeviceTest {
    @Test fun claimResumeAndFailPreserveTheSingleRecoveryBudget() =
        fixture { storage, store ->
            val candidate = store.candidates("session").single()
            assertEquals(InputRecoveryClaim.Claimed(candidate), store.claim(candidate, 3))
            assertEquals(InputRecoveryClaim.AlreadyClaimed, store.claim(candidate, 4))
            assertTrue(store.resume(candidate, 5))
            storage.sessionInputs.parkSessionInputs("session", "INPUT_DELIVERY_FAILED", 6)
            val parked = store.candidates("session").single()
            assertEquals(InputRecoveryClaim.AlreadyClaimed, store.claim(parked, 7))
            assertTrue(store.fail(parked, "INPUT_RECOVERY_INTERRUPTED", "Ended", 8))
            assertFalse(store.fail(parked, "INPUT_RECOVERY_INTERRUPTED", "Ended", 9))
            assertEquals(SessionInputState.FAILED, storage.sessionInputs.get("input")?.state)
            assertEquals(1, storage.messages.listBySession("session").size)
            assertEquals("input.recovery_ended", storage.auditEvents.resolve("input-recovery-ended:input").type)
        }

    @Test fun noticeFailureRollsBackInputFailureAndAudit() =
        fixture { storage, store ->
            val candidate = store.candidates("session").single()
            store.claim(candidate, 3)
            storage.messages.append("input-ended:input", "session", null, "SYSTEM", "SYNTHETIC", "Collision")
            assertThrows(Exception::class.java) { store.fail(candidate, "INPUT_REVALIDATION_FAILED", "Ended", 4) }
            assertEquals(SessionInputState.NEEDS_ATTENTION, storage.sessionInputs.get("input")?.state)
            assertEquals(candidate.revision, storage.sessionInputs.get("input")?.revision)
            assertNull(storage.auditEvents.find("input-recovery-ended:input"))
        }

    @Test fun editedArchivedAndDeletedInputsRejectOldCommands() {
        for (mutation in listOf("edit", "archive", "delete")) {
            fixture { storage, store ->
                val candidate = store.candidates("session").single()
                store.claim(candidate, 3)
                when (mutation) {
                    "edit" -> {
                        assertTrue(
                            storage.sessionInputs.editPending(
                                "input",
                                candidate.revision,
                                input().copy(
                                    revision =
                                        candidate.revision + 1,
                                    text = "Replacement",
                                ),
                            ),
                        )
                    }

                    "archive" -> {
                        storage.sessions.archive("session", 4)
                    }

                    "delete" -> {
                        storage.deleteSessionPermanently("session")
                    }
                }
                assertFalse(store.resume(candidate, 5))
                assertFalse(store.fail(candidate, "INPUT_RECOVERY_UNAVAILABLE", "Ended", 5))
                assertTrue(storage.messages.listBySession("session").isEmpty())
            }
        }
    }

    @Test fun stoppedSessionNeverResumesOrFailsClaimedInput() =
        fixture { storage, store ->
            val candidate = store.candidates("session").single()
            store.claim(candidate, 3)
            val turn = storage.turns.start("stopped", "session", 1)
            storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, 4, "USER_STOP")
            assertFalse(store.resume(candidate, 5))
            assertFalse(store.fail(candidate, "INPUT_REVALIDATION_FAILED", "Ended", 5))
            assertEquals(candidate.revision, storage.sessionInputs.get("input")?.revision)
            assertTrue(storage.messages.listBySession("session").isEmpty())
        }

    private fun input() =
        SessionInputSpec(
            "input",
            "session",
            SessionInputDelivery.QUEUE,
            null,
            0,
            "Original",
            emptyList(),
            InputConfiguration("provider", "model", "ACT", "config"),
            1,
        )

    private fun fixture(block: (HelixStorage, RoomInputRecoveryStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "input-recovery-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        try {
            storage.sessions.create("session", "Synthetic", null, null, 0)
            storage.sessionInputs.accept(input())
            storage.sessionInputs.parkSessionInputs("session", "PROCESS_INTERRUPTED", 2)
            block(storage, RoomInputRecoveryStore(storage, Any()))
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
