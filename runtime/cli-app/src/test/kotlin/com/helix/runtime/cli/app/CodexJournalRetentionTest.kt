package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CodexJournalRetentionTest {
    @Test fun pressureReclaimsOldestAcknowledgedRecordButKeepsUnacknowledgedEvidence() {
        val root = Files.createTempDirectory("cli-journal-pressure").toFile()
        try {
            val store = CodexModelJobStore(root)
            val now = System.currentTimeMillis()
            repeat(CodexModelJobStore.MAX_ENTRIES) { index ->
                store.put(record(index, if (index == 0) null else now - index))
            }
            assertTrue(store.canAcceptNew())
            assertNotNull(store.load(id(0)))
            assertNull(store.load(id(CodexModelJobStore.MAX_ENTRIES - 1)))
            assertNotNull(store.load(id(1)))
            assertEquals(CodexModelJobStore.MAX_ENTRIES - 1, root.walkTopDown().count { it.name == "record.json" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun expiredAcknowledgedRecordIsRemovedWhileFreshAndUnacknowledgedRecordsStay() {
        val root = Files.createTempDirectory("cli-journal-expiry").toFile()
        try {
            val store = CodexModelJobStore(root)
            store.put(record(0, 3))
            store.put(record(1, null))
            store.put(record(2, System.currentTimeMillis()))
            assertTrue(store.canAcceptNew())
            assertNull(store.load(id(0)))
            assertNotNull(store.load(id(1)))
            assertNotNull(store.load(id(2)))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun id(index: Int) = "job_${index.toString(16).padStart(12, '0')}"

    private fun record(
        index: Int,
        acknowledged: Long?,
    ) = CodexModelJobRecord(
        id(index),
        "a".repeat(64),
        CodexModelJobState.SUCCEEDED,
        1,
        2,
        model = "fixture",
        outputSha256 = "b".repeat(64),
        reconciledAtEpochMillis = acknowledged,
    )
}
