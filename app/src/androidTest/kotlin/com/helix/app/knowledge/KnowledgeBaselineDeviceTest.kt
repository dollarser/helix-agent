package com.helix.app.knowledge

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.knowledge.KnowledgeDatabase
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Fresh version-1 baselines reopen records; no migration chain or daily database is used. */
class KnowledgeBaselineDeviceTest {
    @get:Rule val main =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            com.helix.core.storage.HelixDatabase::class.java,
        )

    @get:Rule val library =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            KnowledgeDatabase::class.java,
        )

    @Test fun currentMainBaselineReopensConversationRecords() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "knowledge-migration-${UUID.randomUUID()}.db"
            try {
                main.createDatabase(name, 1).use {
                    it.execSQL(
                        "INSERT INTO sessions(id,title,providerId,modelId,createdAt,archivedAt,directoryRef," +
                            "lastActivityAt,searchTitle) VALUES " +
                            "('retained','Synthetic',NULL,NULL,1,NULL,NULL,1,'synthetic')",
                    )
                }
                val content = java.io.File(context.cacheDir, "content-$name")
                val storage = HelixStorage.open(context, name, content)
                try {
                    assertEquals("Synthetic", storage.sessions.resolve("retained").title)
                } finally {
                    storage.close()
                    content.deleteRecursively()
                }
                val room =
                    androidx.room.Room
                        .databaseBuilder(
                            context,
                            com.helix.core.storage.HelixDatabase::class.java,
                            name,
                        ).build()
                try {
                    val db = room.openHelper.writableDatabase
                    assertEquals(1, db.version)
                    db.query("SELECT title FROM sessions WHERE id = 'retained'").use {
                        check(it.moveToFirst())
                        assertEquals("Synthetic", it.getString(0))
                    }
                    db.query("PRAGMA table_info(session_inputs)").use {
                        var found = false
                        while (it.moveToNext()) if (it.getString(1) == "knowledgeReferencesJson") found = true
                        check(found)
                    }
                } finally {
                    room.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    @Test fun currentLibraryBaselineReopensNotesWithoutChangingTheirKind() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "knowledge-library-migration-${UUID.randomUUID()}.db"
            try {
                library.createDatabase(name, 1).use {
                    it.execSQL(
                        "INSERT INTO knowledge_notes(id,title,body,createdAt,updatedAt,state,generatedDraft," +
                            "generationState,generationTarget) VALUES " +
                            "('retained','Synthetic','Original Markdown',1,2,'REVIEWED','','IDLE','')",
                    )
                }
                val store = KnowledgeStore(context, name)
                try {
                    val note = requireNotNull(store.note("retained"))
                    assertEquals("Original Markdown", note.body)
                    assertEquals("REVIEWED", note.state)
                    assertEquals("NOTE", note.kind)
                    assertEquals("[]", note.originReferencesJson)
                } finally {
                    store.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
}
