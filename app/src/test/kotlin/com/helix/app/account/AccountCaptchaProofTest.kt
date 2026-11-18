package com.helix.app.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCaptchaProofTest {
    @Test fun expiryAndConsumptionAreCheckedAtUse() {
        var now = 0L
        val proof = AccountCaptchaProof(AccountCaptchaAction.REGISTRATION, "synthetic") { now }
        now = 299_999
        assertTrue(proof.isUsable(AccountCaptchaAction.REGISTRATION))
        now = 300_000
        assertFalse(proof.isUsable(AccountCaptchaAction.REGISTRATION))
        assertThrows(IllegalArgumentException::class.java) { proof.consume(AccountCaptchaAction.REGISTRATION) }
    }

    @Test fun wrongPurposeAndDiscardCannotBeReused() {
        val proof = AccountCaptchaProof(AccountCaptchaAction.PASSWORD_RESET, "synthetic")
        assertFalse(proof.isUsable(AccountCaptchaAction.REGISTRATION))
        proof.discard()
        assertFalse(proof.isUsable(AccountCaptchaAction.PASSWORD_RESET))
        assertThrows(IllegalArgumentException::class.java) { proof.consume(AccountCaptchaAction.PASSWORD_RESET) }
    }

    @Test fun successfulConsumptionIsSingleUse() {
        val proof = AccountCaptchaProof(AccountCaptchaAction.EMAIL_BINDING, "synthetic")
        proof.consume(AccountCaptchaAction.EMAIL_BINDING)
        assertFalse(proof.isUsable(AccountCaptchaAction.EMAIL_BINDING))
        assertThrows(IllegalArgumentException::class.java) { proof.consume(AccountCaptchaAction.EMAIL_BINDING) }
    }
}
