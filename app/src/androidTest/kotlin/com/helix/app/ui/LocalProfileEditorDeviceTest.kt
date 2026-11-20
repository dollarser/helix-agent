package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.app.account.AccountUser
import com.helix.app.account.LocalUserProfile
import com.helix.app.account.LocalUserProfilePersistence
import com.helix.app.account.LocalUserProfileStore
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/** Actual profile entry with synthetic transport and private avatar storage; no external requests. */
class LocalProfileEditorDeviceTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()
    private val requests = AtomicInteger()

    @Test fun saveSynchronizesNameWithoutChangingAccountIdentity() {
        val account = editor()
        val revision = account.revision
        compose.onNodeWithTag("profile-name").performTextReplacement("New name")
        val click =
            compose
                .onNodeWithTag("profile-name-save")
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.runOnIdle {
            click()
            click()
        }
        compose.waitUntil(5_000) { account.user.value?.displayName == "New name" }
        assertEquals(1, requests.get())
        assertEquals(revision, account.revision)
        assertEquals("12", account.noticeAccountKey)
        assertNoPasswordField()
    }

    @Test fun failedRenamePreservesServerNameAndAllowsExplicitRetry() {
        val account = editor(429)
        compose.onNodeWithTag("profile-name").performTextReplacement("New name")
        compose.onNodeWithTag("profile-name-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { requests.get() == 1 }
        compose.waitForIdle()
        assertNoPasswordField()
        assertEquals("Before", account.user.value!!.displayName)
        compose.onNodeWithTag("profile-name-save").performScrollTo().assertIsEnabled()
    }

    @Test fun emptyOrOverlongNameCannotBeSaved() {
        editor()
        for (name in listOf("", "x".repeat(81))) {
            compose.onNodeWithTag("profile-name").performScrollTo().performTextReplacement(name)
            compose.onNodeWithTag("profile-name-save").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("profile-name").performScrollTo().performTextReplacement("x".repeat(80))
        compose.onNodeWithTag("profile-name-save").performScrollTo().assertIsEnabled()
        assertEquals(0, requests.get())
    }

    @Test fun cancellingDoesNotSendDraft() {
        editor()
        compose.onNodeWithTag("profile-name").performTextReplacement("Discard")
        compose.onNodeWithTag("user-account-page-back").performClick()
        assertEquals(0, requests.get())
    }

    @Test fun headerUsesCloudName() {
        compose.setContent {
            MaterialTheme {
                UserProfileHeader(
                    LocalUserProfile(),
                    AccountUser("12", "Cloud name", "mail@example.test", false, false),
                ) {}
            }
        }
        compose.onNodeWithTag("profile-display-name", useUnmergedTree = true).assertTextEquals("Cloud name")
    }

    @Test fun avatarRemovalKeepsUnsavedUsernameOnTheUnifiedPage() {
        editor(withAvatar = true)
        compose.onNodeWithTag("profile-name").performScrollTo().performTextReplacement("Unsaved name")
        compose.onNodeWithTag("profile-photo-remove").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("profile-photo-remove").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("profile-name").performScrollTo().assertTextContains("Unsaved name")
        compose.onNodeWithTag("user-account-page").assertExists()
        compose.onNodeWithTag("profile-save").assertDoesNotExist()
        assertEquals(0, requests.get())
    }

    private fun assertNoPasswordField() {
        compose.onNodeWithTag("profile-current-password").assertDoesNotExist()
    }

    private fun editor(
        status: Int = 200,
        withAvatar: Boolean = false,
    ): AccountSession {
        val storage =
            object : AccountTokenStorage {
                private var value: AccountTokens? =
                    AccountTokens(
                        "synthetic-access",
                        "synthetic-refresh",
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        "synthetic",
                        AccountUser("12", "Before", "mail@example.test", false, false),
                    )

                override fun load() = value

                override fun save(tokens: AccountTokens) {
                    value = tokens
                }

                override fun clear() {
                    value = null
                }
            }
        val transport =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    check(request.url.endsWith("/account/profile") && request.method == "PATCH")
                    requests.incrementAndGet()
                    val payload =
                        if (status == 200) {
                            """{"user":{"id":12,"display_name":"New name","email":"mail@example.test"}}"""
                        } else {
                            """{"error_code":"display_name_change_limited"}"""
                        }
                    return WireResponse(
                        status,
                        emptyMap(),
                        object : WireBody {
                            override suspend fun bytes() = payload.toByteArray()

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                onChunk(bytes())
                            }

                            override fun close() = Unit
                        },
                    )
                }
            }
        val account = AccountSession(storage, transport, noticeTransport = transport)
        runBlocking { account.restore() }
        val persistence = LocalUserProfilePersistence(temporary.newFolder())
        if (withAvatar) persistence.save(LocalUserProfile(persistence.writeAvatar(byteArrayOf(1, 2, 3))))
        val store = LocalUserProfileStore(persistence) { error("No image") }
        compose.setContent {
            MaterialTheme {
                UserAccountPage(
                    account,
                    false,
                    {},
                    {},
                    avatarStore = store,
                    feedback = {},
                )
            }
        }
        return account
    }
}
