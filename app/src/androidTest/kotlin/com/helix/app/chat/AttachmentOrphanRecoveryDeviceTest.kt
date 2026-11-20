package com.helix.app.chat

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.ImportStatus
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafSourceMetadata
import com.helix.feature.files.SafSourceOpener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileOutputStream
import java.nio.file.Files

/** Owned-emulator runner kills after real publication, then invokes this test in a fresh process. */
class AttachmentOrphanRecoveryDeviceTest {
    @Test fun interruptedPublicationIsRecoveredOnRealApplicationStartup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as HelixApplication
        app.appContainer // Await production startup before publishing this process's fixture.
        val root = app.filesDir.resolve("workspaces/app").toPath()
        val marker = app.noBackupFilesDir.resolve("attachment-recovery-reference")
        if (InstrumentationRegistry.getArguments().getString("recoveryPhase") == "setup") {
            val bytes = "ATTACHMENT_PUBLISHED_BEFORE_PROCESS_DEATH".toByteArray()
            val result =
                AttachmentImporter(
                    SafImportPipeline(ScopeRootResolver { root }, SafSourceOpener { bytes.inputStream() }),
                ).importAttachment(
                    workspaceScopeId = "app",
                    sourceUri = "content://synthetic/recovery",
                    reported = SafSourceMetadata(bytes.size.toLong(), "text/plain", "recovery.txt"),
                    cancel = SafCancelToken { false },
                    sink = null,
                    sessionId = "unregistered-recovery",
                )
            assertEquals(ImportStatus.COMPLETED, result.status)
            FileOutputStream(marker).use {
                it.write(requireNotNull(result.modelRef).toByteArray())
                it.fd.sync()
            }
            FileOutputStream(app.noBackupFilesDir.resolve("recovery-device-pid")).use {
                it.write(Process.myPid().toString().toByteArray())
                it.fd.sync()
            }
            Process.killProcess(Process.myPid())
            error("Process death did not occur")
        }
        val reference = FileScopePath.fromModelReference(marker.readText())
        assertFalse(Files.exists(root.resolve(reference.relativePath)))
        val store = WorkspaceArtifactStore(rootResolver = ScopeRootResolver { root })
        val entries = Files.newDirectoryStream(root.resolve(".helix/trash")).use { it.toList() }
        val entry =
            entries.single {
                it.fileName.toString().endsWith(
                    reference.parent.relativePath.replace("/", "%2F"),
                )
            }
        store.restoreFromTrash(FileScopePath("app", ".helix/trash/${entry.fileName}"))
        assertEquals(
            "ATTACHMENT_PUBLISHED_BEFORE_PROCESS_DEATH",
            root.resolve(reference.relativePath).toFile().readText(),
        )
        assertTrue(marker.delete())
        // Leave fixture bytes recoverable; next startup will retire this still-unregistered snapshot again.
    }
}
