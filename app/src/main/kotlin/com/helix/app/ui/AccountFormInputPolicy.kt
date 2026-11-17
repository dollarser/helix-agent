package com.helix.app.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.helix.app.account.AccountCaptchaAction

internal val accountPasswordKeyboard =
    KeyboardOptions(
        keyboardType = KeyboardType.Password,
        autoCorrectEnabled = false,
    )
internal val accountCodeKeyboard =
    KeyboardOptions(
        keyboardType = KeyboardType.NumberPassword,
        autoCorrectEnabled = false,
    )

/** Immutable request identity, never a source of authorization. */
internal class EmailCodeContext(
    val email: String,
    val identity: Long,
    val action: AccountCaptchaAction,
    val enabled: Boolean,
) {
    fun accepts(result: EmailCodeContext): Boolean = enabled && this === result
}

internal fun recoveryInputReady(
    email: String,
    code: String,
    password: String,
    confirmation: String,
): Boolean = recoveryAddressAndCodeValid(email, code) && password.length in 6..128 && password == confirmation

private fun recoveryAddressAndCodeValid(
    email: String,
    code: String,
): Boolean = email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) && code.matches(Regex("[0-9]{6}"))

internal fun recoveryPasswordsMismatch(
    password: String,
    confirmation: String,
): Boolean = confirmation.isNotEmpty() && password != confirmation
