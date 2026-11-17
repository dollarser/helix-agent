package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.account.AccountProfileClient
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.app.account.AccountUser
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic transport and in-memory credentials only. No real password mutation or installed account access. */
class AccountPasswordChangeDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val posts = AtomicInteger()
    private val completed = AtomicInteger()
    private val resume = CompletableDeferred<Unit>()

    @Test fun confirmationAndBusyStatePreventInvalidOrDuplicateSubmission() {
        val account = account()
        show(account)
        fill()
        compose.onNodeWithTag("password-new").performTextReplacement("      ")
        compose.onNodeWithTag("password-confirm").performTextReplacement("      ")
        compose.onNodeWithTag("password-change-submit").performScrollTo().assertIsNotEnabled()
        fill()
        compose.onNodeWithTag("password-confirm").performTextReplacement("mismatch")
        compose.onNodeWithTag("password-change-submit").performScrollTo().assertIsNotEnabled()
        assertEquals(0, posts.get())
        compose.onNodeWithTag("password-confirm").performScrollTo().performTextReplacement(" new secret ")
        compose
            .onNodeWithTag("password-change-submit")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(5000) { posts.get() == 1 }
        compose.onNodeWithTag("password-change-submit").assertIsNotEnabled()
        compose.onNodeWithTag("password-current").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { resume.complete(Unit) }
        compose.waitUntil(5000) { completed.get() == 1 }
        emptyFields()
        assertEquals(1, posts.get())
    }

    @Test fun identityChangeClearsSecretsWithoutSendingPasswordMutation() {
        val account = account()
        show(account)
        fill()
        runBlocking { account.login("other-user", "synthetic") }
        compose.waitForIdle()
        emptyFields()
        compose.onNodeWithTag("password-change-submit").performScrollTo().assertIsNotEnabled()
        assertEquals(0, posts.get())
    }

    @Test fun signedInAccountPageOffersOptionalChangeAndCancellationDoesNotSend() {
        val account = account()
        compose.setContent {
            MaterialTheme {
                UserAccountDestinations(account, false, true, false, {}, {}, {}, {}, feedback = {})
            }
        }
        compose.onNodeWithTag("profile-change-password").performScrollTo().performClick()
        compose.onNodeWithTag("password-change-page").assertExists()
        fill()
        compose.onNodeWithTag("password-change-page-back").performClick()
        compose.onNodeWithTag("profile-change-password").performScrollTo().performClick()
        emptyFields()
        assertEquals(0, posts.get())
    }

    @Test fun confirmedChangeWithFailedSignInRoutesToLoginWithoutRepeatingMutation() {
        val account = account(loginFailure = true)
        val signIn = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccountPasswordChangeForm(account, AccountProfileClient(account), onSignInRequired = {
                        signIn.incrementAndGet()
                    })
                }
            }
        }
        fill()
        compose.onNodeWithTag("password-change-submit").performScrollTo().performClick()
        compose.waitUntil(5000) { posts.get() == 1 }
        compose.runOnIdle { resume.complete(Unit) }
        compose.waitUntil(5000) { signIn.get() == 1 }
        assertEquals(false, account.signedIn.value)
        assertEquals(1, posts.get())
        emptyFields()
    }

    @Test fun requiredChangeUsesSameConfirmationGuard() {
        val account = account(mustChange = true)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccountIdentityActions(account, AccountProfileClient(account))
                }
            }
        }
        fill()
        compose.onNodeWithTag("password-confirm").performTextReplacement("different")
        compose.onNodeWithTag("password-change-submit").performScrollTo().assertIsNotEnabled()
        assertEquals(0, posts.get())
    }

    private fun show(account: AccountSession) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccountPasswordChangeForm(
                        account,
                        AccountProfileClient(account),
                        operationScope = rememberCoroutineScope(),
                        onSuccess = { completed.incrementAndGet() },
                    )
                }
            }
        }
    }

    private fun fill() {
        compose.onNodeWithTag("password-current").performScrollTo().performTextReplacement(" old secret ")
        compose.onNodeWithTag("password-new").performScrollTo().performTextReplacement(" new secret ")
        compose.onNodeWithTag("password-confirm").performScrollTo().performTextReplacement(" new secret ")
    }

    private fun emptyFields() {
        for (tag in listOf("password-current", "password-new", "password-confirm")) {
            compose.onNodeWithTag(tag).performScrollTo().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
        }
    }

    private fun account(
        loginFailure: Boolean = false,
        mustChange: Boolean = false,
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
                        AccountUser("synthetic-user", "original-name", "synthetic@example.test", mustChange, false),
                    )

                override fun load() = value

                override fun save(tokens: AccountTokens) {
                    value = tokens
                }

                override fun clear() {
                    value = null
                }
            }
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    if (request.url.endsWith("/account/password")) {
                        posts.incrementAndGet()
                        resume.await()
                        return response("{}")
                    }
                    check(request.url.endsWith("/auth/login"))
                    if (loginFailure) throw java.io.IOException("Synthetic login unavailable")
                    return response(
                        """{"access_token":"synthetic-next","refresh_token":"synthetic-next-refresh",
                    "expires_in":3600,"refresh_expires_in":7200,"user":{"id":"synthetic-user",
                    "username":"original-name","email":"synthetic@example.test","must_change":false,
                    "email_binding_required":false}}""",
                    )
                }
            }
        return AccountSession(storage, wire).also { runBlocking { it.restore() } }
    }

    private fun response(payload: String) =
        WireResponse(
            200,
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
