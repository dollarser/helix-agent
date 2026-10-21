package com.helix.app.proot

import com.helix.runtime.proot.ipc.ProotJobCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinuxLiveWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun call() =
        LinuxRunTool.ParsedLinuxCall(
            "call",
            "turn",
            "session",
            ProotJobCommand.Script("true"),
            "",
            emptyMap(),
            emptyList(),
            null,
            1000,
            workspaceReference = "scope:ws:project",
        )

    @Test fun bindsTheOriginalSelectedDirectoryAndAllowsOrdinaryArtifactPaths() {
        val root = temporary.newFolder("project").canonicalFile
        val resolver = LinuxLiveWorkspace({ root }, listOf(root))
        val prepared = resolver.prepare(call().copy(artifactPaths = listOf("report.docx", "src/main.py")))
        assertEquals(root.path, prepared.resolvedWorkspacePath)
        assertEquals(
            "scope:ws:project/report.docx",
            ProotLiveArtifacts.reference(prepared.workspaceReference!!, "report.docx"),
        )
        assertFalse(prepared.snapshot)
        assertFalse(File(root, "output").exists())
    }

    @Test fun unsupportedBackendNeverSilentlyCreatesASnapshot() {
        val root = temporary.newFolder("project").canonicalFile
        val resolver = LinuxLiveWorkspace({ error("SAF has no POSIX path") }, listOf(root))
        assertThrows(IllegalStateException::class.java) { resolver.prepare(call()) }
        assertEquals(call().copy(snapshot = true), resolver.prepare(call().copy(snapshot = true)))
    }

    @Test fun rejectsExternalInputsInternalArtifactsAndMissingCwd() {
        val root = temporary.newFolder("project").canonicalFile
        val resolver = LinuxLiveWorkspace({ root }, listOf(root))
        assertThrows(IllegalArgumentException::class.java) {
            resolver.prepare(call().copy(inputReferences = listOf("scope:other:a.txt")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.prepare(call().copy(artifactPaths = listOf(".helix/private")))
        }
        assertThrows(IllegalArgumentException::class.java) { resolver.prepare(call().copy(cwd = "missing")) }
    }
}
