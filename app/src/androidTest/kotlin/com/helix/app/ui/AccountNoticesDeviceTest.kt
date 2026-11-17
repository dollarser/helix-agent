package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.account.AccountNoticesStore
import com.helix.app.account.AccountStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic in-memory account and notices only; never contacts the account service or changes login. */
class AccountNoticesDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawerBadgeReflectsUnreadWithoutMarkingAnythingRead() {
        val unread = mutableStateOf(120)
        compose.setContent {
            MaterialTheme {
                AccountEntryButton(AccountStatus.SIGNED_IN, AccountEntryPresentation.DRAWER, unread.value) {}
            }
        }
        compose.onNodeWithTag("account-unread-badge", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { unread.value = 0 }
        compose.onNodeWithTag("account-unread-badge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun openingDetailMarksReadAndKeepsBodyPlainTextWithRepeatAndBack() {
        val fixture = NoticeUiFixture()
        val store = AccountNoticesStore(fixture.account, fixture.api)
        compose.setContent { MaterialTheme { AccountNoticesSheet(store) {} } }
        compose.waitUntil(5_000) {
            store.state.value.items
                .isNotEmpty()
        }
        compose.runOnIdle { assertEquals(0, fixture.api.readCalls) }
        compose.onNodeWithText(label(R.string.account_notices_important)).assertIsDisplayed()
        compose.onNodeWithTag("notice-${fixture.notice.id}").performClick()
        compose.waitUntil(5_000) {
            fixture.api.readCalls == 1 &&
                store.state.value.markingRead
                    .isEmpty()
        }
        compose.onNodeWithTag("account-notice-body").assertTextEquals(fixture.notice.body)
        compose.onNodeWithTag("account-notice-detail-page-back").performClick()
        compose.onNodeWithTag("account-notice-detail-page").assertDoesNotExist()
        compose.onNodeWithTag("notice-${fixture.notice.id}").performClick()
        compose.onNodeWithTag("account-notice-body").assertIsDisplayed()
        // Send system Back to the focused dialog, not Espresso's underlying activity root.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("account-notice-detail-page").assertDoesNotExist()
        compose.onNodeWithTag("account-notices-page").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fixture.api.readCalls) }
    }

    @Test fun forbiddenListShowsRestrictionWithoutExternalLinkOrAcknowledgement() {
        val fixture = NoticeUiFixture(listStatus = 403)
        val store = AccountNoticesStore(fixture.account, fixture.api)
        compose.setContent { MaterialTheme { AccountNoticesSheet(store) {} } }
        compose.waitUntil(5_000) { store.state.value.failure != null }
        compose.onNodeWithTag("account-failure").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.account_notices_manage_account)).assertDoesNotExist()
        compose.onNodeWithTag("account-notices-empty").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.api.readCalls) }
    }

    @Test fun withdrawnNoticeRefreshesAndDismissesStaleDetail() {
        val fixture = NoticeUiFixture(readStatus = 404)
        val store = AccountNoticesStore(fixture.account, fixture.api)
        compose.setContent { MaterialTheme { AccountNoticesSheet(store) {} } }
        compose.waitUntil(5_000) {
            store.state.value.items
                .isNotEmpty()
        }
        compose.onNodeWithTag("notice-${fixture.notice.id}").performClick()
        compose.waitUntil(5_000) { store.state.value.invalidated && !store.state.value.loading }
        compose.onNodeWithTag("account-notice-detail-page").assertDoesNotExist()
        compose.onNodeWithTag("account-notice-body").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.account_notices_changed)).assertIsDisplayed()
        compose.onNodeWithTag("account-notices-empty").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fixture.api.readCalls) }
    }

    @Test fun failedReadStaysVisibleAndCanBeRetriedInDetail() {
        val fixture = NoticeUiFixture(readStatus = 500)
        val store = AccountNoticesStore(fixture.account, fixture.api)
        compose.setContent { MaterialTheme { AccountNoticesSheet(store) {} } }
        compose.waitUntil(5_000) {
            store.state.value.items
                .isNotEmpty()
        }
        compose.onNodeWithTag("notice-${fixture.notice.id}").performClick()
        compose.waitUntil(5_000) { store.state.value.failure != null }
        compose.onNodeWithTag("account-notice-body").assertIsDisplayed()
        compose.onNodeWithTag("account-notice-retry-read").assertIsDisplayed()
        compose.runOnIdle { fixture.api.readStatus = null }
        compose.onNodeWithTag("account-notice-retry-read").performClick()
        compose.waitUntil(5_000) { fixture.api.readCalls == 2 && store.state.value.failure == null }
        compose.onNodeWithTag("account-notice-retry-read").assertDoesNotExist()
        compose.onNodeWithTag("account-notice-detail-page-back").performClick()
        compose.runOnIdle { assertEquals(0, store.state.value.unread) }
    }

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
