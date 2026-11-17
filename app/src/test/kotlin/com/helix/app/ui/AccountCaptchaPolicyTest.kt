package com.helix.app.ui

import com.helix.app.account.AccountCaptchaAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCaptchaPolicyTest {
    private val action = AccountCaptchaAction.PASSWORD_RESET
    private val message = """{"type":"captcha-completed","action":"password_reset_code","token":"synthetic-proof"}"""

    @Test fun trustedMainFrameCanDeliverExactlyOnce() {
        val gate = AccountCaptchaPolicy(action)
        assertNotNull(gate.receive(AccountCaptchaPolicy.ORIGIN, true, gate.url, message))
        assertNull(gate.receive(AccountCaptchaPolicy.ORIGIN, true, gate.url, message))
    }

    @Test fun rejectsOriginFrameActionAndWindowMismatchWithoutConsuming() {
        val gate = AccountCaptchaPolicy(action)
        for (origin in listOf(
            "http://accounts.hilex.me",
            "https://accounts.hilex.me:444",
            "https://accounts.hilex.me.evil.test",
            "https://user@accounts.hilex.me",
            "https://challenges.cloudflare.com",
        )) {
            assertNull(gate.receive(origin, true, gate.url, message))
        }
        assertNull(gate.receive(AccountCaptchaPolicy.ORIGIN, false, gate.url, message))
        assertNull(gate.receive(AccountCaptchaPolicy.ORIGIN, true, "https://accounts.hilex.me/other", message))
        assertNull(
            gate.receive(
                AccountCaptchaPolicy.ORIGIN,
                true,
                gate.url,
                message.replace(
                    "password_reset_code",
                    "registration_code",
                ),
            ),
        )
        assertNotNull(gate.receive("https://accounts.hilex.me:443", true, gate.url, message))
    }

    @Test fun closedOrMalformedDialogCannotDeliverProof() {
        val gate = AccountCaptchaPolicy(action)
        for (value in listOf(
            "[]",
            "not json",
            message.replace(
                "synthetic-proof",
                "",
            ),
            message.replace(
                "synthetic-proof",
                "x".repeat(2049),
            ),
            message.replace(
                "\"synthetic-proof\"",
                "123",
            ),
        )) {
            assertNull(gate.receive(AccountCaptchaPolicy.ORIGIN, true, gate.url, value))
        }
        gate.close()
        assertNull(gate.receive(AccountCaptchaPolicy.ORIGIN, true, gate.url, message))
    }

    @Test fun navigationAllowsOnlyExactChallengeAndRequiredSubresources() {
        val gate = AccountCaptchaPolicy(action)
        assertTrue(gate.navigationAllowed(gate.url, true))
        assertFalse(gate.navigationAllowed(gate.url + "&return_url=https://evil.test", true))
        assertFalse(gate.navigationAllowed("https://challenges.cloudflare.com/", true))
        assertTrue(gate.navigationAllowed("https://challenges.cloudflare.com/turnstile/v0/api.js", false))
        assertTrue(gate.navigationAllowed("about:blank", false))
        assertTrue(gate.navigationAllowed("about:srcdoc", false))
        assertFalse(gate.navigationAllowed("file:///sdcard/private", false))
        assertFalse(gate.navigationAllowed("http://accounts.hilex.me/script.js", false))
        assertFalse(gate.navigationAllowed("https://unrelated.test/script.js", false))
    }
}
