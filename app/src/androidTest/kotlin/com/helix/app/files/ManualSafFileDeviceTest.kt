package com.helix.app.files

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeGrantCheck
import com.helix.feature.files.SafTreeGrantFacts
import com.helix.feature.files.SafTreeScopeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real ContentResolver/DocumentsContract calls; grant state is controlled independently. */
class ManualSafFileDeviceTest {
    @Test fun treeFilesAndDirectoriesCanBeCreatedCopiedMovedAndDeleted() {
        withBackend { backend, ops, id ->
            ops.mkdir(id, "", "source")
            backend.create("source/a.txt", false)
            backend.write("source/a.txt").use { it.write("SAF byte verification".toByteArray()) }
            ops.mkdir(id, "source", "empty")
            ops.transfer(id, "source", id, "copy", false, false)
            assertEquals("SAF byte verification", backend.read("copy/a.txt").bufferedReader().use { it.readText() })
            assertTrue(backend.stat("copy/empty")!!.directory)
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                ops.transfer(id, "source", id, "copy", false, false)
            }
            ops.transfer(id, "copy", id, "renamed", true, false)
            assertFalse(ops.exists(id, "copy"))
            ops.transfer(id, "source/a.txt", id, "renamed/a.txt", true, true)
            assertFalse(ops.exists(id, "source/a.txt"))
            ops.delete(id, "renamed")
            assertFalse(ops.exists(id, "renamed"))
            ops.delete(id, "source")
        }
    }

    @Test fun readOnlyOrRevokedGrantCannotMutateExistingDocuments() {
        withBackend { backend, ops, id ->
            backend.create("original.txt", false)
            backend.write("original.txt").use { it.write("keep".toByteArray()) }
            writable = false
            assertFalse(ops.canWrite(id))
            assertThrows(Exception::class.java) { ops.delete(id, "original.txt") }
            assertThrows(Exception::class.java) { ops.mkdir(id, "", "denied") }
            assertEquals("keep", backend.read("original.txt").bufferedReader().use { it.readText() })
            live = false
            assertThrows(Exception::class.java) { backend.read("original.txt") }
            live = true
            writable = true
            ops.delete(id, "original.txt")
        }
    }

    @Test fun unsupportedRenameRetainsTheSource() {
        withBackend { backend, ops, id ->
            backend.create("no-rename.txt", false)
            backend.write("no-rename.txt").use { it.write("preserve".toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) {
                ops.transfer(id, "no-rename.txt", id, "new.txt", true, false)
            }
            assertEquals("preserve", backend.read("no-rename.txt").bufferedReader().use { it.readText() })
            assertFalse(ops.exists(id, "new.txt"))
        }
    }

    private var writable = true
    private var live = true

    private fun withBackend(block: (ManualFileBackend, ManualFileOperations, String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val authority = "${instrumentation.context.packageName}.manualfiles"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val registry = File(context.cacheDir, "manual-grants-${UUID.randomUUID()}.json")
        val grants = SafGrantStore(registry.toPath())
        val id = grants.grant(tree.toString(), "Fixture").scopeId
        val service =
            SafTreeScopeService(
                grants,
                SafTreeGrantCheck {
                    SafTreeGrantFacts(live, authority, "root", true, writable)
                },
            )
        val backend = SafManualFileBackend(context.contentResolver, grants, service, id)
        val ops = ManualFileOperations({ backend }, { live && writable })
        try {
            block(backend, ops, id)
        } finally {
            writable = true
            live = true
            backend.children("").forEach { ops.delete(id, it) }
            registry.delete()
        }
    }
}
