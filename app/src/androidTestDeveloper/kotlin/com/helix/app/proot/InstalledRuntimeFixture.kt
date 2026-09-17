package com.helix.app.proot

import android.content.Context
import com.helix.runtime.proot.app.ProotNative
import com.helix.runtime.proot.app.ProotRuntimeInstaller
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RootFsInstaller

/** Each job suite owns its prerequisite instead of depending on JUnit class ordering. */
internal fun ensureInstalledRuntime(context: Context) {
    val outcome =
        RootFsInstaller.install(
            ProotRuntimeInstaller.buildInstallRequest(
                context,
                ProotRuntimeInstaller.loadEmbeddedLock(context),
                ProotNative.pageSizeBytes(),
                System.currentTimeMillis(),
            ),
        )
    check(outcome is InstallOutcome.Success) { "Embedded runtime installation failed: $outcome" }
}
