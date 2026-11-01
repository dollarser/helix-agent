package com.helix.app.files

import com.helix.core.workspace.ScopeRootResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class DirectorySyncTest {
    @get:Rule val temp = TemporaryFolder()
    private val left = SyncRoot("app", "left")
    private val right = SyncRoot("saf-synthetic", "right")

    @Test fun bidirectionalCopiesChangesAndKeepsCommonBaselineAcrossRestart() {
        val f = Fixture()
        f.put("left/a.txt", "one")
        f.put("right/sub/b.txt", "two")
        val rule = f.service.create(left, right)
        f.service.run(rule.id)
        assertEquals(
            "COMPLETE",
            f.service
                .list()
                .single()
                .status,
        )
        assertEquals("one", f.text("right/a.txt"))
        assertEquals("two", f.text("left/sub/b.txt"))
        f.put("right/a.txt", "changed")
        f.service = f.open()
        f.service.run(rule.id)
        assertEquals("changed", f.text("left/a.txt"))
        assertEquals(
            "COMPLETE",
            f.service
                .list()
                .single()
                .status,
        )
    }

    @Test fun simultaneousEditsAndDeletionPauseWithoutOverwriting() {
        val f = Fixture()
        f.put("left/a", "base")
        val rule = f.service.create(left, right)
        f.service.run(rule.id)
        f.put("left/a", "mine")
        f.put("right/a", "theirs")
        f.service.enable(rule.id, true)
        f.service.run(rule.id)
        assertEquals(
            "CONFLICT",
            f.service
                .list()
                .single()
                .status,
        )
        assertFalse(
            f.service
                .list()
                .single()
                .enabled,
        )
        assertEquals("mine", f.text("left/a"))
        assertEquals("theirs", f.text("right/a"))
        f.put("left/a", "same")
        f.put("right/a", "same")
        f.service.run(rule.id)
        Files.delete(f.root.resolve("right/a"))
        f.service.run(rule.id)
        assertEquals(
            "CONFLICT",
            f.service
                .list()
                .single()
                .status,
        )
        assertEquals("same", f.text("left/a"))
    }

    @Test fun permissionRevocationCancellationAndColdRecoveryNeverReplay() {
        val f = Fixture()
        f.put("left/a", "one")
        val rule = f.service.create(left, right)
        f.service.run(rule.id, { true })
        assertEquals(
            "PAUSED",
            f.service
                .list()
                .single()
                .status,
        )
        assertFalse(Files.exists(f.root.resolve("right/a")))
        f.allowed.set(false)
        f.service.run(rule.id)
        assertEquals(
            "REVIEW",
            f.service
                .list()
                .single()
                .status,
        )
        assertFalse(Files.exists(f.root.resolve("right/a")))
        f.saved = f.saved!!.replace("REVIEW", "RUNNING")
        f.allowed.set(true)
        f.service = f.open()
        assertEquals(
            "REVIEW",
            f.service
                .list()
                .single()
                .status,
        )
        assertFalse(
            f.service
                .list()
                .single()
                .enabled,
        )
        assertFalse(Files.exists(f.root.resolve("right/a")))
    }

    @Test fun checkedTransferRejectsLateDestinationEditAndRetainsBoth() {
        val f = Fixture()
        f.put("left/a", "new")
        f.put("right/a", "old")
        val scan = DirectorySyncScan { f.backend }
        val source = scan.scan(left) { false }.getValue("a").hash
        val target = scan.scan(right) { false }.getValue("a").hash
        f.put("right/a", "edited after scan")
        assertThrows(IllegalStateException::class.java) {
            f.manual.transfer(
                "app",
                "left/a",
                "saf-synthetic",
                "right/a",
                false,
                true,
                expected = TransferExpectation(source, target),
            )
        }
        assertEquals("edited after scan", f.text("right/a"))
        assertEquals("new", f.text("left/a"))
    }

    @Test fun unsafePathsOverlapsAndTypesCannotBecomeSuccessfulSync() {
        assertThrows(IllegalArgumentException::class.java) { SyncRoot("app", "../secret") }
        val f = Fixture()
        f.put("left/a", "file")
        Files.createDirectory(f.root.resolve("right/a"))
        val rule = f.service.create(left, right)
        assertThrows(IllegalArgumentException::class.java) { f.service.create(left, right) }
        f.service.run(rule.id)
        assertEquals(
            "CONFLICT",
            f.service
                .list()
                .single()
                .status,
        )
        assertTrue(Files.isDirectory(f.root.resolve("right/a")))
    }

    private inner class Fixture {
        val root = temp.newFolder().toPath()
        val allowed = AtomicBoolean(true)
        val backend = NioManualFileBackend("synthetic", ScopeRootResolver { root }, false) { check(allowed.get()) }
        val manual =
            ManualFileOperations({ backend }, { allowed.get() }, ManualTransferJournal(root.resolve("journal")))
        var saved: String? = null
        var service = open()

        init {
            Files.createDirectories(root.resolve("left"))
            Files.createDirectories(root.resolve("right"))
        }

        fun open() = DirectorySyncService(manual, { saved }, { saved = it }, { _, _ -> })

        fun put(
            path: String,
            text: String,
        ) {
            Files.createDirectories(root.resolve(path).parent)
            Files.writeString(root.resolve(path), text)
        }

        fun text(path: String): String = Files.readString(root.resolve(path))
    }
}
