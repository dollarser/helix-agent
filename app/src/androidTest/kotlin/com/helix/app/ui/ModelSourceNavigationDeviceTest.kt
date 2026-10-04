package com.helix.app.ui

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
import androidx.compose.ui.test.performTextInput
import com.helix.core.model.ProviderProvisioningKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Presentation only: source navigation must not select a model or start a provider operation. */
class ModelSourceNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyPickerOffersApiAndLocalWithoutSubscription() {
        var destination: ProviderProvisioningKind? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(
                    emptyList(),
                    null,
                    null,
                    true,
                    { _, _ -> error("navigation must not select a model") },
                    onConfigureSource = { destination = it },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-configure-USER_CONFIGURED").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-configure-ON_DEVICE_ASSET").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-configure-MANAGED_ACCOUNT").assertDoesNotExist()
        compose.runOnIdle { assertNull(destination) }
        compose.onNodeWithTag("chat-model-configure-USER_CONFIGURED").performClick()
        compose.runOnIdle { assertEquals(ProviderProvisioningKind.USER_CONFIGURED, destination) }
        compose.onNodeWithTag("chat-model-search").assertDoesNotExist()
    }

    @Test fun searchDoesNotHideLocalConfiguration() {
        var destination: ProviderProvisioningKind? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(
                    emptyList(),
                    null,
                    null,
                    true,
                    { _, _ -> error("navigation must not select a model") },
                    onConfigureSource = { destination = it },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-search").performTextInput("no matches")
        compose.onNodeWithTag("chat-model-configure-ON_DEVICE_ASSET").performClick()
        compose.runOnIdle { assertEquals(ProviderProvisioningKind.ON_DEVICE_ASSET, destination) }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-configure-USER_CONFIGURED").assertIsDisplayed()
    }

    @Test fun enabledSubscriptionHasAnEntryEvenWithoutProviderRows() {
        var destination: ProviderProvisioningKind? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(
                    emptyList(),
                    null,
                    null,
                    true,
                    { _, _ -> error("navigation must not select a model") },
                    sourceGroups = ProviderProvisioningKind.entries,
                    onConfigureSource = { destination = it },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-configure-MANAGED_ACCOUNT").performClick()
        compose.runOnIdle { assertEquals(ProviderProvisioningKind.MANAGED_ACCOUNT, destination) }
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
