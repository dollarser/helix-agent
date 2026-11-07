package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.helix.core.model.ProviderProvisioningKind
import com.helix.feature.conversation.presentation.ComposerModelMenu
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
                    { _, _, _ -> error("navigation must not select a model") },
                    onConfigureSource = { destination = it },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-configure-USER_CONFIGURED")
        compose.onNodeWithTag("chat-model-configure-USER_CONFIGURED").assertIsDisplayed()
        reveal("chat-model-configure-ON_DEVICE_ASSET")
        compose.onNodeWithTag("chat-model-configure-ON_DEVICE_ASSET").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-configure-MANAGED_ACCOUNT").assertDoesNotExist()
        compose.runOnIdle { assertNull(destination) }
        reveal("chat-model-configure-USER_CONFIGURED")
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
                    { _, _, _ -> error("navigation must not select a model") },
                    onConfigureSource = { destination = it },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-search").performTextInput("no matches")
        reveal("chat-model-configure-ON_DEVICE_ASSET")
        compose.onNodeWithTag("chat-model-configure-ON_DEVICE_ASSET").performClick()
        compose.runOnIdle { assertEquals(ProviderProvisioningKind.ON_DEVICE_ASSET, destination) }
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-configure-USER_CONFIGURED")
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
                    { _, _, _ -> error("navigation must not select a model") },
                    sourceGroups = ProviderProvisioningKind.entries,
                    onConfigureSource = { destination = it },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-configure-MANAGED_ACCOUNT")
        compose.onNodeWithTag("chat-model-configure-MANAGED_ACCOUNT").performClick()
        compose.runOnIdle { assertEquals(ProviderProvisioningKind.MANAGED_ACCOUNT, destination) }
    }

    // Short landscape windows reach the single-column actions through the real picker list.
    private fun reveal(tag: String) {
        compose.onNodeWithTag("chat-model-list").performScrollToNode(hasTestTag(tag))
    }
}
