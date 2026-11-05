package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootUiTransactionTest {
    @Test fun rootRoutesDevicePreviewAndCleanupThroughTheSameClosedUiService() {
        listOf(
            ShizukuUiProtocol.GET_UID,
            ShizukuUiProtocol.CLICK_MATCH,
            ShizukuUiProtocol.DEVICE,
            ShizukuUiProtocol.PREVIEW,
            ShizukuUiProtocol.RESTORE_FOCUS,
        ).forEach { assertTrue(isRootUiTransaction(it)) }
        assertFalse(isRootUiTransaction(ShizukuUiProtocol.DESTROY_REMOTE))
        assertFalse(isRootUiTransaction(9999))
    }
}
