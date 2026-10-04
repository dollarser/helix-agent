package com.helix.app.projects

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ProjectServiceDeviceTest {
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
