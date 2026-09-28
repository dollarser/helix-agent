package com.helix.app.storage

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class PrivateStorageScanTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun countsNestedFilesOnceAndDoesNotModifyThem() {
        val root = temp.newFolder().toPath()
        val child = Files.createDirectory(root.resolve("child"))
        Files.write(root.resolve("one"), byteArrayOf(1, 2))
        Files.write(child.resolve("two"), byteArrayOf(3, 4, 5))
        val result = PrivateStorageScan.measure(listOf(root, root))
        assertTrue(result.complete)
        assertEquals(5L, result.bytes)
        assertEquals(listOf<Byte>(3, 4, 5), Files.readAllBytes(child.resolve("two")).toList())
    }

    @Test fun missingRootIsEmptyButSymlinksAreExcludedAndMarkedPartial() {
        val root = temp.newFolder().toPath()
        val outside = temp.newFile().apply { writeText("outside") }.toPath()
        assertEquals(PrivateStorageUsage(0, true), PrivateStorageScan.measure(listOf(root.resolve("missing"))))
        Files.createSymbolicLink(root.resolve("link"), outside)
        val result = PrivateStorageScan.measure(listOf(root))
        assertFalse(result.complete)
        assertEquals(0L, result.bytes)
        assertEquals("outside", outside.toFile().readText())
        assertFalse(PrivateStorageScan.measure(listOf(root.resolve("link"))).complete)
    }

    @Test fun entryLimitReturnsPartialAndNeverPretendsToKnowTotal() {
        val root = temp.newFolder().toPath()
        repeat(4) { Files.write(root.resolve("$it"), byteArrayOf(1)) }
        val result = PrivateStorageScan.measure(listOf(root), maxEntries = 2)
        assertFalse(result.complete)
        assertEquals(1L, result.bytes)
    }

    @Test fun cancellationPropagatesInsteadOfReportingZero() {
        val root = temp.newFolder().toPath()
        assertThrows(CancellationException::class.java) {
            PrivateStorageScan.measure(listOf(root)) { throw CancellationException("stop") }
        }
    }

    @Test fun directoryDepthLimitIsExplicitlyPartial() {
        val root = temp.newFolder().toPath()
        var child = root
        repeat(65) { child = Files.createDirectory(child.resolve("d")) }
        Files.write(child.resolve("last"), byteArrayOf(1))
        assertEquals(PrivateStorageUsage(0, false), PrivateStorageScan.measure(listOf(root)))
    }
}
