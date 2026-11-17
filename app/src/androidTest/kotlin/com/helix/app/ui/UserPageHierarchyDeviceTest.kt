package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Navigation checks use synthetic account state and never contact the service. */
class UserPageHierarchyDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overviewKeepsDetailsInDestinationsAndBackRestoresOverview() {
        val account = account()
        compose.setContent { MaterialTheme { AccountAccessEntry(AccountEntryPresentation.SETTINGS, account, {}, {}) } }
        compose.onNodeWithTag("account-entry").performClick()
        compose.onNodeWithTag("profile-edit").assertIsDisplayed()
        compose.onNodeWithTag("profile-notices").assertIsDisplayed()
        compose.onNodeWithTag("profile-usage").assertIsDisplayed()
        compose.onNodeWithTag("profile-account").assertIsDisplayed()
        compose.onNodeWithTag("account-usage").assertDoesNotExist()
        compose.onNodeWithTag("profile-logout").assertDoesNotExist()
        compose.onNodeWithTag("profile-usage").performClick()
        compose.onNodeWithTag("user-usage-page").assertIsDisplayed()
        compose.onNodeWithTag("user-page").assertDoesNotExist()
        compose.onNodeWithTag("user-usage-page-back").performClick()
        compose.onNodeWithTag("profile-account").performClick()
        compose.onNodeWithTag("user-account-page").assertIsDisplayed()
        compose.onNodeWithTag("profile-manage-account").assertDoesNotExist()
        compose.onNodeWithTag("profile-logout").assertIsDisplayed()
        compose.onNodeWithTag("user-account-page-back").performClick()
        compose.onNodeWithTag("profile-notices").performClick()
        compose.onNodeWithTag("account-notices-page").assertIsDisplayed()
        compose.onNodeWithTag("account-notices-page-back").performClick()
        compose.onNodeWithTag("profile-usage").assertIsDisplayed()
    }

    private fun account(): AccountSession {
        val storage =
            object : AccountTokenStorage {
                private var tokens: AccountTokens? =
                    AccountTokens("test-access", "test-refresh", Long.MAX_VALUE, Long.MAX_VALUE, "fixture")

                override fun load() = tokens

                override fun save(tokens: AccountTokens) {
                    this.tokens = tokens
                }

                override fun clear() {
                    tokens = null
                }
            }
        val client =
            object : WireClient {
                override suspend fun open(request: WireRequest) = WireResponse(200, emptyMap(), emptyNotices())
            }
        return AccountSession(storage, client).also { runBlocking { it.restore() } }
    }

    private fun emptyNotices() =
        object : WireBody {
            override suspend fun bytes() = """{"unread":0,"page":1,"page_size":30,"total":0,"items":[]}""".toByteArray()

            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                onChunk(bytes())
            }

            override fun close() = Unit
        }
}
