package com.helix.app.ui

import com.helix.app.account.AccountCaptchaAction
import com.helix.app.account.AccountCaptchaProof
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailCodeVerificationTest {
    private val context = EmailCodeContext("a@example.test", 1, AccountCaptchaAction.REGISTRATION, true)
    private val attempt = EmailCaptchaAttempt(context) { _, _ -> error("No service call in verification state") }
    private val state = EmailCodeVerification()

    @Test fun duplicateCompletionDoesNotDiscardAcceptedProofAndTakeIsSingleUse() {
        val proof = AccountCaptchaProof(context.action, "synthetic")
        state.begin(attempt)
        state.complete(attempt, proof, context)
        state.complete(attempt, proof, context)
        assertTrue(state.ready(context))
        assertNotNull(state.take(context))
        assertNull(state.take(context))
        proof.discard()
    }

    @Test fun cancelledAndDifferentPurposeProofsCannotEnableSend() {
        val late = AccountCaptchaProof(context.action, "synthetic")
        state.begin(attempt)
        state.cancel(attempt)
        state.complete(attempt, late, context)
        assertFalse(late.isUsable(context.action))
        assertFalse(state.ready(context))
        state.begin(attempt)
        state.complete(attempt, AccountCaptchaProof(AccountCaptchaAction.PASSWORD_RESET, "synthetic"), context)
        assertFalse(state.ready(context))
    }

    @Test fun expiryIsCheckedOnSendEvenBeforeUiTimerTicks() {
        var now = 0L
        val proof = AccountCaptchaProof(context.action, "synthetic") { now }
        state.begin(attempt)
        state.complete(attempt, proof, context)
        now = 300_000
        assertNull(state.take(context))
        assertTrue(state.expired)
        assertFalse(proof.isUsable(context.action))
    }

    @Test fun contextChangeCannotRetargetReadyProof() {
        val proof = AccountCaptchaProof(context.action, "synthetic")
        state.begin(attempt)
        state.complete(attempt, proof, context)
        val other = EmailCodeContext(context.email, 2, context.action, true)
        assertFalse(state.ready(other))
        assertNull(state.take(other))
        assertFalse(proof.isUsable(context.action))
    }
}
