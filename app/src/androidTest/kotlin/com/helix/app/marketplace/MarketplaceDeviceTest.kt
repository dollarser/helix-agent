package com.helix.app.marketplace

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarketplaceDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val service get() = requireNotNull(container.marketplaceService) { "MarketplaceService should be wired" }

    @Test
    fun marketplaceItemsAreInstallableAndDetectStatus() {
        val items = service.items()
        assertTrue("Marketplace should provide items", items.isNotEmpty())

        val cloudflare = items.first { it.id == "cloudflare-docs" }
        assertEquals(MarketplaceItemStatus.NOT_INSTALLED, service.status(cloudflare))

        val installed = service.install(cloudflare)
        assertNotNull("Installation should return an InstalledConnector", installed)

        try {
            assertEquals(MarketplaceItemStatus.INSTALLED_INACTIVE, service.status(cloudflare))

            // Idempotent re-install
            val reinstalled = service.install(cloudflare)
            assertNotNull(reinstalled)
            assertEquals(installed?.id, reinstalled?.id)

            // Skill item install
            val codeReview = items.first { it.id == "code-review" }
            val skillInstalled = service.install(codeReview)
            assertNotNull(skillInstalled)
            try {
                assertEquals(MarketplaceItemStatus.ACTIVE, service.status(codeReview))
            } finally {
                skillInstalled?.let { container.connectorService.remove(it) }
            }
        } finally {
            installed?.let { container.connectorService.remove(it) }
        }

        assertEquals(MarketplaceItemStatus.NOT_INSTALLED, service.status(cloudflare))
    }
}
