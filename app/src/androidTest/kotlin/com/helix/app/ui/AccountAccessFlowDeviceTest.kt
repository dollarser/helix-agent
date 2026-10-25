package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.R
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountStatus
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.app.chat.SessionModelSelectionResult
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real picker and account UI, with synthetic in-memory auth; no real service or credential storage. */
class AccountAccessFlowDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var saves = 0
    private var serviceFailureLabel = ""

    private fun picker(account: AccountSession) {
        val draft = mutableStateOf("Unsent draft")
        compose.setContent {
            MaterialTheme {
                serviceFailureLabel = stringResource(R.string.account_error_service)
                Column {
                    OutlinedTextField(draft.value, { draft.value = it }, Modifier.testTag("fixture-draft"))
                    ComposerModelMenu(
                        providers = emptyList(),
                        providerId = null,
                        model = null,
                        enabled = true,
                        onSave = { _, _, _ ->
                            saves++
                            SessionModelSelectionResult.APPLIED
                        },
                        accountEntry = { changed ->
                            AccountAccessEntry(AccountEntryPresentation.MODEL_PICKER, account, {}, changed)
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-search").performTextReplacement("My model query")
        compose.onNodeWithTag("account-entry").performClick()
    }

    private fun submit() {
        compose.onNodeWithTag("account-email").performTextReplacement("synthetic@example.test")
        compose.onNodeWithTag("account-password").performTextReplacement("synthetic-password")
        compose.onNodeWithTag("account-submit").performScrollTo().performClick()
    }

    private fun assertOriginalTask() {
        compose
            .onNodeWithTag(
                "chat-model-search",
            ).assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("My model query")),
            )
        compose.onNodeWithTag("chat-model-picker-cancel").performClick()
        compose.onNodeWithTag("fixture-draft").assertTextEquals("Unsent draft")
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun successfulLoginReturnsToOriginalPickerWithoutSavingOrSending() {
        val account = fakeAccount { 200 }
        picker(account)
        submit()
        compose.waitUntil(10000) { account.status.value == AccountStatus.SIGNED_IN }
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("account-sheet").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("account-sheet").assertDoesNotExist()
        assertOriginalTask()
    }

    @Test fun failedLoginStaysOpenAndCancelReturnsWithoutLosingDraftOrQuery() {
        val account = fakeAccount { 401 }
        picker(account)
        submit()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("account-failure").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("account-sheet").assertIsDisplayed()
        compose.onNodeWithTag("account-close").performClick()
        assertOriginalTask()
        assertEquals(AccountStatus.SIGNED_OUT, account.status.value)
    }

    @Test fun unavailableServiceShowsSafeHintAndExplicitRetryRecovers() {
        var status = 530
        val account = fakeAccount { status }
        picker(account)
        submit()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("account-failure").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("account-failure")
            .assertTextEquals(serviceFailureLabel)
        compose.runOnIdle { status = 200 }
        compose.onNodeWithTag("account-submit").performScrollTo().performClick()
        compose.waitUntil(10000) { account.signedIn.value }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("account-sheet").fetchSemanticsNodes().isEmpty() }
        assertOriginalTask()
    }

    private fun fakeAccount(status: () -> Int): AccountSession =
        AccountSession(
            object : AccountTokenStorage {
                private var value: AccountTokens? = null

                override fun load() = value

                override fun save(tokens: AccountTokens) {
                    value = tokens
                }

                override fun clear() {
                    value = null
                }
            },
            object : WireClient {
                override suspend fun open(request: WireRequest) =
                    WireResponse(
                        status(),
                        emptyMap(),
                        object : WireBody {
                            override suspend fun bytes() =
                                """
                                {"access_token":"fixture-access","refresh_token":"fixture-refresh",
                                 "expires_in":600,"refresh_expires_in":1200}
                                """.trimIndent().toByteArray()

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                onChunk(bytes())
                            }

                            override fun close() = Unit
                        },
                    )
            },
        )
}
