package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.helix.app.ShellDestination
import com.helix.app.account.AccountStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Presentation fixtures only: no account requests, secrets, or device-state mutations. */
class AccountEntryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun prototypeAvatarAndIconGeometryRemainNativeTouchTargets() {
        compose.setContent {
            MaterialTheme {
                DrawerVisualTheme {
                    Column {
                        AccountEntryButton(AccountStatus.SIGNED_OUT, AccountEntryPresentation.DRAWER) {}
                        DrawerRow(
                            label = { androidx.compose.material3.Text("Models") },
                            selected = false,
                            onClick = {},
                            modifier = Modifier.testTag("fixture-row"),
                            iconRes = com.helix.app.R.drawable.ic_drawer_models,
                        )
                    }
                }
            }
        }
        assertEquals(
            40f,
            compose
                .onNodeWithTag("drawer-account-avatar", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
                .let { (it.bottom - it.top).value },
            0.5f,
        )
        assertEquals(
            20f,
            compose
                .onNodeWithTag("drawer-icon-${com.helix.app.R.drawable.ic_drawer_models}", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
                .let { (it.right - it.left).value },
            0.5f,
        )
        val actualPixels =
            compose
                .onNodeWithTag("fixture-row")
                .fetchSemanticsNode()
                .size.height
        val requiredPixels = with(compose.density) { 48.dp.roundToPx() }
        org.junit.Assert.assertTrue(
            "Native row pixels=$actualPixels, required=$requiredPixels",
            actualPixels >= requiredPixels,
        )
    }

    @Test fun accountAndSettingsStayVisibleWhileNavigationScrolls() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).height(480.dp)) {
                    GroupedNavigation(
                        destinations = ShellDestination.entries,
                        currentRoute = "sessions",
                        conversation = ConversationDrawerState("id", "Draft remains here"),
                        onCurrentConversation = {},
                        onNewConversation = {},
                        onAllConversations = {},
                        header = {
                            AccountEntryButton(
                                AccountStatus.SIGNED_OUT,
                                AccountEntryPresentation.DRAWER,
                            ) { opened++ }
                        },
                        onNavigate = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("navigation-group-work").performScrollTo()
        compose.onNodeWithTag("drawer-account").assertIsDisplayed().performClick()
        compose.onNodeWithTag("navigation-group-settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("drawer-settings-sheet").assertIsDisplayed()
        compose.onNodeWithTag("drawer-settings-close").performClick()
        compose.onNodeWithTag("drawer-current-conversation").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun accountStatesRemainExplicitAndClickable() {
        val state = mutableStateOf(AccountStatus.SIGNED_OUT)
        var opened = 0
        compose.setContent {
            MaterialTheme {
                AccountEntryButton(state.value, AccountEntryPresentation.DRAWER) { opened++ }
            }
        }
        val context =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .targetContext
        val labels =
            listOf(
                AccountStatus.SIGNED_OUT to com.helix.app.R.string.account_drawer_login,
                AccountStatus.SIGNED_IN to com.helix.app.R.string.account_signed_in,
                AccountStatus.REAUTH_REQUIRED to com.helix.app.R.string.account_reauth,
                AccountStatus.UNAVAILABLE to com.helix.app.R.string.account_unavailable,
            )
        labels.forEach { (status, label) ->
            compose.runOnIdle { state.value = status }
            compose.onNodeWithTag("drawer-account").assertTextContains(context.getString(label)).performClick()
        }
        compose.runOnIdle { assertEquals(4, opened) }
    }
}
