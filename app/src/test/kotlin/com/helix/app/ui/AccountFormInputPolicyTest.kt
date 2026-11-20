package com.helix.app.ui

import androidx.compose.ui.text.input.KeyboardType
import com.helix.app.account.AccountCaptchaAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFormInputPolicyTest {
    @Test fun secretsUseNonLearningSecretKeyboards() {
        assertEquals(KeyboardType.Password, accountPasswordKeyboard.keyboardType)
        assertEquals(false, accountPasswordKeyboard.autoCorrectEnabled)
        assertEquals(KeyboardType.NumberPassword, accountCodeKeyboard.keyboardType)
        assertEquals(false, accountCodeKeyboard.autoCorrectEnabled)
    }

    @Test fun oldSendResultCannotPublishAfterEditingSwitchingOrReturningToSameAddress() {
        val original = EmailCodeContext("a@example.test", 1, AccountCaptchaAction.EMAIL_BINDING, true)
        assertTrue(original.accepts(original))
        val changed =
            listOf(
                EmailCodeContext("b@example.test", 1, AccountCaptchaAction.EMAIL_BINDING, true),
                EmailCodeContext("a@example.test", 2, AccountCaptchaAction.EMAIL_BINDING, true),
                EmailCodeContext("a@example.test", 1, AccountCaptchaAction.REGISTRATION, true),
                EmailCodeContext("a@example.test", 1, AccountCaptchaAction.EMAIL_BINDING, false),
                EmailCodeContext("a@example.test", 1, AccountCaptchaAction.EMAIL_BINDING, true),
            )
        for (current in changed) assertFalse(current.accepts(original))
    }

    @Test fun resetValidationPreservesPasswordWhitespaceAndLeadingZeroCode() {
        assertTrue(recoveryInputReady("a@example.test", "012345", " 1234 ", " 1234 "))
        assertFalse(recoveryInputReady("a@example.test", "12345", "abcdef", "abcdef"))
        assertFalse(recoveryInputReady("a@example.test", "０１２３４５", "abcdef", "abcdef"))
        assertFalse(recoveryInputReady("a@example.test", "012345", "abcde", "abcde"))
        assertFalse(recoveryInputReady("a@example.test", "012345", "a".repeat(129), "a".repeat(129)))
        assertFalse(recoveryInputReady("a@example.test", "012345", "abcdef", "ABCDEF"))
    }
}
