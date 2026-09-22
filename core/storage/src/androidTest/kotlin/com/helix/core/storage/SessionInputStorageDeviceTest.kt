package com.helix.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.TurnState
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.InputAttachment
import com.helix.core.storage.repository.InputConfiguration
import com.helix.core.storage.repository.SessionInputAcceptResult
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputSpec
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Storage acceptance only: actual process death and protocol delivery belong to the app matrix. */
@RunWith(AndroidJUnit4::class)
class SessionInputStorageDeviceTest {
    @Test
    fun concurrentAcceptanceAllocatesUniqueFifoSequenceAndEnforcesCapacity() =
        withStorage { storage ->
            val outcomes = race((0 until 40).map { index -> { storage.sessionInputs.accept(spec("input-$index")) } })
            val accepted = outcomes.filterIsInstance<SessionInputAcceptResult.Accepted>()
            assertEquals(32, accepted.size)
            assertEquals(8, outcomes.filterIsInstance<SessionInputAcceptResult.Rejected>().size)
            assertEquals((1L..32L).toList(), storage.sessionInputs.listPending(SESSION).map { it.sequence })
            assertTrue(storage.messages.listBySession(SESSION).isEmpty())
            assertTrue(storage.turns.listBySession(SESSION).isEmpty())
        }

    @Test
    fun concurrentDuplicateAcceptIsStableAndChangedIdentityConflicts() =
        withStorage { storage ->
            val request = spec("same")
            val outcomes =
                race(List(8) { { storage.sessionInputs.accept(request) } })
                    .map { it as SessionInputAcceptResult.Accepted }
            assertEquals(1, outcomes.count { !it.duplicate })
            assertEquals(1, storage.sessionInputs.listPending(SESSION).size)
            val conflicting = storage.sessionInputs.accept(request.copy(text = "changed"))
            val conflict = conflicting as SessionInputAcceptResult.Rejected
            assertEquals("INPUT_ID_CONFLICT", conflict.reason)
            val saved = storage.sessionInputs.get("same")!!
            storage.sessionInputs.parkSessionInputs(SESSION, "USER_STOPPED", 3)
            assertTrue((storage.sessionInputs.accept(request) as SessionInputAcceptResult.Accepted).duplicate)
            assertEquals(saved.sequence, storage.sessionInputs.get("same")!!.sequence)
        }

    @Test
    fun utf8BudgetCountsParkedInputsAndEditingCannotExceedIt() =
        withStorage { storage ->
            val text = "\u4e2d".repeat(128_000)
            val first = accepted(storage, spec("large-a", text))
            accepted(storage, spec("large-b", text))
            assertEquals(384_000L, first.textBytes)
            storage.sessionInputs.parkSessionInputs(SESSION, "USER_STOPPED", 2)
            val rejected = storage.sessionInputs.accept(spec("large-c", text)) as SessionInputAcceptResult.Rejected
            assertEquals("INPUT_QUEUE_BYTES", rejected.reason)
            val small = accepted(storage, spec("small", "x"))
            assertFalse(
                storage.sessionInputs.editPending(
                    "small",
                    small.revision,
                    spec("small", text).copy(revision = small.revision + 1),
                ),
            )
            assertEquals("x", storage.sessionInputs.readText(storage.sessionInputs.get("small")!!))
        }

    @Test
    fun editWithdrawAndConsumeHaveExactlyOneRevisionWinner() =
        withStorage { storage ->
            accepted(storage, spec("race"))
            val wins =
                race(
                    listOf(
                        { storage.sessionInputs.editPending("race", 0, spec("race", "edited").copy(revision = 1)) },
                        { storage.sessionInputs.withdrawPending("race", 0, 2) },
                        { consume(storage, "race", 0, "turn-race", "message-race") },
                    ),
                )
            assertEquals(1, wins.count { it })
            val row = storage.sessionInputs.get("race")!!
            if (row.state == SessionInputState.APPENDED) {
                assertEquals("message-race", row.messageId)
                assertEquals(1, storage.messages.listBySession(SESSION).size)
            } else {
                assertTrue(storage.turns.listBySession(SESSION).isEmpty())
                assertTrue(storage.messages.listBySession(SESSION).isEmpty())
            }
            assertFalse(storage.sessionInputs.withdrawPending("race", 0, 3))
        }

    @Test
    fun consumptionMappingAndRequestStartRemainDistinctAndSessionBound() =
        withStorage { storage ->
            accepted(storage, spec("consume"))
            assertTrue(consume(storage, "consume", 0, "turn", "message"))
            val row = storage.sessionInputs.get("consume")!!
            assertEquals(SessionInputState.APPENDED, row.state)
            assertNull(row.requestModelCallId)
            assertEquals(listOf(row), storage.sessionInputs.pendingRequest("turn"))
            storage.sessions.create("other", "Other", null, null, 0)
            storage.turns.start("other-turn", "other", 1)
            storage.modelCalls.append("wrong-model", "other-turn", "{}", "RUNNING")
            assertThrows(IllegalArgumentException::class.java) {
                storage.sessionInputs.markRequestStarted("consume", "wrong-model", 3)
            }
            storage.modelCalls.append("model", "turn", "{}", "RUNNING")
            assertNull(storage.sessionInputs.get("consume")!!.requestModelCallId)
            assertTrue(storage.sessionInputs.markRequestStarted("consume", "model", 3))
            assertTrue(storage.sessionInputs.markRequestStarted("consume", "model", 4))
            assertTrue(storage.sessionInputs.pendingRequest("turn").isEmpty())
            assertFalse(
                storage.sessionInputs.editPending(
                    "consume",
                    row.revision,
                    spec("consume", "changed").copy(revision = row.revision + 1),
                ),
            )
        }

    @Test
    fun parkedHeadDoesNotReleaseImplicitlyAndResumeRevalidatesSteerTarget() =
        withStorage { storage ->
            accepted(storage, spec("old"))
            assertEquals(1, storage.sessionInputs.parkSessionInputs(SESSION, "USER_STOPPED", 2))
            assertEquals(0, storage.sessionInputs.parkAllPending("PROCESS_INTERRUPTED", 3))
            accepted(storage, spec("new"))
            val old = storage.sessionInputs.headQueue(SESSION)!!
            assertEquals("old", old.inputId)
            assertEquals(SessionInputState.NEEDS_ATTENTION, old.state)
            assertTrue(
                storage.sessionInputs.editPending(
                    "old",
                    old.revision,
                    spec("old", "edit parked").copy(revision = old.revision + 1),
                ),
            )
            val edited = storage.sessionInputs.get("old")!!
            assertEquals("USER_STOPPED", edited.blockedReason)
            assertTrue(storage.sessionInputs.resumePending("old", edited.revision, 5))
            val target = storage.turns.start("target", SESSION, 1)
            val steer = spec("steer").copy(delivery = SessionInputDelivery.STEER, expectedTurnId = target.id)
            accepted(storage, steer)
            assertTrue(storage.sessionInputs.markNeedsAttention("steer", 0, "CONFIGURATION_CHANGED", 2))
            storage.turns.updateState(target, TurnState.CANCELLING, 0, null, null)
            assertFalse(storage.sessionInputs.resumePending("steer", 1, 3))
            val late = storage.sessionInputs.accept(steer.copy(inputId = "late"))
            assertEquals("INPUT_TARGET_UNAVAILABLE", (late as SessionInputAcceptResult.Rejected).reason)
        }

    @Test
    fun invalidSessionTargetAndConsumedMessageBindingsFailClosed() =
        withStorage { storage ->
            storage.sessions.create("other", "Other", null, null, 0)
            storage.turns.start("other-turn", "other", 1)
            val request = spec("cross").copy(delivery = SessionInputDelivery.STEER, expectedTurnId = "other-turn")
            val rejected = storage.sessionInputs.accept(request) as SessionInputAcceptResult.Rejected
            assertEquals("INPUT_TARGET_UNAVAILABLE", rejected.reason)
            accepted(storage, spec("own"))
            storage.withTransaction {
                storage.messages.append("other-message", "other", "other-turn", "USER", "TEXT", "x")
            }
            assertThrows(IllegalArgumentException::class.java) {
                storage.sessionInputs.markAppended("own", 0, "other-turn", "other-message", 2)
            }
            assertEquals(SessionInputState.PENDING, storage.sessionInputs.get("own")!!.state)
        }

    @Test
    fun stopAndConsumeAreLinearizedAndNeitherDropsTheInput() =
        withStorage { storage ->
            accepted(storage, spec("stop-race"))
            val results =
                race(
                    listOf(
                        { storage.sessionInputs.parkSessionInputs(SESSION, "USER_STOPPED", 3) == 1 },
                        { consume(storage, "stop-race", 0, "stop-turn", "stop-message") },
                    ),
                )
            assertEquals(1, results.count { it })
            val row = storage.sessionInputs.get("stop-race")!!
            assertEquals("text", storage.sessionInputs.readText(row))
            if (row.state == SessionInputState.NEEDS_ATTENTION) {
                assertTrue(storage.turns.listBySession(SESSION).isEmpty())
            } else {
                assertEquals(SessionInputState.APPENDED, row.state)
                assertEquals("stop-message", row.messageId)
            }
        }

    @Test
    fun roomReopenPreservesParkedAndAppendedInputsWithoutStartingRequests() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "session-input-reopen-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, name)
        var storage = HelixStorage.open(context, name, content)
        try {
            storage.sessions.create(SESSION, "Reopen", null, null, 0)
            accepted(storage, spec("already-appended"))
            assertTrue(consume(storage, "already-appended", 0, "reopen-turn", "reopen-message"))
            accepted(storage, spec("pending"))
            storage.close()
            storage = HelixStorage.open(context, name, content)
            assertEquals(1, storage.sessionInputs.parkAllPending("PROCESS_INTERRUPTED", 3))
            assertEquals(0, storage.sessionInputs.parkAllPending("PROCESS_INTERRUPTED", 4))
            assertEquals(SessionInputState.NEEDS_ATTENTION, storage.sessionInputs.get("pending")!!.state)
            assertEquals(SessionInputState.APPENDED, storage.sessionInputs.get("already-appended")!!.state)
            assertEquals(1, storage.messages.listBySession(SESSION).size)
            assertEquals(1, storage.turns.listBySession(SESSION).size)
            assertTrue(storage.modelCalls.listByTurn("reopen-turn").isEmpty())
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }

    @Test
    fun pendingAttachmentsPreventArtifactDeletionAndSessionEraseCascades() =
        withStorage { storage ->
            val body = File.createTempFile("input-attachment", ".txt").apply { writeText("attachment") }
            try {
                val hash = FileContentStore.sha256Hex(body)
                storage.artifacts.register(
                    "artifact",
                    SESSION,
                    "input/attachment.txt",
                    "text/plain",
                    body.length(),
                    hash,
                    body,
                )
                val attachment = InputAttachment("artifact", hash)
                val row = accepted(storage, spec("attached").copy(attachments = listOf(attachment)))
                assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                    storage.database.openHelper.writableDatabase
                        .execSQL("DELETE FROM artifacts WHERE id = 'artifact'")
                }
                assertEquals(
                    1,
                    storage.sessionInputs
                        .get("attached")!!
                        .attachments.size,
                )
                storage.deleteSessionPermanently(SESSION)
                assertNull(storage.sessionInputs.get("attached"))
                assertFalse(storage.contentStore.exists(ContentRef.parse(row.textRef)))
            } finally {
                body.delete()
            }
        }

    @Test
    fun deletingOneSessionRetainsContentReferencedOnlyByAnotherPendingInput() =
        withStorage { storage ->
            storage.sessions.create("other", "Other", null, null, 0)
            storage.withTransaction {
                storage.messages.append("history", SESSION, null, "USER", "TEXT", "shared content")
            }
            val pending = accepted(storage, spec("survivor", "shared content").copy(sessionId = "other"))
            storage.deleteSessionPermanently(SESSION)
            assertEquals("shared content", storage.sessionInputs.readText(pending))
            storage.deleteSessionPermanently("other")
            assertFalse(storage.contentStore.exists(ContentRef.parse(pending.textRef)))
        }

    private fun consume(
        storage: HelixStorage,
        inputId: String,
        revision: Long,
        turnId: String,
        messageId: String,
    ): Boolean =
        try {
            storage.withTransaction {
                storage.turns.start(turnId, SESSION, 1, inputId)
                storage.messages.append(messageId, SESSION, turnId, "USER", "TEXT", "text")
                if (!storage.sessionInputs.markAppended(inputId, revision, turnId, messageId, 2)) throw LostRace()
            }
            true
        } catch (_: LostRace) {
            false
        }

    private fun accepted(
        storage: HelixStorage,
        request: SessionInputSpec,
    ): SessionInputRecord = (storage.sessionInputs.accept(request) as SessionInputAcceptResult.Accepted).record

    private fun spec(
        id: String,
        text: String = "text",
    ) = SessionInputSpec(
        id,
        SESSION,
        SessionInputDelivery.QUEUE,
        null,
        0,
        text,
        emptyList(),
        InputConfiguration("provider", "model", "ACT", "configuration-v1"),
        1,
    )

    private fun <T> race(actions: List<() -> T>): List<T> {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        return try {
            val futures =
                actions.map { action ->
                    pool.submit(
                        Callable {
                            start.await()
                            action()
                        },
                    )
                }
            start.countDown()
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "session-input-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, content)
        try {
            storage.sessions.create(SESSION, "Session inputs", null, null, 0)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }

    private class LostRace : RuntimeException()

    companion object {
        private const val SESSION = "session"
    }
}
