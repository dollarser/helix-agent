package com.helix.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executors

class DiagnosticArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private var time = 100 * DIAGNOSTIC_DAY
    private var owners: Set<String>? = emptySet()

    private fun root(): Path = temp.newFolder().toPath().resolve("diagnostics")

    private fun open(root: Path) = DiagnosticArchive(root, { owners }, { time })

    @Test fun normalExpiresButFailureAndItsContextRemain() {
        val store = open(root())
        store.append(DiagnosticKind.NORMAL, "ordinary", "ordinary")
        store.append(DiagnosticKind.NORMAL, "context", "context", "turn")
        store.append(DiagnosticKind.FAILURE, "failure", "failure", "turn")
        time += 14 * DIAGNOSTIC_DAY
        store.clean()
        val rows = store.status().rows.associateBy { it.event }
        assertNull(store.detail(rows.getValue("ordinary").id))
        assertEquals("context", store.detail(rows.getValue("context").id))
        assertEquals("failure", store.detail(rows.getValue("failure").id))
        time += 76 * DIAGNOSTIC_DAY
        store.clean()
        assertTrue(store.status().rows.all { it.cleared })
    }

    @Test fun holdProtectsWholeTaskAcrossReopenUntilReleased() {
        val root = root()
        val store = open(root)
        store.append(DiagnosticKind.FAILURE, "one", "one", "task")
        store.append(DiagnosticKind.NORMAL, "two", "two", "task")
        val row = store.status().rows.first()
        store.pin(row.id, true)
        time += 400 * DIAGNOSTIC_DAY
        val reopened = open(root)
        reopened.clean()
        assertTrue(reopened.status().rows.all { it.pinned && !it.cleared })
        reopened.pin(row.id, false)
        reopened.clean()
        assertTrue(reopened.status().rows.all { it.cleared && !it.pinned })
    }

    @Test fun unavailableProtectionSourceAndPendingTaskFailClosed() {
        val store = open(root())
        store.append(DiagnosticKind.FAILURE, "model.failure", "{}", "pending")
        time += 100 * DIAGNOSTIC_DAY
        owners = null
        store.clean()
        assertFalse(
            store
                .status()
                .rows
                .single()
                .cleared,
        )
        owners = setOf("pending")
        store.clean()
        assertFalse(
            store
                .status()
                .rows
                .single()
                .cleared,
        )
        owners = emptySet()
        store.clean()
        assertTrue(
            store
                .status()
                .rows
                .single()
                .cleared,
        )
    }

    @Test fun pressureEvictsOldNormalDetailsBeforeFailures() {
        val store = open(root())
        store.configure(DiagnosticRetention(budgetMiB = 64))
        val body = "x".repeat(1024 * 1024)
        store.append(DiagnosticKind.FAILURE, "failure", body)
        repeat(55) {
            time++
            assertTrue(store.append(DiagnosticKind.NORMAL, "ordinary", body))
        }
        val status = store.status()
        assertTrue(status.bytes < 64 * DIAGNOSTIC_MIB)
        assertTrue(status.rows.any { it.cleared && it.kind == DiagnosticKind.NORMAL })
        assertFalse(status.rows.single { it.kind == DiagnosticKind.FAILURE }.cleared)
    }

    @Test fun logBudgetRotatesSeparatelyFromOverallBudget() {
        val store = open(root())
        repeat(36) {
            time++
            assertTrue(store.append(DiagnosticKind.LOG, "runtime.log", "x".repeat(1024 * 1024)))
        }
        assertTrue(store.status().byKind.getValue(DiagnosticKind.LOG) < 32 * DIAGNOSTIC_MIB)
        assertTrue(store.status().rows.any { it.cleared })
    }

    @Test fun unknownBytesCountTowardBudgetAndAreNeverDeleted() {
        val root = root()
        val store = open(root)
        store.configure(DiagnosticRetention(budgetMiB = 64))
        val unknown = root.resolve("records/unrecognized.bin")
        java.io.RandomAccessFile(unknown.toFile(), "rw").use { it.setLength(64 * DIAGNOSTIC_MIB) }
        assertFalse(store.append(DiagnosticKind.NORMAL, "new", "new"))
        assertEquals(1L, store.status().dropped)
        assertEquals(1, store.status().unreadableFiles)
        assertTrue(Files.exists(unknown))
    }

    @Test fun noUserFilesOrDatabaseFilesAreTouched() {
        val parent = temp.newFolder().toPath()
        val userFile = parent.resolve("output.txt")
        val db = parent.resolve("main.db")
        Files.writeString(userFile, "user result")
        Files.writeString(db, "durable execution facts")
        val store = open(parent.resolve("diagnostics"))
        store.append(DiagnosticKind.NORMAL, "ordinary", "diagnostic only")
        time += 20 * DIAGNOSTIC_DAY
        store.clean()
        assertEquals("user result", Files.readString(userFile))
        assertEquals("durable execution facts", Files.readString(db))
    }

    @Test fun corruptHeaderIsRetainedAndReported() {
        val root = root()
        val store = open(root)
        store.append(DiagnosticKind.NORMAL, "ordinary", "payload")
        val id =
            store
                .status()
                .rows
                .single()
                .id
        val header = root.resolve("records/$id.json")
        Files.writeString(header, "broken")
        time += 100 * DIAGNOSTIC_DAY
        store.clean()
        assertEquals("broken", Files.readString(header))
        assertEquals("payload", Files.readString(root.resolve("records/$id.body")))
        assertTrue(store.status().unreadableFiles > 0)
    }

    @Test fun corruptedPayloadDoesNotBecomeEmptySuccess() {
        val root = root()
        val store = open(root)
        store.append(DiagnosticKind.NORMAL, "ordinary", "payload")
        val id =
            store
                .status()
                .rows
                .single()
                .id
        Files.writeString(root.resolve("records/$id.body"), "changed")
        assertTrue(runCatching { store.detail(id) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun symlinkCannotRedirectInspectionOrCleanup() {
        val root = root()
        val store = open(root)
        store.append(DiagnosticKind.NORMAL, "ordinary", "payload")
        val id =
            store
                .status()
                .rows
                .single()
                .id
        val body = root.resolve("records/$id.body")
        val outside = temp.newFile().toPath()
        Files.writeString(outside, "outside")
        Files.delete(body)
        Files.createSymbolicLink(body, outside)
        assertTrue(runCatching { store.detail(id) }.isFailure)
        time += 100 * DIAGNOSTIC_DAY
        store.clean()
        assertEquals("outside", Files.readString(outside))
    }

    @Test fun abandonedPublicationHasGraceAndDoesNotRemoveLiveBody() {
        val root = root()
        val store = open(root)
        store.append(DiagnosticKind.NORMAL, "ordinary", "live")
        val body = root.resolve("records/${java.util.UUID.randomUUID()}.body")
        Files.writeString(body, "abandoned")
        Files.setLastModifiedTime(body, FileTime.fromMillis(time))
        store.clean()
        assertTrue(Files.exists(body))
        time += DIAGNOSTIC_DAY
        store.clean()
        assertFalse(Files.exists(body))
        assertEquals(
            "live",
            store.detail(
                store
                    .status()
                    .rows
                    .single()
                    .id,
            ),
        )
    }

    @Test fun interruptedSweepCanResumeWithoutLosingItsReceipt() {
        val store = open(root())
        store.append(DiagnosticKind.NORMAL, "ordinary", "payload")
        time += 20 * DIAGNOSTIC_DAY
        Thread.currentThread().interrupt()
        try {
            assertTrue(runCatching { store.clean() }.exceptionOrNull() is InterruptedException)
        } finally {
            Thread.interrupted()
        }
        assertFalse(
            store
                .status()
                .rows
                .single()
                .cleared,
        )
        store.clean()
        assertTrue(
            store
                .status()
                .rows
                .single()
                .cleared,
        )
    }

    @Test fun moreThanOneBatchCompletesAndConcurrentReadsRemainTruthful() {
        val store = open(root())
        val pool = Executors.newFixedThreadPool(3)
        try {
            val writers =
                (1..3).map {
                    pool.submit {
                        repeat(90) {
                            store.append(DiagnosticKind.NORMAL, "ordinary", "payload")
                            assertNotNull(store.status())
                        }
                    }
                }
            writers.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        time += 20 * DIAGNOSTIC_DAY
        store.clean()
        assertTrue(store.status().rows.all { it.cleared })
    }

    @Test fun policyRejectsInvalidValuesAndPersistsValidSettings() {
        assertTrue(runCatching { DiagnosticRetention(normalDays = 0) }.isFailure)
        assertTrue(runCatching { DiagnosticRetention(normalDays = 90, failureDays = 14) }.isFailure)
        assertTrue(runCatching { DiagnosticRetention(budgetMiB = 1) }.isFailure)
        val root = root()
        open(root).configure(DiagnosticRetention(7, 30, 128))
        assertEquals(DiagnosticRetention(7, 30, 128), open(root).status().policy)
    }

    @Test fun fullProtectedStorageDropsOptionalDetailWithoutDiscardingHold() {
        val root = root()
        val store = open(root)
        store.configure(DiagnosticRetention(budgetMiB = 64))
        store.append(DiagnosticKind.FAILURE, "failure", "protected")
        val row = store.status().rows.single()
        store.pin(row.id, true)
        java.io.RandomAccessFile(root.resolve("records/unrecognized.bin").toFile(), "rw").use {
            it.setLength(64 * DIAGNOSTIC_MIB)
        }
        time += 100 * DIAGNOSTIC_DAY
        assertFalse(store.append(DiagnosticKind.NORMAL, "new", "new"))
        assertEquals("protected", store.detail(row.id))
        assertTrue(
            open(root)
                .status()
                .rows
                .single()
                .pinned,
        )
    }

    @Test fun categoryLifetimesAndTombstoneExpiryAreExplicit() {
        val store = open(root())
        store.append(DiagnosticKind.ATTACHMENT, "image", "image")
        store.append(DiagnosticKind.FAILURE_ATTACHMENT, "failure.image", "image")
        store.append(DiagnosticKind.TEMPORARY, "export", "temporary")
        time += DIAGNOSTIC_DAY
        store.clean()
        assertTrue(
            store
                .status()
                .rows
                .single { it.kind == DiagnosticKind.TEMPORARY }
                .cleared,
        )
        time += 6 * DIAGNOSTIC_DAY
        store.clean()
        assertTrue(
            store
                .status()
                .rows
                .single { it.kind == DiagnosticKind.ATTACHMENT }
                .cleared,
        )
        assertFalse(
            store
                .status()
                .rows
                .single { it.kind == DiagnosticKind.FAILURE_ATTACHMENT }
                .cleared,
        )
        time += 23 * DIAGNOSTIC_DAY
        store.clean()
        assertTrue(store.status().rows.all { it.cleared })
        time += 90 * DIAGNOSTIC_DAY
        store.clean()
        assertTrue(store.status().rows.isEmpty())
    }

    @Test fun protectionReadFailureAbortsDeletionAndClockRollbackDoesNotExpire() {
        val root = root()
        val store = open(root)
        store.append(DiagnosticKind.NORMAL, "ordinary", "keep")
        val id =
            store
                .status()
                .rows
                .single()
                .id
        time -= 10 * DIAGNOSTIC_DAY
        store.clean()
        assertEquals("keep", store.detail(id))
        time += 100 * DIAGNOSTIC_DAY
        val inaccessible = DiagnosticArchive(root, { throw java.io.IOException("synthetic") }, { time })
        assertTrue(runCatching { inaccessible.clean() }.exceptionOrNull() is java.io.IOException)
        assertEquals("keep", store.detail(id))
    }

    @Test fun corruptConfigurationNeverDefaultsToAnUnprotectedPolicy() {
        val root = root()
        open(root).configure(DiagnosticRetention())
        Files.writeString(root.resolve("config.json"), "{\"formatVersion\":2}")
        assertTrue(runCatching { open(root) }.isFailure)
        assertEquals("{\"formatVersion\":2}", Files.readString(root.resolve("config.json")))
    }

    @Test fun explicitInspectionIncludesOriginalOwnerAndContent() {
        val store = open(root())
        store.append(DiagnosticKind.FAILURE, "model.failure", "synthetic", "original-turn")
        val row = store.status().rows.single()
        val inspected =
            kotlinx.serialization.json.Json
                .parseToJsonElement(store.inspect(row.id)!!)
                .toString()
        assertTrue(inspected.contains("original-turn"))
        assertTrue(inspected.contains(row.id))
        assertTrue(inspected.contains("synthetic"))
        time += 100 * DIAGNOSTIC_DAY
        store.clean()
        assertNull(store.inspect(row.id))
    }

    @Test fun entryLimitReclaimsOldUnprotectedDetailsInsteadOfDroppingNewFailures() {
        val store = DiagnosticArchive(root(), { emptySet() }, { time }, recordLimit = 8)
        repeat(7) {
            time++
            assertTrue(store.append(DiagnosticKind.NORMAL, "ordinary", "old"))
        }
        repeat(5) {
            time++
            assertTrue(store.append(DiagnosticKind.FAILURE, "new.failure", "failure"))
        }
        assertEquals(0L, store.status().dropped)
        assertTrue(
            store
                .status()
                .rows
                .filter { it.kind == DiagnosticKind.FAILURE }
                .all { !it.cleared },
        )
    }
}
