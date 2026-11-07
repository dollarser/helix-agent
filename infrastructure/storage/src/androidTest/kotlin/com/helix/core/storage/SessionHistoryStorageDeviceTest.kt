package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real SQL and reopen coverage; assembling this APK is not a device pass. */
class SessionHistoryStorageDeviceTest {
    @Test fun callerAndRenameAreBoundToTheOriginalActiveCall() =
        withDatabase { db ->
            val dao = db.sessionHistoryDao()
            assertEquals("Original", dao.caller("s", "t", "c", "session.rename")?.title)
            assertNull(dao.caller("foreign", "t", "c", "session.rename"))
            assertNull(dao.caller("s", "t", "c", "session.read"))
            assertEquals(0, dao.rename("s", "t", "c", "stale", "wrong", "wrong"))
            assertEquals(1, dao.rename("s", "t", "c", "Original", "Saved", "saved"))
            assertEquals("scope:ws:folder", db.sessionDao().byId("s")?.directoryRef)
            db.toolCallDao().updateState("c", "COMPLETED")
            assertEquals(0, dao.rename("s", "t", "c", "Saved", "late", "late"))
            assertNull(dao.caller("s", "t", "c", "session.rename"))
        }

    @Test fun queryReadsOnlyEffectiveOwnedTextAndPaginatesWithoutNewAppendInterference() =
        withDatabase { db ->
            val dao = db.messageDao()
            listOf(
                MessageEntity("one", "s", null, "USER", "TEXT", null, 1),
                MessageEntity("two", "s", null, "ASSISTANT", "TEXT", null, 2),
                MessageEntity("revised", "s", null, "USER", "TEXT", null, 3, "replacement"),
                MessageEntity("system", "s", null, "SYSTEM", "TEXT", null, 4),
                MessageEntity("foreign", "foreign", null, "USER", "TEXT", null, 5),
            ).forEach(dao::insert)
            val history = db.sessionHistoryDao()
            assertEquals(listOf("two"), history.page("s", Long.MAX_VALUE, 1).map { it.id })
            dao.insert(MessageEntity("new", "s", null, "USER", "TEXT", null, 6))
            assertEquals(listOf("one"), history.page("s", 2, 10).map { it.id })
            assertNull(history.message("s", "foreign"))
            assertNull(history.message("s", "revised"))
            assertNull(history.message("s", "system"))
        }

    @Test fun turnQueriesBoundHistoryAndExcludeSupersededRetryTargets() =
        withDatabase { db ->
            val dao = db.turnDao()
            repeat(1000) { index ->
                dao.insert(TurnEntity("past-$index", "s", "FAILED", 0, index + 2L, index + 2L, "SYNTHETIC"))
            }
            db.messageDao().insert(MessageEntity("old", "s", "past-999", "USER", "TEXT", null, 1, "replacement"))
            db.messageDao().insert(MessageEntity("replacement", "s", "past-998", "USER", "TEXT", null, 2))
            val repository =
                com.helix.core.storage.repository
                    .TurnRepository(dao)
            assertEquals("past-998", repository.latestVisible("s")?.id)
            assertEquals("past-998", repository.latestVisible("s", com.helix.core.model.TurnState.FAILED)?.id)
            assertEquals(listOf("t"), repository.admissionBlockers("s").map { it.id })
            assertEquals(100, repository.byIds((900 until 1000).map { "past-$it" }).size)
            assertEquals(emptyList<TurnEntity>(), repository.byIds(emptyList()))
            assertNull(repository.latestVisible("foreign"))
            val failed = setOf(com.helix.core.model.TurnState.FAILED)
            assertEquals((995 until 1000).map { "past-$it" }, repository.recentByStates("s", failed, 5).map { it.id })
            assertEquals(false, repository.hasLaterCompleted("past-999"))
            dao.insert(TurnEntity("tie-success", "s", "COMPLETED", 0, 1001, 1001, null))
            assertEquals(true, repository.hasLaterCompleted("past-999"))
            dao.insert(TurnEntity("tie-failure", "s", "FAILED", 0, 1001, 1001, "SYNTHETIC"))
            assertEquals(false, repository.hasLaterCompleted("tie-failure"))
            assertEquals(false, repository.hasLaterCompleted("missing"))
        }

    @Test fun ledgerCandidatesAndBudgetCheckRetainFullSessionBoundaries() =
        withDatabase { db ->
            val dao = db.turnDao()
            dao.insert(TurnEntity("latest", "s", "FAILED", 0, 10, 10, "BUDGET"))
            assertEquals(false, dao.budgetContinuationClear("latest"))
            db.toolCallDao().updateState("c", "COMPLETED")
            assertEquals(true, dao.budgetContinuationClear("latest"))
            assertEquals(false, dao.budgetContinuationClear("t"))
            assertEquals(false, dao.budgetContinuationClear("missing"))
            db.toolCallDao().insert(
                ToolCallEntity("ledger", "latest", "wire-ledger", "todo.write", "1", "{}", "a".repeat(64), "COMPLETED"),
            )
            assertEquals(listOf("latest"), dao.ledgerCandidates("s", 0, 1).map { it.id })
            assertEquals(emptyList<TurnEntity>(), dao.ledgerCandidates("s", 1, 1))
            assertEquals(emptyList<TurnEntity>(), dao.ledgerCandidates("foreign", 0, 1))
            db.toolCallDao().updateState("ledger", "INTERRUPTED")
            assertEquals(false, dao.budgetContinuationClear("latest"))
            assertEquals(emptyList<TurnEntity>(), dao.ledgerCandidates("s", 0, 1))
            dao.insert(TurnEntity("same-time-newer", "s", "FAILED", 0, 10, 10, "BUDGET"))
            db.toolCallDao().updateState("ledger", "FAILED")
            assertEquals(false, dao.budgetContinuationClear("latest"))
            assertEquals(true, dao.budgetContinuationClear("same-time-newer"))
        }

    private fun withDatabase(test: (HelixDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "session-history-${java.util.UUID.randomUUID()}.db"

        fun open() = Room.databaseBuilder(context, HelixDatabase::class.java, name).build()
        var db = open()
        try {
            db.sessionDao().insert(SessionEntity("s", "Original", null, null, 1, null, "scope:ws:folder"))
            db.sessionDao().insert(SessionEntity("foreign", "Private", null, null, 1, null))
            db.turnDao().insert(TurnEntity("t", "s", "RUNNING_TOOL", 0, 1, null, null))
            db.toolCallDao().insert(
                ToolCallEntity("c", "t", "wire", "session.rename", "1", "{}", "a".repeat(64), "RUNNING"),
            )
            test(db)
            val saved = db.sessionDao().byId("s")
            db.close()
            db = open()
            assertEquals(saved, db.sessionDao().byId("s"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
