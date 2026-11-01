package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LocalDirectoryProofTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun descriptorIdentityAllowsRootOrChosenChildWithoutCopyingOrGuessingUri() {
        val root = temporary.newFolder().toPath().toRealPath()
        val child = Files.createDirectory(root.resolve("project"))
        Files.writeString(child.resolve("original"), "unchanged")
        val roots = listOf(SharedStorageAccess.SCOPE_ID to root)
        val identity = 7L to 11L
        assertEquals(
            "project",
            FileScopePath
                .fromModelReference(
                    verifiedSharedDirectory(
                        roots,
                        child.toString(),
                        identity,
                    ) {
                        identity
                    },
                ).relativePath,
        )
        assertEquals(
            "",
            FileScopePath
                .fromModelReference(
                    verifiedSharedDirectory(roots, root.toString(), identity) {
                        identity
                    },
                ).relativePath,
        )
        assertEquals(listOf("original"), child.toFile().list()!!.toList())
        assertThrows(IllegalArgumentException::class.java) {
            verifiedSharedDirectory(roots, child.toString(), identity) {
                7L to
                    12L
            }
        }
    }

    @Test fun deniedRevokedOutsideAndSymlinkCannotBecomeDirectAlternatives() {
        val root = temporary.newFolder().toPath().toRealPath()
        val outside = temporary.newFolder().toPath().toRealPath()
        val roots = listOf(SharedStorageAccess.SCOPE_ID to root)
        val identity = 1L to 2L
        assertThrows(IllegalStateException::class.java) {
            verifiedSharedDirectory(emptyList(), root.toString(), identity) { identity }
        }
        assertThrows(IllegalStateException::class.java) {
            verifiedSharedDirectory(roots, outside.toString(), identity) { identity }
        }
        val link = Files.createSymbolicLink(root.resolve("link"), outside)
        assertThrows(IllegalArgumentException::class.java) {
            verifiedSharedDirectory(roots, link.toString(), identity) { identity }
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifiedSharedDirectory(roots, "content://provider/tree/root", identity) { identity }
        }
    }
}
