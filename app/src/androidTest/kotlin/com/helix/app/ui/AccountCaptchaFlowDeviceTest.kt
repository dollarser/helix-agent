package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.account.AccountCaptchaAction
import com.helix.app.account.AccountCaptchaProof
import com.helix.app.account.AccountDataFailure
import com.helix.app.account.AccountRequestFailure
import com.helix.provider.api.wire.HttpFailureDetails
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** UI lifecycle with synthetic proofs only; strict origin/frame checks remain in AccountCaptchaPolicyTest. */
class AccountCaptchaFlowDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val challenge = SyntheticAccountChallenge()
    private val email = mutableStateOf("first@example.test")
    private val identity = mutableStateOf(1L)
    private val sends = AtomicInteger()

    private fun form(
        action: AccountCaptchaAction = AccountCaptchaAction.PASSWORD_RESET,
        send: suspend (String, AccountCaptchaProof) -> Long,
    ) {
        compose.setContent {
            MaterialTheme {
                Column {
                    AccountEmailCodeField(
                        email.value,
                        "",
                        {},
                        true,
                        send,
                        action = action,
                        identity = identity.value,
                        challenge = challenge.content,
                    )
                }
            }
        }
    }

    private fun open() = compose.onNodeWithTag("account-send-code").performClick()

    private fun noFeedback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.account_code_accepted)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.account_failure)).assertDoesNotExist()
    }

    @Test fun cancellingChallengeRejectsLateProofWithoutSending() {
        form { _, _ ->
            sends.incrementAndGet()
            0
        }
        open()
        compose.runOnIdle {
            val late = challenge.complete
            val proof = challenge.proof()
            challenge.cancel()
            late(proof)
        }
        compose.onNodeWithTag("account-send-code").assertIsEnabled()
        assertEquals(0, sends.get())
        noFeedback()
    }

    @Test fun duplicateProofCallbackStartsOnlyOneSend() {
        form { _, proof ->
            proof.consume(AccountCaptchaAction.PASSWORD_RESET)
            sends.incrementAndGet()
            0
        }
        open()
        compose.runOnIdle { challenge.completeTwice() }
        compose.waitUntil(5000) { sends.get() == 1 }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.account_code_accepted)).assertExists()
        assertEquals(1, sends.get())
    }

    @Test fun changedEmailRejectsLateChallengeProof() {
        form { _, _ ->
            sends.incrementAndGet()
            0
        }
        open()
        compose.runOnIdle { email.value = "second@example.test" }
        compose.waitForIdle()
        compose.runOnIdle { challenge.complete(challenge.proof()) }
        compose.onNodeWithTag("account-send-code").assertIsEnabled()
        assertEquals(0, sends.get())
    }

    @Test fun changedAccountRejectsLateChallengeProof() {
        form { _, _ ->
            sends.incrementAndGet()
            0
        }
        open()
        compose.runOnIdle { identity.value = 2L }
        compose.waitForIdle()
        compose.runOnIdle { challenge.complete(challenge.proof()) }
        assertEquals(0, sends.get())
    }

    @Test fun emailRoundTripDoesNotPublishOldSuccessfulSend() {
        staleResult(failure = false) {
            compose.runOnIdle { email.value = "second@example.test" }
            compose.waitForIdle()
            compose.runOnIdle { email.value = "first@example.test" }
        }
    }

    @Test fun accountSwitchDoesNotPublishOldFailedSend() {
        staleResult(failure = true) { compose.runOnIdle { identity.value = 2L } }
    }

    @Test fun completedCooldownDoesNotFollowAccountSwitch() {
        form(action = AccountCaptchaAction.EMAIL_BINDING) { _, proof ->
            proof.consume(AccountCaptchaAction.EMAIL_BINDING)
            sends.incrementAndGet()
            60
        }
        open()
        compose.runOnIdle { challenge.complete(challenge.proof()) }
        compose.waitUntil(5000) { sends.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("account-send-code").assertIsNotEnabled()
        compose.runOnIdle { identity.value = 2L }
        compose.waitForIdle()
        compose.onNodeWithTag("account-send-code").assertIsEnabled()
        noFeedback()
    }

    @Test fun accountSwitchRejectsOldSuccessfulNonzeroCooldown() {
        staleResult(failure = false, waitSeconds = 60) { compose.runOnIdle { identity.value = 2L } }
    }

    @Test fun accountSwitchRejectsOldRateLimitNonzeroCooldown() {
        staleResult(failure = true, waitSeconds = 60) { compose.runOnIdle { identity.value = 2L } }
    }

    private fun staleResult(
        failure: Boolean,
        waitSeconds: Long = 0,
        change: () -> Unit,
    ) {
        val response = CompletableDeferred<Unit>()
        form(action = AccountCaptchaAction.EMAIL_BINDING) { _, proof ->
            proof.consume(AccountCaptchaAction.EMAIL_BINDING)
            sends.incrementAndGet()
            response.await()
            if (failure && waitSeconds > 0) {
                throw AccountRequestFailure(
                    HttpFailureDetails(429, retryAfterMillis = waitSeconds * 1000),
                )
            }
            if (failure) throw AccountDataFailure()
            waitSeconds
        }
        open()
        compose.runOnIdle { challenge.complete(challenge.proof()) }
        compose.waitUntil(5000) { sends.get() == 1 }
        change()
        compose.waitForIdle()
        compose.runOnIdle { response.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("account-send-code").assertIsEnabled()
        noFeedback()
        assertEquals(1, sends.get())
    }
}
