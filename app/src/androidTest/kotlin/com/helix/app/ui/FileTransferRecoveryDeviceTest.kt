package com.helix.app.ui

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.files.ManualFileBackend
import com.helix.app.files.ManualFileOperations
import com.helix.app.files.ManualTransferJournal
import com.helix.app.files.NioManualFileBackend
import com.helix.core.workspace.ScopeRootResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Default is a normal UI regression; the owned runner also executes setup/verify across process death. */
class FileTransferRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class SimulatedInterruption : Error()

    @Test fun recoversPublishedMoveWithoutDeletingSource() {
        compose.resetDeterministicUiState()
        val context = compose.activity
        val root = context.filesDir.resolve("workspaces/app")
        val folder = root.resolve("work/recovery-device")
        val marker = context.noBackupFilesDir.resolve("recovery-device-pid")
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        if (phase != "verify") {
            folder.mkdirs()
            folder.resolve("source").writeText("new")
            folder.resolve("target").writeText("old")
            val nio = NioManualFileBackend("app", ScopeRootResolver { root.toPath() }, true) {}
            val broken =
                object : ManualFileBackend by nio {
                    override fun rename(
                        path: String,
                        destination: String,
                    ) {
                        nio.rename(path, destination)
                        if (destination == "work/recovery-device/target") {
                            if (phase == "setup") {
                                marker.writeText(Process.myPid().toString())
                                Process.killProcess(Process.myPid())
                            }
                            throw SimulatedInterruption()
                        }
                    }
                }
            val journal = ManualTransferJournal(context.noBackupFilesDir.toPath().resolve("manual-transfers"))
            assertThrows(SimulatedInterruption::class.java) {
                ManualFileOperations({ broken }, { true }, journal).transfer(
                    "app",
                    "work/recovery-device/source",
                    "app",
                    "work/recovery-device/target",
                    true,
                    true,
                )
            }
            marker.writeText(Process.myPid().toString())
        }
        if (phase == "setup") return
        try {
            if (phase == "verify") assertNotEquals(marker.readText().toInt(), Process.myPid())
            val manager = compose.container().fileManager
            val entry = manager.pendingTransfers().single { it.source.endsWith("work/recovery-device/source") }
            compose.navigateTo("files")
            compose.onNodeWithTag("files-recovery-open").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("files-recovery-${entry.id}").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("files-recovery-${entry.id}").performClick()
            compose.waitUntil(30_000) { manager.pendingTransfers().none { it.id == entry.id } }
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("files-recovery-result").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("new", folder.resolve("source").readText())
            assertEquals("new", folder.resolve("target").readText())
            assertTrue(folder.listFiles().orEmpty().none { it.name.startsWith(".helix-") })
        } finally {
            folder.deleteRecursively()
            marker.delete()
        }
    }
}
