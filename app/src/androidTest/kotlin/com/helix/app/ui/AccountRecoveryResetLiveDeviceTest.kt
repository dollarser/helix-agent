package com.helix.app.ui

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountPasswordRecoveryClient
import com.helix.app.account.AccountProfileClient
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Separately authorized live mutation. User types all secrets; no screenshots, secret dumps or daily storage. */
class AccountRecoveryResetLiveDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun authorizedResetThenUserEnteredNewPasswordLogin() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recoveryLiveReset") == "true")
        check(android.os.Build.HARDWARE == "ranchu")
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.helix.agent.developer")
        val email = requireNotNull(args.getString("recoveryEmail"))
        val transport = RecoveryAcceptanceTransport(email)
        val account = AccountSession(RecoveryMemoryStorage(), transport)
        val finished = CountDownLatch(1)
        val passed = AtomicBoolean(false)
        compose.setContent {
            MaterialTheme { RecoveryAcceptanceFlow(account, email, finished, passed) }
        }
        compose.onNodeWithTag("account-reset-email").performTextReplacement(email)
        compose.onNodeWithTag("account-verify-human").performScrollTo().performClick()
        reportRecoveryStage("CHALLENGE_OPEN")
        // Interactive handoff only. Never inspect editable values after opening the challenge.
        compose.waitUntil(15 * 60_000L) { finished.count == 0L }
        assertTrue("Reset/login acceptance did not complete", passed.get())
        check(transport.codeCalls.get() == 1 && transport.resetCalls.get() == 1)
    }
}

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
private fun RecoveryAcceptanceFlow(
    account: AccountSession,
    email: String,
    finished: CountDownLatch,
    passed: AtomicBoolean,
) {
    var reset by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    if (!reset) {
        AccountPasswordRecoveryForm(
            AccountPasswordRecoveryClient(account),
            onBack = {
                reportRecoveryStage("CANCELLED")
                finished.countDown()
            },
            onSuccess = {
                reset = true
                reportRecoveryStage("RESET_COMPLETED")
            },
        )
    } else {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("密码已重置。请手动输入账号和新密码，验证登录。")
            Text(message)
            AccountLoginForm(loading, registrationEnabled = false) { name, password, registration, _, _ ->
                check(!registration)
                scope.launch {
                    loading = true
                    try {
                        account.login(name, password)
                        AccountProfileClient(account).refreshUser()
                        check(
                            account.user.value
                                ?.email
                                ?.trim()
                                ?.lowercase() == email,
                        )
                        passed.set(true)
                        reportRecoveryStage("NEW_PASSWORD_LOGIN_VERIFIED")
                        finished.countDown()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = "登录验证未完成；请停止并报告，不重复提交。"
                        reportRecoveryStage("LOGIN_NOT_VERIFIED")
                        finished.countDown()
                    } finally {
                        loading = false
                    }
                }
            }
        }
    }
}

private class RecoveryMemoryStorage : AccountTokenStorage {
    private var tokens: AccountTokens? = null

    override fun load() = tokens

    override fun save(tokens: AccountTokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}

private fun reportRecoveryStage(stage: String) {
    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("recoveryStage", stage) })
}

private class RecoveryAcceptanceTransport(
    private val email: String,
) : WireClient {
    val codeCalls = AtomicInteger()
    val resetCalls = AtomicInteger()
    private val loginCalls = AtomicInteger()
    private val delegate = OkHttpWireClient(followRedirects = false, maxBodyBytes = 65536)

    override suspend fun open(request: WireRequest): WireResponse {
        val path = request.url.removePrefix("https://accounts.hilex.me")
        check(request.url == "https://accounts.hilex.me$path")
        when (path) {
            "/auth/password-reset/code" -> {
                validateMutation(request, codeCalls, false)
            }

            "/auth/password-reset" -> {
                validateMutation(request, resetCalls, true)
            }

            "/auth/login" -> {
                check(resetCalls.get() == 1 && loginCalls.incrementAndGet() == 1)
                val body = Json.parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8)).jsonObject
                check(body.getValue("email").jsonPrimitive.content == email)
            }

            "/account/me" -> {
                check(loginCalls.get() == 1 && request.method == "GET")
            }

            else -> {
                error("Endpoint outside acceptance")
            }
        }
        return delegate.open(request).also {
            reportRecoveryStage("${path.substringAfterLast('/').uppercase()}_HTTP_${it.status}")
        }
    }

    private fun validateMutation(
        request: WireRequest,
        count: AtomicInteger,
        reset: Boolean,
    ) {
        check(count.incrementAndGet() == 1) { "Only one authorized mutation" }
        check(request.method == "POST")
        check(request.headers.keys.none { it.equals("Authorization", true) })
        val body = Json.parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8)).jsonObject
        check(body.getValue("email").jsonPrimitive.content == email)
        if (reset) check(body.getValue("revoke_api_keys").toString() == "false")
    }
}
