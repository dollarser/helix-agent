package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.account.AccountCaptchaProof

internal class EmailCaptchaAttempt(
    val context: EmailCodeContext,
    val send: suspend (String, AccountCaptchaProof) -> Long,
)

internal class VerifiedEmailAttempt(
    val attempt: EmailCaptchaAttempt,
    val proof: AccountCaptchaProof,
)

/** Composition-owned state only. Never persisted with the form or reused after an attempted send. */
internal class EmailCodeVerification {
    var pending by mutableStateOf<EmailCaptchaAttempt?>(null)
        private set
    private var verified by mutableStateOf<VerifiedEmailAttempt?>(null)
    var expired by mutableStateOf(false)
        private set

    fun clear() {
        verified?.proof?.discard()
        verified = null
        pending = null
        expired = false
    }

    fun begin(attempt: EmailCaptchaAttempt) {
        clear()
        pending = attempt
    }

    fun cancel(attempt: EmailCaptchaAttempt) {
        if (pending === attempt) clear()
    }

    fun complete(
        attempt: EmailCaptchaAttempt,
        proof: AccountCaptchaProof,
        current: EmailCodeContext,
    ) {
        if (pending !== attempt || !current.accepts(attempt.context)) {
            if (verified?.proof !== proof) proof.discard()
            return
        }
        pending = null
        if (proof.isUsable(current.action)) {
            verified = VerifiedEmailAttempt(attempt, proof)
        } else {
            proof.discard()
            expired = true
        }
    }

    fun ready(current: EmailCodeContext): Boolean =
        verified?.let { current.accepts(it.attempt.context) && it.proof.isUsable(current.action) } == true

    fun expire() {
        verified?.let {
            if (!it.proof.isUsable(it.attempt.context.action)) {
                clear()
                expired = true
            }
        }
    }

    fun take(current: EmailCodeContext): VerifiedEmailAttempt? {
        if (!ready(current)) {
            clear()
            expired = true
            return null
        }
        return verified.also { verified = null }
    }
}
