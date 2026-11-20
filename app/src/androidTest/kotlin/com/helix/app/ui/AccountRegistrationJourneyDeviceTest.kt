package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Full account entry with synthetic registration responses; never sends email or creates a real account. */
class AccountRegistrationJourneyDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val registrations = AtomicInteger()

    @Test fun successfulRegistrationReturnsToLoginKeepsEmailAndClearsSecrets() {
        show(201)
        register()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("account-confirm-password").fetchSemanticsNodes().isEmpty()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("account-submit").assertTextEquals(context.getString(R.string.account_login))
        compose.onNodeWithTag("account-email").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("fixture@example.test")),
        )
        assertEmptyPassword()
        compose.onNodeWithTag("account-display-name").assertDoesNotExist()
        compose.onNodeWithTag("account-email-code").assertDoesNotExist()
        assertEquals(1, registrations.get())
    }

    @Test fun rejectedRegistrationStaysInFormAndModeSwitchClearsItsError() {
        show(422)
        register()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("account-failure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-confirm-password").assertExists()
        assertEmptyPassword()
        compose.onNodeWithTag("account-switch-mode").performScrollTo().performClick()
        compose.onNodeWithTag("account-failure").assertDoesNotExist()
        compose.onNodeWithTag("account-confirm-password").assertDoesNotExist()
        assertEquals(1, registrations.get())
    }

    private fun assertEmptyPassword() {
        compose.onNodeWithTag("account-password").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    private fun register() {
        compose.onNodeWithTag("account-entry").performClick()
        compose.onNodeWithTag("account-switch-mode").performScrollTo().performClick()
        compose.onNodeWithTag("account-email").performScrollTo().performTextReplacement("fixture@example.test")
        compose.onNodeWithTag("account-password").performScrollTo().performTextReplacement("synthetic-password")
        compose.onNodeWithTag("account-confirm-password").performScrollTo().performTextReplacement("synthetic-password")
        compose.onNodeWithTag("account-email-code").performScrollTo().performTextReplacement("012345")
        compose
            .onNodeWithTag("account-submit")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
    }

    private fun show(registrationStatus: Int) {
        val storage =
            object : AccountTokenStorage {
                override fun load(): AccountTokens? = null

                override fun save(tokens: AccountTokens) = error("Registration must not log in automatically")

                override fun clear() = Unit
            }
        val transport =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse =
                    when {
                        request.url.endsWith("/auth/registration-info") -> {
                            response(
                                200,
                                """{"registration_enabled":true,"email_verification":true}""",
                            )
                        }

                        request.url.endsWith("/auth/register") -> {
                            registrations.incrementAndGet()
                            response(
                                registrationStatus,
                                if (registrationStatus ==
                                    201
                                ) {
                                    "{}"
                                } else {
                                    """{"error_code":"email_in_use"}"""
                                },
                            )
                        }

                        else -> {
                            error("Unexpected external request")
                        }
                    }
            }
        val account = AccountSession(storage, transport, noticeTransport = transport)
        runBlocking { account.restore() }
        compose.setContent {
            MaterialTheme {
                AccountAccessEntry(AccountEntryPresentation.SETTINGS, account, {}, {}, readUsage = {
                    error("Usage is not part of registration")
                })
            }
        }
    }

    private fun response(
        status: Int,
        payload: String,
    ) = WireResponse(
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
