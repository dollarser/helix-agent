package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.account.AccountProfileClient
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.app.account.AccountUser
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic session and transport only. Never reads/replaces the installed account or sends email. */
class AccountEmailBindingDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val sends = AtomicInteger()

    private fun account(): AccountSession {
        val session =
            AccountSession(
                object : AccountTokenStorage {
                    private var tokens: AccountTokens? =
                        AccountTokens(
                            "test-access",
                            "test-refresh",
                            Long.MAX_VALUE,
                            Long.MAX_VALUE,
                            "synthetic",
                            AccountUser("synthetic-user", "original-name", "synthetic@example.test", false, true),
                        )

                    override fun load() = tokens

                    override fun save(tokens: AccountTokens) {
                        this.tokens = tokens
                    }

                    override fun clear() {
                        tokens = null
                    }
                },
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        sends.incrementAndGet()
                        assertEquals("https://accounts.hilex.me/account/email/code", request.url)
                        return WireResponse(
                            200,
                            emptyMap(),
                            object : WireBody {
                                override suspend fun bytes() = """{"retry_after":60,"expires_in":600}""".toByteArray()

                                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                    onChunk(bytes())
                                }

                                override fun close() = Unit
                            },
                        )
                    }
                },
            )
        runBlocking { session.restore() }
        return session
    }

    @Test fun bindingIsOptionalAndNeverSendsOnOpen() {
        val account = account()
        compose.setContent { MaterialTheme { AccountIdentityActions(account, AccountProfileClient(account)) } }
        compose.onNodeWithTag("account-bind-open").assertExists()
        assertEquals(0, sends.get())
        compose.onNodeWithTag("account-bind-later").performClick()
        compose.onNodeWithTag("account-bind-open").assertDoesNotExist()
        assertEquals(0, sends.get())
        assertTrue(account.signedIn.value)
    }

    @Test fun codeIsSentOnlyByExplicitTapAndCannotImmediatelyResend() {
        val account = account()
        compose.setContent { MaterialTheme { AccountIdentityActions(account, AccountProfileClient(account)) } }
        compose.onNodeWithTag("account-bind-open").performClick()
        assertEquals(0, sends.get())
        compose.onNodeWithTag("account-send-code").performClick()
        compose.waitUntil(5000) { sends.get() == 1 }
        compose.onNodeWithTag("account-send-code").assertIsNotEnabled()
        assertEquals("original-name", account.user.value!!.username)
        assertTrue(account.signedIn.value)
    }
}
