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
