package com.helix.app.infrastructure.conversation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.infrastructure.chat.ConversationReferenceResolver
import com.helix.app.knowledge.KnowledgeFixtureDocuments
import com.helix.app.knowledge.KnowledgeNoteService
import com.helix.app.knowledge.KnowledgeReferenceService
import com.helix.core.model.TurnState
import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Room snapshots and isolated in-memory document publication, never the user's library. */
class ConversationReferenceKnowledgeDeviceTest {
    @Test fun referencesKeepTheirCapturedCeilingAndRejectChangedCapturedHistory() =
        Fixture().use { f ->
            f.storage.messages.append("source", "source", null, "USER", "TEXT", "captured")
            val reader = ConversationReferenceResolver(f.storage)
            val reference = reader.prepare("session", "source", ConversationReferenceKind.HISTORY)
            f.storage.messages.append("future", "source", null, "USER", "TEXT", "not admitted")
            assertTrue(reader.matches(reference))
            f.storage.messages.supersedeFrom(
                "source",
                f.storage.messages
                    .resolve("source")
                    .sequence,
                "revision",
            )
            assertFalse(reader.matches(reference))
            assertFalse(reader.matches(reference.copy(sourceSessionId = "missing")))
        }

    @Test fun onlyOwnedUnsupersededTerminalRepliesPublishAndRepeatedSaveIsIdempotent() =
        Fixture().use { f ->
            runBlocking {
                f.notes.recovery.join()
                f.reply()
                assertFalse(f.knowledge.saveReply("source", "reply"))
                assertFalse(f.knowledge.saveReply("session", "question"))
                assertFalse(f.knowledge.saveReply("session", "reply"))
                assertTrue(
                    f.library.notes.value
                        .isEmpty(),
                )
                f.complete()
                assertTrue(f.knowledge.saveReply("session", "reply"))
                assertTrue(f.knowledge.saveReply("session", "reply"))
                val note =
                    f.library.notes.value
                        .single()
                assertEquals("Captured reply", note.body)
                assertEquals("session", note.originSessionId)
                assertEquals("reply", note.originMessageId)
                f.storage.messages.supersedeFrom(
                    "session",
                    f.storage.messages
                        .resolve("reply")
                        .sequence,
                    "revision",
                )
                assertFalse(f.knowledge.saveReply("session", "reply"))
                assertEquals(
                    note,
                    f.library.notes.value
                        .single(),
                )
            }
        }

    @Test fun notePublicationDoesNotHoldRoomAndCannotRetargetItsCapturedReply() =
        Fixture().use { f ->
            runBlocking {
                f.notes.recovery.join()
                f.reply()
                f.complete()
                val pool = Executors.newSingleThreadExecutor()
                f.workersStopped = false
                try {
                    f.documents.beforeSave = {
                        pool
                            .submit {
                                f.storage.withTransaction {
                                    val sequence =
                                        f.storage.messages
                                            .resolve("reply")
                                            .sequence
                                    f.storage.messages.supersedeFrom("session", sequence, "revision")
                                    f.storage.messages.append(
                                        "later",
                                        "session",
                                        "turn",
                                        "ASSISTANT",
                                        "TEXT",
                                        "Later answer",
                                    )
                                }
                            }.get(5, TimeUnit.SECONDS)
                    }
                    assertTrue(f.knowledge.saveReply("session", "reply"))
                    assertEquals(
                        "Captured reply",
                        f.library.notes.value
                            .single()
                            .body,
                    )
                    assertEquals(
                        "reply",
                        f.library.notes.value
                            .single()
                            .originMessageId,
                    )
                    assertEquals(
                        "revision",
                        f.storage.messages
                            .resolve("reply")
                            .supersededBy,
                    )
                } finally {
                    pool.shutdownNow()
                    f.workersStopped = pool.awaitTermination(5, TimeUnit.SECONDS)
                    check(f.workersStopped) { "Fixture workers did not stop; private fixture retained" }
                }
            }
        }

    private class PublicationDocuments : DocumentStorage {
        private val documents = KnowledgeFixtureDocuments()
        var beforeSave: (() -> Unit)? = null

        override fun references(directory: String) = documents.references(directory)

        override fun list(directory: String) = documents.list(directory)

        override fun read(
            reference: String,
            maxBytes: Int,
        ) = documents.read(reference, maxBytes)

        override fun delete(previous: DocumentStorage.File) = documents.delete(previous)

        override fun save(
            directory: String,
            name: String,
            bytes: ByteArray,
            previous: DocumentStorage.File?,
            mimeType: String,
        ): DocumentStorage.File {
            beforeSave?.also { beforeSave = null }?.invoke()
            return documents.save(directory, name, bytes, previous, mimeType)
        }
    }

    private class Fixture : AutoCloseable {
        var workersStopped = true
        private val context =
            ApplicationProvider.getApplicationContext<Context>().also {
                assertEquals(Application::class.java, it.javaClass)
                assertFalse(it.packageName == "com.helix.agent.developer")
            }
        private val name = "reference-knowledge-${UUID.randomUUID()}.db"
        private val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        val documents = PublicationDocuments()
        val library = KnowledgeStore(documents, KnowledgeFixtureDocuments())
        val notes = KnowledgeNoteService(library)
        val knowledge =
            RoomConversationKnowledge(storage, KnowledgeReferenceService(library), notes) { _, _ ->
                "Fixture note"
            }

        init {
            storage.sessions.create("session", "Session", null, null, 1)
            storage.sessions.create("source", "Source", null, null, 1)
        }

        fun reply() {
            storage.turns.start("turn", "session", 2)
            storage.messages.append("question", "session", "turn", "USER", "TEXT", "Question")
            storage.messages.append("reply", "session", "turn", "ASSISTANT", "TEXT", "Captured reply")
        }

        fun complete() {
            var turn = storage.turns.resolve("turn")
            for (state in listOf(TurnState.BUILDING_CONTEXT, TurnState.WAITING_MODEL, TurnState.RECEIVING_MODEL)) {
                turn = storage.turns.updateState(turn, state, 0, null, null)
            }
            storage.turns.updateState(turn, TurnState.COMPLETED, 0, 3, null)
        }

        override fun close() {
            // A failed termination check must not close or delete resources still used by a worker.
            if (!workersStopped) return
            runBlocking { notes.recovery.join() }
            notes.close()
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
