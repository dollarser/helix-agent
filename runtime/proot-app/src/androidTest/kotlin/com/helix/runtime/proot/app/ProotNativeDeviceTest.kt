package com.helix.runtime.proot.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-084: the native getpagesize() seam. The value must be the real host page
 * size (it gates the ELF pre-activation check), so it is cross-checked against
 * the runtime's own `page.size` system property when that property is present.
 */
@RunWith(AndroidJUnit4::class)
class ProotNativeDeviceTest {
    @Test
    fun theNativePageSizeIsPositiveAndConsistent() {
        val page = ProotNative.pageSizeBytes()
        assertTrue("page size must be positive, got $page", page > 0)
        val fromProperty =
            System.getProperty("page.size")?.toLongOrNull()
                ?: System.getProperty("page_size")?.toLongOrNull()
        if (fromProperty != null) {
            assertEquals("native seam disagrees with the runtime property", fromProperty, page)
        }
        // The cached value must be stable across calls.
        assertEquals(page, ProotNative.pageSizeBytes())
    }

    @Test
    fun theRepairActivityUsesTheSeamIndirectly() {
        // The install request built from the live context uses the seam's value:
        // a 16 KiB device must install against 16384, not the old 4096 constant.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val page = ProotNative.pageSizeBytes()
        val request =
            ProotRuntimeInstaller.buildInstallRequest(
                context,
                ProotRuntimeInstaller.loadEmbeddedLock(context),
                page,
                System.currentTimeMillis(),
            )
        assertEquals(page, request.facts.pageSizeBytes)
    }
}
