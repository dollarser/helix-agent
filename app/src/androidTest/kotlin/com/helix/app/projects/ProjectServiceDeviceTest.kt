package com.helix.app.projects

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ProjectServiceDeviceTest {
    @Test fun liveRowsTrackCreateRenameArchiveAndRestoreWithoutMembershipChange() =
        fixture { storage ->
            runBlocking {
                val service = ProjectService(storage) { it }
                val updates = Channel<ProjectSessionState>(Channel.UNLIMITED)
                val observer = launch(Dispatchers.Default) { service.sessions.collect { updates.send(it) } }

                suspend fun expect(rows: List<ProjectSessionRow>) {
                    withTimeout(10_000) {
                        updates.receiveAsFlow().filterIsInstance<ProjectSessionState.Ready>().first { it.rows == rows }
                    }
                }
                try {
                    expect(emptyList())
                    storage.sessions.create("one", "First", null, null, 1)
                    expect(listOf(ProjectSessionRow("one", "First", false)))
                    val directory = storage.sessions.resolve("one").directoryRef
                    storage.sessions.updateDetails("one", "Renamed", directory)
                    expect(listOf(ProjectSessionRow("one", "Renamed", false)))
                    storage.sessions.archive("one", 2)
                    expect(listOf(ProjectSessionRow("one", "Renamed", true)))
                    storage.sessions.restore("one")
                    expect(listOf(ProjectSessionRow("one", "Renamed", false)))
                    storage.sessions.create("two", "Newer", null, null, 3)
                    expect(listOf(ProjectSessionRow("two", "Newer", false), ProjectSessionRow("one", "Renamed", false)))
                    assertTrue(
                        storage.projects.memberships
                            .first()
                            .isEmpty(),
                    )
                } finally {
                    observer.cancelAndJoin()
                    updates.cancel()
                }
            }
        }

    @Test fun activeTurnStillRejectsMembershipChangeUntilDurableCancellation() =
        fixture { storage ->
            runBlocking {
                val service = ProjectService(storage) { it }
                val first = service.save(null, "First", "", "", null)
                val second = service.save(null, "Second", "", "", null)
                val session = service.newSession(first, null, null)
                val directory = storage.sessions.resolve(session).directoryRef
                val turn = storage.turns.start("busy", session, System.currentTimeMillis())
                val failure = runCatching { service.assign(session, second) }.exceptionOrNull()
                assertEquals("PROJECT_SESSION_BUSY", failure?.message)
                assertEquals(first, storage.projects.forSession(session)?.id)
                val cancelling = storage.turns.updateState(turn, TurnState.CANCELLING, 0, null, null)
                storage.turns.updateState(cancelling, TurnState.CANCELLED, 0, System.currentTimeMillis(), null)
                storage.turns.collectResult(turn.id, System.currentTimeMillis())
                service.assign(session, second)
                assertEquals(second, storage.projects.forSession(session)?.id)
                assertEquals(directory, storage.sessions.resolve(session).directoryRef)
            }
        }

    @Test fun projectSessionsShareDirectoryButMembershipChangesDoNotMoveExistingSessions() =
        fixture { storage ->
            runBlocking {
                val service = ProjectService(storage) { it }
                val id = service.save(null, "Project", "", "Shared instructions", null)
                val first = service.newSession(id, null, null)
                val second = service.newSession(id, null, null)
                val original = storage.sessions.resolve(first)
                assertEquals(original.directoryRef, storage.sessions.resolve(second).directoryRef)
                service.assign(first, null)
                assertEquals(original.directoryRef, storage.sessions.resolve(first).directoryRef)
                service.delete(requireNotNull(storage.projects.find(id)))
                assertNotNull(storage.sessions.find(first))
                assertNotNull(storage.sessions.find(second))
            }
        }

    @Test fun projectHistoryIsNotLostBehindOtherProjectsRecentTurns() =
        fixture { storage ->
            storage.sessions.create("member", "Member", null, null, 1)
            storage.sessions.create("other", "Other", null, null, 1)
            complete(storage, "old", "member", 2)
            repeat(205) { complete(storage, "other-$it", "other", it.toLong() + 10) }
            val records = readProjectRecords(storage, setOf("member"))
            assertEquals(listOf("old"), records.tasks.map { it.id })
            assertTrue(readProjectRecords(storage, emptySet()).tasks.isEmpty())
            assertTrue(readProjectRecords(storage, emptySet()).files.isEmpty())
        }

    private fun complete(
        storage: HelixStorage,
        id: String,
        sessionId: String,
        time: Long,
    ) {
        val turn = storage.turns.start(id, sessionId, time)
        val cancelling = storage.turns.updateState(turn, TurnState.CANCELLING, 0, null, null)
        storage.turns.updateState(cancelling, TurnState.CANCELLED, 0, time + 1, null)
        storage.turns.collectResult(id, time + 2)
    }

    private fun fixture(block: (HelixStorage) -> Unit) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "project-service-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        val storage = HelixStorage.open(context, name, File(directory, "content"))
        try {
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
