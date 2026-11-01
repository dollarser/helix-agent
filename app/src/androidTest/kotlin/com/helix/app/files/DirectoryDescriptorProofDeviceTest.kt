package com.helix.app.files

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real kernel descriptor identity; private owned fixtures only, no system permission or real tree grant. */
class DirectoryDescriptorProofDeviceTest {
    @Test fun descriptorProvesDirectoryAndRevocationOrRegularFileCannotBeProjected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".asrvalidation"))
        val root = File(context.filesDir, "workspaces/proof-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val child = File(root, "original").apply { mkdir() }
            val file = File(child, "keep.txt").apply { writeText("Synthetic unchanged") }

            fun uri(f: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val roots = { listOf(SharedStorageAccess.SCOPE_ID to root.toPath()) }
            var rechecks = 0
            val result = context.contentResolver.directoryPathFromDescriptor(uri(child), roots) { rechecks++ }
            assertEquals("scope:${SharedStorageAccess.SCOPE_ID}:original", result)
            assertEquals(2, rechecks)
            assertNull(context.contentResolver.directoryPathFromDescriptor(uri(file), roots) {})
            assertNull(
                context.contentResolver.directoryPathFromDescriptor(uri(child), roots) {
                    throw SecurityException("Revoked")
                },
            )
            assertEquals("Synthetic unchanged", file.readText())
            assertEquals(listOf("keep.txt"), child.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }
}
