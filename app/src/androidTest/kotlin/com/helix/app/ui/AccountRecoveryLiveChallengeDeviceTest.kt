package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountPasswordRecoveryClient
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Explicit one-send acceptance only. Real bridge/TLS; no stored login, password reset, proof or code logging. */
class AccountRecoveryLiveChallengeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun authorizedSingleEmailWithRealNativeChallenge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recoveryLiveSend") == "true")
        check(android.os.Build.HARDWARE == "ranchu")
        check(instrumentation.targetContext.packageName == "com.helix.agent.developer")
        val email = requireNotNull(args.getString("recoveryEmail"))
        require(email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
        val calls = AtomicInteger()
        val status = AtomicInteger()
        val delegate = OkHttpWireClient(followRedirects = false, maxBodyBytes = 65536)
        val account = AccountSession(emptyStorage(), singleSend(delegate, email, calls, status))
        compose.setContent {
            MaterialTheme {
                AccountPasswordRecoveryForm(
                    AccountPasswordRecoveryClient(account),
                    onBack = {},
                    onSuccess = { error("Password reset is outside this acceptance") },
                )
            }
        }
        compose.onNodeWithTag("account-reset-email").performTextReplacement(email)
        compose.onNodeWithTag("account-send-code").performScrollTo().performClick()
        // The visible real challenge is completed by the explicitly authorized operator, never test injection.
        compose.waitUntil(240_000) { status.get() != 0 }
        assertEquals(1, calls.get())
        assertEquals(200, status.get())
    }

    private fun emptyStorage() =
        object : AccountTokenStorage {
            override fun load(): AccountTokens? = null

            override fun save(tokens: AccountTokens) = error("Login is outside this acceptance")

            override fun clear() = Unit
        }

    private fun singleSend(
        delegate: WireClient,
        email: String,
        calls: AtomicInteger,
        status: AtomicInteger,
    ) = object : WireClient {
        override suspend fun open(request: WireRequest): WireResponse {
            check(request.url == "https://accounts.hilex.me/auth/password-reset/code")
            check(calls.incrementAndGet() == 1) { "Only one authorized send" }
            check(request.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
            val body = Json.parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8)).jsonObject
            check(body.getValue("email").jsonPrimitive.content == email)
            return delegate.open(request).also { status.set(it.status) }
        }
    }
}
