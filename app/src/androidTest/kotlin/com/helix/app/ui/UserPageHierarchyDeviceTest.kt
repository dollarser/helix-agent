package com.helix.app.ui

import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.app.account.AccountUser
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Navigation checks use synthetic account state and never contact the service. */
class UserPageHierarchyDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var accountWindows: Set<View>? = null

    @Test fun overviewKeepsDetailsInDestinationsAndBackRestoresOverview() {
        val account = account()
        compose.setContent {
            MaterialTheme {
                AccountAccessEntry(AccountEntryPresentation.SETTINGS, account, {}, {}, readUsage = {
                    com.helix.app.account
                        .AccountUsageSummary(emptyList(), "today", emptyList(), emptyList())
                })
            }
        }
        compose.onNodeWithTag("account-entry").performClick()
        compose.onNodeWithTag("profile-edit").assertIsDisplayed()
        compose.onNodeWithTag("profile-notices").assertIsDisplayed()
        compose.onNodeWithTag("profile-usage").assertIsDisplayed()
        compose.onNodeWithTag("profile-account").assertIsDisplayed()
        compose.runOnIdle { accountWindows = WindowInspector.getGlobalWindowViews().toSet() }
        compose
            .onNodeWithTag(
                "profile-account-identity",
                useUnmergedTree = true,
            ).assertTextEquals("fixture@example.test")
        compose.onNodeWithTag("account-usage").assertDoesNotExist()
        compose.onNodeWithTag("profile-logout").assertDoesNotExist()
        compose.onNodeWithTag("profile-edit").performClick()
        awaitPage("user-account-page")
        compose.onNodeWithTag("user-account-page-back").performClick()
        awaitPage("user-page")
        compose.onNodeWithTag("profile-usage").performClick()
        awaitPage("user-usage-page")
        compose.onNodeWithTag("user-page").assertDoesNotExist()
        compose.onNodeWithTag("user-usage-page-back").performClick()
        awaitPage("user-page")
        compose.onNodeWithTag("profile-account").performClick()
        awaitPage("user-account-page")
        compose.onNodeWithTag("profile-name").assertTextContains("fixture-login")
        compose.onNodeWithTag("account-bound-email").performScrollTo().assertTextEquals("fixture@example.test")
        compose.onNodeWithTag("profile-manage-account").assertDoesNotExist()
        compose.onNodeWithTag("profile-logout").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile-change-password").performScrollTo().performClick()
        awaitPage("password-change-page")
        compose.onNodeWithTag("password-change-page-back").performClick()
        awaitPage("user-account-page")
        compose.onNodeWithTag("user-account-page-back").performClick()
        awaitPage("user-page")
        compose.onNodeWithTag("profile-notices").performClick()
        awaitPage("account-notices-page")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPage("user-page")
        compose.onNodeWithTag("profile-usage").assertIsDisplayed()
    }

    @Test fun editFromOverviewUpdatesCloudNameAndReturnsInSameWindow() {
        var mutations = 0
        val account = account(onRename = { mutations++ })
        compose.setContent {
            MaterialTheme { AccountAccessEntry(AccountEntryPresentation.SETTINGS, account, {}, {}) }
        }
        compose.onNodeWithTag("account-entry").performClick()
        compose.runOnIdle { accountWindows = WindowInspector.getGlobalWindowViews().toSet() }
        compose.onNodeWithTag("profile-edit").performClick()
        awaitPage("user-account-page")
        compose.onNodeWithTag("profile-name").performScrollTo().performTextReplacement("服务端名字")
        compose.onNodeWithTag("profile-name-save").performScrollTo().performClick()
        compose.waitUntil(5000) { account.user.value?.displayName == "服务端名字" }
        compose.onNodeWithTag("user-account-page-back").performClick()
        awaitPage("user-page")
        compose.onNodeWithTag("profile-display-name", useUnmergedTree = true).assertTextEquals("服务端名字")
        assertEquals(1, mutations)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.File(instrumentation.targetContext.cacheDir, "email-identity-overview.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun awaitPage(tag: String) {
        compose.waitUntil(5000) { compose.onNodeWithTag(tag).isDisplayed() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.runOnIdle { assertEquals(accountWindows, WindowInspector.getGlobalWindowViews().toSet()) }
    }

    @Test fun missingEmailIsDistinctFromUnavailableProfile() {
        val account = account(AccountUser("fixture", "fixture-login", null, false, true))
        compose.setContent { MaterialTheme { AccountIdentityDetails(account) } }
        compose.onNodeWithTag("account-display-name").assertTextContains("fixture-login")
        compose.onNodeWithTag("account-bound-email").assertExists()
        runBlocking { account.logout() }
        compose.onNodeWithTag("account-display-name").assertDoesNotExist()
        compose.onNodeWithTag("account-bound-email").assertDoesNotExist()
    }

    @Test fun unavailableProfileDoesNotInventAnUnboundEmail() {
        val account = account(null)
        compose.setContent { MaterialTheme { AccountIdentityDetails(account) } }
        compose.onNodeWithTag("account-display-name").assertDoesNotExist()
        compose.onNodeWithTag("account-bound-email").assertDoesNotExist()
    }

    private fun account(
        user: AccountUser? = AccountUser("fixture", "fixture-login", "fixture@example.test", false, false),
        onRename: () -> Unit = {},
    ): AccountSession {
        val storage =
            object : AccountTokenStorage {
                private var tokens: AccountTokens? =
                    AccountTokens(
                        "test-access",
                        "test-refresh",
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        "fixture",
                        user,
                    )

                override fun load() = tokens

                override fun save(tokens: AccountTokens) {
                    this.tokens = tokens
                }

                override fun clear() {
                    tokens = null
                }
            }
        var serverUser = user
        val client =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    if (request.url.endsWith("/account/profile")) {
                        check(request.method == "PATCH")
                        val fields = Json.parseToJsonElement(requireNotNull(request.body).decodeToString()).jsonObject
                        check(fields.keys == setOf("display_name"))
                        serverUser =
                            requireNotNull(serverUser).copy(
                                displayName = fields.getValue("display_name").jsonPrimitive.content,
                            )
                        onRename()
                    }
                    val body =
                        if (request.url.endsWith("/account/me") || request.url.endsWith("/account/profile")) {
                            responseBody("{\"user\":${serverUser?.document()}}")
                        } else {
                            emptyNotices()
                        }
                    return WireResponse(200, emptyMap(), body)
                }
            }
        return AccountSession(storage, client, noticeTransport = client).also { runBlocking { it.restore() } }
    }

    private fun emptyNotices() =
        responseBody(
            """{"unread":0,"page":1,"page_size":30,"total":0,"items":[]}""",
        )

    private fun responseBody(payload: String) =
        object : WireBody {
            override suspend fun bytes() = payload.toByteArray()

            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                onChunk(bytes())
            }

            override fun close() = Unit
        }
}
