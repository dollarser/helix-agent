package com.helix.ui.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.ProviderProvisioningKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Only presentation events; selecting a source does not access a provider or choose a model. */
class ModelSourceNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun requireIsolatedApplication() {
        requireSharedTestApplication(ApplicationProvider.getApplicationContext())
    }

    @Test fun largeFontSourceActionsHaveEqualWidthsAndOneSourcePerRow() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(density.density, 1.8f),
            ) {
                MaterialTheme { ModelSourceActions(ProviderProvisioningKind.entries) {} }
            }
        }
        val bounds =
            listOf("USER_CONFIGURED", "MANAGED_ACCOUNT", "ON_DEVICE_ASSET").map {
                compose
                    .onNodeWithTag("chat-model-configure-$it")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            }
        assertEquals(bounds[0].width, bounds[1].width, 1f)
        assertEquals(bounds[1].width, bounds[2].width, 1f)
        assertTrue(bounds[0].bottom <= bounds[1].top && bounds[1].bottom <= bounds[2].top)
    }

    @Test fun categoriesRemainAvailableAfterSwitching() {
        compose.setContent {
            var selected by remember { mutableStateOf(ProviderProvisioningKind.USER_CONFIGURED) }
            MaterialTheme {
                ModelSourceTabs(ProviderProvisioningKind.entries, selected) { selected = it }
            }
        }
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").assertIsSelected()
        compose.onNodeWithTag("provider-group-ON_DEVICE_ASSET").performClick().assertIsSelected()
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick().assertIsSelected()
        compose.onNodeWithTag("provider-group-MANAGED_ACCOUNT").assertIsDisplayed()
    }
}
