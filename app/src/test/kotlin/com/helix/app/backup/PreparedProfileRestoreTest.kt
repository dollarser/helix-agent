package com.helix.app.backup

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreparedProfileRestoreTest {
    private fun prepared(directory: File) =
        PreparedProfileRestore(
            directory,
            emptyMap(),
            JsonObject(emptyMap()),
            JsonObject(emptyMap()),
            emptyList(),
            emptyMap(),
            false,
        )

    @Test fun cancelDeletesOnlyUnqueuedStageAndDuplicateConfirmationIsRejected() {
        val root = Files.createTempDirectory("prepared-profile").toFile()
        try {
            val cancelled = File(root, "cancelled").apply { check(mkdirs()) }
            prepared(cancelled).close()
            assertFalse(cancelled.exists())
            val stage = File(root, "queued").apply { check(mkdirs()) }
            val pending = prepared(stage)
            pending.beginQueue()
            assertThrows(IllegalStateException::class.java) { pending.beginQueue() }
            pending.close()
            assertTrue(stage.exists())
            pending.endQueue(preserve = true)
            pending.close()
            assertTrue(stage.exists())
            assertThrows(IllegalStateException::class.java) { pending.beginQueue() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun failedQueueAfterCancellationReleasesUncommittedStage() {
        val root = Files.createTempDirectory("failed-profile").toFile()
        val pending = prepared(root)
        pending.beginQueue()
        pending.close()
        pending.endQueue(preserve = false)
        assertFalse(root.exists())
    }
}
