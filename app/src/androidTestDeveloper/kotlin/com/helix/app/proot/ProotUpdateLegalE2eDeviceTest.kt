package com.helix.app.proot

import android.content.ComponentName
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.app.ProotRuntimeInstaller
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.client.RepairEntryResult
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeInstallationState
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.RuntimeManifestCodec
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.UnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Current bundled lifecycle: no separate APK approval or manual verification receipt. */
class ProotUpdateLegalE2eDeviceTest : com.helix.app.test.ForegroundDeviceTestHost() {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theLegalPageActivityIsPrivateToTheHostApplication() {
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(context.packageName, ProotRuntimeProtocol.LEGAL_ACTIVITY_CLASS),
                0,
            )
        assertFalse(info.exported)
        assertEquals("${context.packageName}:proot", info.processName)
    }

    @Test fun theLegalPageOpensFromTheUserClick() {
        ProotToolModule.wireForTest(context)
        assertEquals(RepairEntryResult.Opened, ProotToolModule.openLegalPage())
    }

    @Test fun openingMaintenanceNeverInvalidatesInstallationOrWorkspace() {
        ensureInstalledRuntime(context)
        ProotToolModule.wireForTest(context)
        val supervisor = ProotRuntimeSupervisor(context)
        val original = supervisor.installation().manifest
        val marker = File(context.filesDir, "workspace-survives-maintenance").apply { writeText("keep") }
        try {
            assertEquals(RepairEntryResult.Opened, ProotToolModule.openEnvironment())
            assertEquals(original, supervisor.installation().manifest)
            assertTrue(supervisor.installationReady())
            assertEquals("keep", marker.readText())
        } finally {
            marker.delete()
        }
    }

    @Test fun changedInstalledVersionRequiresUpdateAndCannotBeRebaselinedAway() {
        ensureInstalledRuntime(context)
        val supervisor = ProotRuntimeSupervisor(context)
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        val active = requireNotNull(RootFsInstaller.currentActive(root))
        val file = File(root, "${active.installId}/manifest.json")
        val original = file.readText()
        val manifest = RuntimeManifestCodec.parseManifest(original)
        val changed =
            manifest.lock.copy(
                components =
                    manifest.lock.components.map {
                        it.copy(
                            version =
                                it.version + "-old",
                        )
                    },
            )
        try {
            file.writeText(
                RuntimeManifestCodec.encodeManifest(
                    manifest.copy(lock = changed, lockSha256 = RuntimeLockCodec.sha256Hex(changed)),
                ),
            )
            assertEquals(RuntimeInstallationState.UPDATE_REQUIRED, supervisor.installation().state)
            val result =
                CompletableFuture
                    .supplyAsync {
                        supervisor.verify(
                            System.currentTimeMillis(),
                        )
                    }.get(30, TimeUnit.SECONDS)
            assertEquals(UnavailableCause.LOCK_MISMATCH, (result as ProotRuntimeAvailability.Unavailable).cause)
            assertFalse(supervisor.installationReady())
        } finally {
            file.writeText(original)
        }
        assertTrue(supervisor.installationReady())
    }
}
