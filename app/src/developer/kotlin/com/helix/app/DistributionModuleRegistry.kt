package com.helix.app

import com.helix.feature.files.allfiles.FilesAllFilesMarker
import com.helix.runtime.cli.client.CliClientMarker
import com.helix.runtime.proot.client.ProotClientMarker
import com.helix.tools.automation.AutomationMarker
import com.helix.tools.root.RootMarker

internal object DistributionModuleRegistry {
    const val EDITION: String = "developer"
    val moduleIds: List<String> =
        listOf(
            FilesAllFilesMarker.ID,
            AutomationMarker.ID,
            RootMarker.ID,
            ProotClientMarker.ID,
            CliClientMarker.ID,
        )
}
