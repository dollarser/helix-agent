package com.helix.app.ui

import com.helix.app.R
import com.helix.app.application.runtime.ProotInstallationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProotInstallationLabelTest {
    @Test fun everyInstallationFactHasOneSharedUiLabel() {
        val labels = ProotInstallationStatus.entries.associateWith { it.labelRes() }
        assertEquals(
            mapOf(
                ProotInstallationStatus.NOT_AVAILABLE to R.string.cap_status_unavailable,
                ProotInstallationStatus.NOT_PREPARED to R.string.settings_proot_not_installed,
                ProotInstallationStatus.PREPARED to R.string.settings_proot_verified,
                ProotInstallationStatus.UPDATE_REQUIRED to R.string.settings_proot_update_required,
                ProotInstallationStatus.DAMAGED to R.string.settings_proot_damaged,
                ProotInstallationStatus.RESET_REQUIRED to R.string.settings_proot_damaged,
            ),
            labels,
        )
    }
}
