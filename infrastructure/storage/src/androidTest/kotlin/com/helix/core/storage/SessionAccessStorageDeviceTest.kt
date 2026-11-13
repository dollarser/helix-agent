package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real SQL/reopen acceptance; test APK compilation alone is not a device pass. */
class SessionAccessStorageDeviceTest {
    @Test fun executionProjectionStaysOnTargetAndExcludesTerminalTurns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build()
        try {
            listOf("target", "other").forEach { id ->
                db.sessionDao().insert(SessionEntity(id, id, null, null, 0, null))
            }
            db.turnDao().insert(TurnEntity("target-turn", "target", "WAITING_MODEL", 0, 1, null, null))
            db.turnDao().insert(TurnEntity("other-turn", "other", "RUNNING_TOOL", 0, 2, null, null))
            val terminal =
                com.helix.core.model.TurnState.entries
                    .filter { it.isTerminal }
                    .map { it.name }
            assertEquals("target-turn", db.sessionAccessDao().execution("target", terminal)?.turnId)
            assertEquals("WAITING_MODEL", db.sessionAccessDao().execution("target", terminal)?.state)
            assertEquals(null, db.sessionAccessDao().execution("missing", terminal))
            db.turnDao().insert(TurnEntity("finished", "target", "COMPLETED", 0, 3, 4, null))
            assertEquals("target-turn", db.sessionAccessDao().execution("target", terminal)?.turnId)
        } finally {
            db.close()
        }
    }

    @Test fun grantRevisionsPersistAndBoundMetadataCasRejectsRevocationAndLateCall() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "session-access-${java.util.UUID.randomUUID()}.db"

        fun open() = Room.databaseBuilder(context, HelixDatabase::class.java, name).build()
        var db = open()
        try {
            listOf("source", "target", "private").forEach { id ->
                db.sessionDao().insert(SessionEntity(id, id, null, null, 0, null, "scope:unchanged"))
            }
            db.turnDao().insert(TurnEntity("turn", "source", "RUNNING_TOOL", 0, 0, null, null))
            db.toolCallDao().insert(
                ToolCallEntity("call", "turn", "wire", "session.rename", "1", "{}", "a".repeat(64), "RUNNING"),
            )
            var dao = db.sessionAccessDao()
            val initial = dao.setGrant("source", "target", false, true)
            assertEquals(listOf("target"), dao.list("source", null, 20).map { it.targetSessionId })
            assertEquals(
                0,
                dao.rename("source", "private", initial.revision, "turn", "call", "private", "leaked", "leaked"),
            )
            assertEquals(
                1,
                dao.rename("source", "target", initial.revision, "turn", "call", "target", "renamed", "renamed"),
            )
            assertEquals("scope:unchanged", db.sessionDao().byId("target")?.directoryRef)
            dao.setGrant("source", "target", false, false)
            assertEquals(emptyList<String>(), dao.list("source", null, 20).map { it.targetSessionId })
            assertEquals(
                0,
                dao.rename("source", "target", initial.revision, "turn", "call", "renamed", "stale", "stale"),
            )
            db.close()
            db = open()
            dao = db.sessionAccessDao()
            assertEquals(2L, dao.grant("source", "target")?.revision)
            val audits = db.auditEventDao().listByCorrelation("source")
            assertEquals(2, audits.size)
            assertEquals("session.access_grant", audits.last().type)
            assertEquals("user", audits.last().actor)
            assertEquals(
                "{\"sourceSessionId\":\"source\",\"targetSessionId\":\"target\"," +
                    "\"readHistory\":false,\"manageMetadata\":false,\"delegateTasks\":false,\"revision\":2}",
                audits.last().redactedPayload,
            )
            val renewed = dao.setGrant("source", "target", true, true)
            assertEquals(3L, renewed.revision)
            assertEquals(
                0,
                dao.rename("source", "target", initial.revision, "turn", "call", "renamed", "stale", "stale"),
            )
            db.toolCallDao().updateState("call", "COMPLETED")
            assertEquals(0, dao.rename("source", "target", renewed.revision, "turn", "call", "renamed", "late", "late"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun archiveChangesOnlyVisibilityAndExactToolIdentityIsRequired() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build()
        try {
            listOf("source", "target").forEach { id ->
                db.sessionDao().insert(SessionEntity(id, id, null, null, 0, null))
            }
            db.turnDao().insert(TurnEntity("turn", "source", "RUNNING_TOOL", 0, 0, null, null))
            db.toolCallDao().insert(
                ToolCallEntity("archive", "turn", "wire", "session.archive", "1", "{}", "a".repeat(64), "RUNNING"),
            )
            val dao = db.sessionAccessDao()
            val grant = dao.setGrant("source", "target", true, true)
            assertEquals(
                0,
                dao.archive("source", "target", grant.revision, "turn", "archive", "session.restore", false, null),
            )
            assertEquals(
                1,
                dao.archive("source", "target", grant.revision, "turn", "archive", "session.archive", false, 100L),
            )
            assertEquals(grant, dao.grant("source", "target"))
            assertEquals(100L, db.sessionDao().byId("target")?.archivedAt)
            assertEquals("RUNNING_TOOL", db.turnDao().byId("turn")?.state)
        } finally {
            db.close()
        }
    }
}
