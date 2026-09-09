package com.helix.app.goal

import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScopedEditedArtifactReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun liveScopeRevocationPreventsSnapshotRead() {
        val root = temporary.newFolder()
        val file =
            File(root, "output/result.txt").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("edited")
            }
        var enabled = true
        val source =
            ScopedEditedArtifactReader(
                WorkspaceArtifactStore(
                    ScopeRootResolver {
                        if (!enabled || it != "app") throw ScopeNotAvailable("scope unavailable")
                        root.toPath()
                    },
                ),
            )
        val expected = expectation(file.readBytes())
        assertArrayEquals(file.readBytes(), source.read(expected))
        enabled = false
        assertThrows(GoalEvidenceRejected::class.java) { source.read(expected) }
    }

    @Test fun growthAfterEditCannotVerifyAnUnchangedPrefix() {
        val root = temporary.newFolder()
        val file =
            File(root, "output/result.txt").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("edited")
            }
        val source = ScopedEditedArtifactReader(WorkspaceArtifactStore(ScopeRootResolver { root.toPath() }))
        val expected = expectation(file.readBytes())
        file.appendBytes(ByteArray(1_048_576))
        assertThrows(IllegalArgumentException::class.java) { source.read(expected) }
    }

    @Test fun symlinkCannotRedirectTheAuthorizedPath() {
        val root = temporary.newFolder()
        File(root, "output").mkdirs()
        val outside = temporary.newFile().apply { writeText("edited") }
        java.nio.file.Files
            .createSymbolicLink(File(root, "output/result.txt").toPath(), outside.toPath())
        val source = ScopedEditedArtifactReader(WorkspaceArtifactStore(ScopeRootResolver { root.toPath() }))
        assertThrows(GoalEvidenceRejected::class.java) {
            source.read(expectation(outside.readBytes()))
        }
    }

    private fun expectation(bytes: ByteArray) =
        EditedArtifactContent.parse(
            "edit",
            "1",
            """{"path":"scope:app:output/result.txt"}""",
            """{"path":"scope:app:output/result.txt","sizeBytes":${bytes.size},"sha256":"${FileContentStore.sha256Hex(
                bytes,
            )}"}""",
        )
}
