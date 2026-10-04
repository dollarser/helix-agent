package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.entity.ProjectEntity
import com.helix.core.storage.entity.ProjectSessionEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.entity.WorkspaceEntity
import com.helix.core.storage.repository.ProjectRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** On-disk Room contracts; compilation alone is not a device pass. */
class ProjectPersistenceDeviceTest {
    @Test fun reopenRetainsIdentityAndDeleteRetainsSessionsAndFiles() =
        fixture { context, name, directory ->
            val project = project()
            val file = File(directory, "keep.txt").apply { writeText("user content") }
            withDatabase(context, name) { db ->
                seed(db)
                db.projectDao().insert(project)
                db.projectDao().bind(ProjectSessionEntity("session", project.id))
            }
            withDatabase(context, name) { db ->
                assertEquals(project, db.projectDao().find(project.id))
                assertEquals(project.id, db.projectDao().forSession("session")?.id)
                assertTrue(db.workspaceDao().retentionReferences("workspace", "scope:workspace:").retained)
                assertEquals(1, db.projectDao().delete(project.id, 1))
                assertNull(db.projectDao().forSession("session"))
                assertNotNull(db.sessionDao().byId("session"))
                assertNotNull(db.workspaceDao().find("workspace"))
                assertEquals("user content", file.readText())
            }
        }

    @Test fun activeTurnRejectsMembershipChangeAndAuditFailureRollsBack() =
        fixture { context, name, _ ->
            withDatabase(context, name) { db ->
                seed(db)
                val project = project()
                db.projectDao().insert(project)
                db.projectDao().bind(ProjectSessionEntity("session", project.id))
                db.turnDao().insert(TurnEntity("turn", "session", "RUNNING_TOOL", 0, 1, null, null))
                var auditFails = false
                val repository =
                    ProjectRepository(
                        db.projectDao(),
                        { block -> db.runInTransaction(block) },
                        { true },
                        { _, _ -> check(!auditFails) },
                    )
                assertTrue(db.projectDao().busy("session"))
                assertThrows(IllegalStateException::class.java) { repository.assign("session", null) }
                assertThrows(IllegalStateException::class.java) { repository.delete(project.id, 1) }
                db.openHelper.writableDatabase.execSQL("UPDATE turns SET state = 'COMPLETED' WHERE id = 'turn'")
                assertFalse(db.projectDao().busy("session"))
                auditFails = true
                assertThrows(IllegalStateException::class.java) { repository.assign("session", null) }
                assertEquals(project.id, db.projectDao().forSession("session")?.id)
                auditFails = false
                repository.assign("session", null)
                assertNull(db.projectDao().forSession("session"))
            }
        }

    @Test fun projectToolRequiresLiveCallAndPendingCallsKeepMembershipStable() =
        fixture { context, name, _ ->
            withDatabase(context, name) { db ->
                seed(db)
                val project = project()
                db.projectDao().insert(project)
                db.projectDao().bind(ProjectSessionEntity("session", project.id))
                db.turnDao().insert(TurnEntity("turn", "session", "RUNNING_TOOL", 0, 1, null, null))
                db.toolCallDao().insert(
                    ToolCallEntity("call", "turn", "provider-call", "memory.read", "1", "{}", "hash", "RUNNING"),
                )
                assertEquals(project.id, db.projectDao().forTool("session", "turn", "call")?.id)
                assertNull(db.projectDao().forTool("another", "turn", "call"))
                db.openHelper.writableDatabase.execSQL("UPDATE turns SET state = 'INTERRUPTED' WHERE id = 'turn'")
                assertNull(db.projectDao().forTool("session", "turn", "call"))
                listOf("PENDING", "AWAITING_APPROVAL", "RUNNING", "INTERRUPTED", "NEEDS_REVIEW").forEach { state ->
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE tool_calls SET state = ? WHERE id = 'call'",
                        arrayOf(state),
                    )
                    assertTrue(db.projectDao().busy("session"))
                }
                db.openHelper.writableDatabase.execSQL("UPDATE tool_calls SET state = 'COMPLETED' WHERE id = 'call'")
                assertFalse(db.projectDao().busy("session"))
                assertNull(db.projectDao().forTool("session", "turn", "call"))
            }
        }

    private fun seed(db: HelixDatabase) {
        db.workspaceDao().insert(
            WorkspaceEntity("workspace", "PATH", "fixture", "identity", "EXTERNAL", "READY", null, null, 1),
        )
        db.sessionDao().insert(SessionEntity("session", "Session", null, null, 1, null))
    }

    private fun project() =
        ProjectEntity(
            "project-${UUID.randomUUID()}",
            "Project",
            "",
            "Instructions",
            "workspace",
            "",
            1,
            1,
            1,
            null,
        )

    private fun withDatabase(
        context: Context,
        name: String,
        block: (HelixDatabase) -> Unit,
    ) {
        val db = Room.databaseBuilder(context, HelixDatabase::class.java, name).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun fixture(block: (Context, String, File) -> Unit) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "projects-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        try {
            block(context, name, directory)
        } finally {
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
