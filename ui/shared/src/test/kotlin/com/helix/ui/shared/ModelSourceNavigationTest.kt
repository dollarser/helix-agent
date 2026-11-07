package com.helix.ui.shared

import com.helix.core.model.ProviderProvisioningKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceNavigationTest {
    @Test fun sourceOrderIsStableAndOnlyIncludesAvailableConfigurableSources() {
        assertEquals(
            listOf(
                ProviderProvisioningKind.USER_CONFIGURED,
                ProviderProvisioningKind.MANAGED_ACCOUNT,
                ProviderProvisioningKind.ON_DEVICE_ASSET,
            ),
            orderedSources(ProviderProvisioningKind.entries.reversed()),
        )
        assertEquals(
            listOf(ProviderProvisioningKind.ON_DEVICE_ASSET),
            orderedSources(listOf(ProviderProvisioningKind.ON_DEVICE_ASSET, ProviderProvisioningKind.ON_DEVICE_ASSET)),
        )
    }

    @Test fun absentAndNonConfigurableSourcesDoNotInventNavigationEntries() {
        assertTrue(orderedSources(emptyList()).isEmpty())
        assertTrue(orderedSources(listOf(ProviderProvisioningKind.BUNDLED_SERVICE)).isEmpty())
    }
}
