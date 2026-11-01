package com.helix.app.files

import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class WorkspaceAccessReportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun actualPathProbeListsOriginalDirectoryWithoutCreatingProbeOrMirror() {
        val root = temp.newFolder().toPath()
        java.nio.file.Files
            .writeString(root.resolve("original.txt"), "original")
        val roots = ScopeRootResolver { root }
        val backend = NioManualFileBackend("app", roots, false) {}
        val manual = ManualFileOperations({ backend }, { true })
        val service = FileManagerService(WorkspaceArtifactStore(roots), roots, "app", manual = manual)
        val report = service.inspectWorkspace("scope:app:.")
        assertTrue(report.directPath && report.directoryReadable && report.writeAdvertised)
        assertEquals(listOf("original.txt"), backend.children(""))
    }

    @Test fun documentBackendRemainsInPlaceWithoutGuessingPathOrTestingWrites() {
        val roots = ScopeRootResolver { throw ScopeNotAvailable("URI has no POSIX path") }
        var readable = true
        val backend =
            object : ManualFileBackend {
                override fun validateMutation(path: String) = error("No mutations")

                override fun stat(path: String) = ManualFileInfo(true, 0)

                override fun children(path: String): List<String> {
                    if (!readable) throw IOException("Grant revoked")
                    return listOf("document.txt")
                }

                override fun read(path: String) = error("Do not read bodies")

                override fun create(
                    path: String,
                    directory: Boolean,
                ) = error("No mutations")

                override fun write(path: String) = error("No mutations")

                override fun rename(
                    path: String,
                    destination: String,
                ) = error("No mutations")

                override fun delete(path: String) = error("No mutations")
            }
        val service =
            FileManagerService(
                WorkspaceArtifactStore(roots),
                roots,
                "app",
                manual = ManualFileOperations({ backend }, { false }),
            )
        val report = service.inspectWorkspace("scope:saf-fixture:.")
        assertEquals("SAF", report.backend)
        assertTrue(report.directoryReadable)
        assertFalse(report.directPath || report.writeAdvertised)
        readable = false
        assertThrows(IOException::class.java) { service.inspectWorkspace("scope:saf-fixture:.") }
    }
}
