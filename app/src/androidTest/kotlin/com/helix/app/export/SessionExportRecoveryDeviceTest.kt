package com.helix.app.export

import android.net.Uri
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Run only with the owned runner's setup/verify protocol: kills the real app mid-copy. */
class SessionExportRecoveryDeviceTest {
    @Test fun freshProcessCleansPartialDocumentWithoutReExportOrConversationChanges() =
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
            require(phase == "setup" || phase == "verify") { "Use the two-phase owned runner" }
            val context = instrumentation.targetContext
            val marker = context.noBackupFilesDir.resolve("session-export-recovery")
            val pid = context.noBackupFilesDir.resolve("recovery-device-pid")
            if (phase == "setup") {
                val fixture = SessionExportFixture()
                fixture.service.cleanupInterrupted()
                fixture.seed(128)
                val target = fixture.create("normal")
                marker.writeText(fixture.id + "\n" + target)
                pid.writeText(Process.myPid().toString())
                fixture.service.export("selected", target) {
                    Process.killProcess(Process.myPid())
                    error("Expected process death during first output block")
                }
                error("Setup cannot complete normally")
            }
            val parts = marker.readLines()
            require(UUID.fromString(parts[0]).toString() == parts[0])
            assertNotEquals(pid.readText().toInt(), Process.myPid())
            SessionExportFixture(parts[0]).use { fixture ->
                val target = Uri.parse(parts[1])
                fixture.own(target)
                assertTrue(fixture.exists(target))
                assertEquals(1, fixture.opens(target))
                assertTrue(fixture.tail(target).getLong("size") > 0)
                assertFalse(fixture.tail(target).getBoolean("complete"))
                assertTrue(
                    fixture.context.contentResolver.persistedUriPermissions.any {
                        it.uri == target &&
                            it.isWritePermission
                    },
                )
                val recovery = fixture.service.cleanupInterrupted()
                assertTrue(recovery.interrupted)
                assertFalse(recovery.partialMayRemain)
                assertFalse(fixture.exists(target))
                assertFalse(
                    fixture.context.contentResolver.persistedUriPermissions
                        .any { it.uri == target },
                )
                assertTrue(fixture.temporaryIsEmpty())
                assertEquals(
                    128,
                    fixture.storage.messages
                        .listBySession("selected")
                        .size,
                )
                assertFalse(fixture.service.cleanupInterrupted().interrupted)
            }
            marker.delete()
            pid.delete()
        }
}
