package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Production navigation with anonymous memory-only auth. Never logs out, sends mail, or resets a password. */
class AccountRecoveryNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun signedOutEntryCanRecoverWithRegistrationClosedAndReturnWithoutLeakingFormState() {
        val reads = AtomicInteger()
        val account = anonymousAccount(reads)
        compose.setContent {
            MaterialTheme {
                Column {
                    Text("Unsent draft", Modifier.testTag("fixture-draft"))
                    AccountAccessEntry(AccountEntryPresentation.MODEL_PICKER, account, {}, {})
                }
            }
        }
        compose.onNodeWithTag("account-entry").performClick()
        compose.waitUntil(5000) { reads.get() > 0 }
        compose.onNodeWithTag("account-forgot-password").performScrollTo().performClick()
        compose.onNodeWithTag("account-recovery").assertIsDisplayed()
        compose.onNodeWithTag("account-reset-email").performTextReplacement("synthetic@example.test")
        compose.onNodeWithTag("account-reset-password").performScrollTo().performTextReplacement("synthetic-only")
        compose.onNodeWithTag("account-reset-submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("account-recovery-back").performClick()
        compose.onNodeWithTag("account-recovery").assertDoesNotExist()
        compose.onNodeWithTag("account-forgot-password").performScrollTo().performClick()
        assertEmpty("account-reset-email")
        assertEmpty("account-reset-password")
        assertEmpty("account-reset-confirm")
        assertEmpty("account-email-code")
        compose.onNodeWithTag("account-recovery-back").performClick()
        compose.onNodeWithTag("user-page-back").performClick()
        compose.onNodeWithTag("fixture-draft").assertTextEquals("Unsent draft")
        compose.onNodeWithTag("user-page").assertDoesNotExist()
        assertFalse(account.signedIn.value)
    }

    private fun assertEmpty(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    private fun anonymousAccount(reads: AtomicInteger) =
        AccountSession(
            object : AccountTokenStorage {
                override fun load(): AccountTokens? = null

                override fun save(tokens: AccountTokens) = error("Navigation cannot save credentials")

                override fun clear() = error("Navigation cannot log out")
            },
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    check(request.url == "https://accounts.hilex.me/auth/registration-info")
                    check(request.body == null) { "Navigation cannot send a mutation" }
                    reads.incrementAndGet()
                    return WireResponse(
                        200,
                        emptyMap(),
                        object : WireBody {
                            override suspend fun bytes() =
                                """{"registration_enabled":false,"email_verification":true}""".toByteArray()

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                onChunk(bytes())
                            }

                            override fun close() = Unit
                        },
                    )
                }
            },
        )
}
