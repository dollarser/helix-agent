package com.helix.runtime.proot.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-only precondition for the HXA-086 lifecycle script.
 *
 * Installing the APK and lifting Android's force-stopped package state do not install the
 * embedded RootFS. The lifecycle phases intentionally exercise only process/package state, so
 * the host installs and verifies the active runtime once before it starts that matrix.
 */
@RunWith(AndroidJUnit4::class)
class ProotLifecycleHostSetupDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun activeRuntimeIsInstalledForHostPhases() {
        assumeTrue(
            "host setup only: run via scripts/accept-hxa-086-lifecycle.sh",
            InstrumentationRegistry.getArguments().getString("hxa086_host_setup") == "1",
        )
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        if (RootFsInstaller.currentActive(root) == null) {
            val lock = ProotRuntimeInstaller.loadEmbeddedLock(context)
            val outcome =
                RootFsInstaller.install(
                    ProotRuntimeInstaller.buildInstallRequest(
                        context,
                        lock,
                        ProotNative.pageSizeBytes(),
                        System.currentTimeMillis(),
                    ),
                )
            assertTrue("runtime install failed: $outcome", outcome is InstallOutcome.Success)
        }
        assertNotNull("active runtime is missing after host setup", RootFsInstaller.currentActive(root))
    }
}
