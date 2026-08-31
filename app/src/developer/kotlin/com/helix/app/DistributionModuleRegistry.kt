package com.helix.app

import com.helix.feature.files.allfiles.FilesAllFilesMarker
import com.helix.runtime.cli.client.CliClientMarker
import com.helix.runtime.proot.client.ProotClientMarker
import com.helix.tools.automation.AutomationMarker
import com.helix.tools.root.RootMarker

internal object DistributionModuleRegistry {
    const val EDITION: String = "developer"

    /**
     * Developer-only module markers compiled into this flavor. The hard references keep the
     * marker classes — and their `HELIX_DEVELOPER_ONLY_*` probe constants — in the APK's dex
     * even if a future release build strips unreferenced classes; the probe is exactly what
     * `verify-variant-boundaries.sh` greps for.
     */
    val developerOnlyMarkers: List<Class<*>> =
        listOf(
            FilesAllFilesMarker::class.java,
            AutomationMarker::class.java,
            RootMarker::class.java,
            ProotClientMarker::class.java,
            CliClientMarker::class.java,
        )
}
