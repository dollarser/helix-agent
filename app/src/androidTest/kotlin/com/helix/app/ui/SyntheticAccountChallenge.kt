package com.helix.app.ui

import androidx.compose.runtime.SideEffect
import com.helix.app.account.AccountCaptchaAction
import com.helix.app.account.AccountCaptchaProof

/** Test-only renderer: no WebView, external CAPTCHA, installed account, or email service. */
internal class SyntheticAccountChallenge {
    private lateinit var action: AccountCaptchaAction
    lateinit var cancel: () -> Unit
        private set
    lateinit var complete: (AccountCaptchaProof) -> Unit
        private set

    val content: AccountCaptchaContent = { requested, dismiss, proof ->
        SideEffect {
            action = requested
            cancel = dismiss
            complete = proof
        }
    }

    fun proof() = AccountCaptchaProof(action, "synthetic-proof")

    fun completeTwice() {
        val value = proof()
        complete(value)
        complete(value)
    }
}
