package com.helix.app.account

import java.util.concurrent.atomic.AtomicReference

internal enum class AccountCaptchaAction(
    val wireValue: String,
) {
    REGISTRATION("registration_code"),
    PASSWORD_RESET("password_reset_code"),
    EMAIL_BINDING("email_binding_code"),
}

/** Memory-only, one attempt. Never a data class: generated diagnostics must not expose the proof. */
internal class AccountCaptchaProof(
    val action: AccountCaptchaAction,
    token: String,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val created = monotonicMillis()
    private val value = AtomicReference(token)

    init {
        require(token.isNotBlank() && token.length <= 2048)
    }

    internal fun consume(expected: AccountCaptchaAction): String {
        val token = value.getAndSet(null)
        require(action == expected && monotonicMillis() - created in 0 until 300_000 && token != null) {
            "Human verification must be completed again"
        }
        return token
    }
}
