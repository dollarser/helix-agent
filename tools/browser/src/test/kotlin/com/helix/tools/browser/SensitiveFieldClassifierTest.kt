package com.helix.tools.browser

import com.helix.tools.browser.SensitiveFieldClassifier.Refusal
import com.helix.tools.browser.SensitiveFieldClassifier.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HXA-062 (verification matrix row `:tools:browser:test`): the host-side sensitive-field policy
 * (doc 09 §3.3 密码框、支付框和验证码框默认拒绝 + §3.4 支付/账号恢复/验证码页面禁止自主点击或输入).
 *
 * The closed heuristic is a pure JVM table: password first, then payment, then one-time-code
 * (first match wins); bare “code” is deliberately NOT a trigger (it would refuse postal/coupon
 * codes). The SAME rules are duplicated verbatim in the fixed action script
 * (:feature:browser `BrowserActionScript`); the two-sided pin is this test (Kotlin side) plus
 * `BrowserActionScriptTest` (asserts the JS carries these triggers) plus the on-device refusal
 * test (asserts the runtime agrees).
 */
class SensitiveFieldClassifierTest {
    private fun classify(
        tag: String = "input",
        type: String = "text",
        autocomplete: String = "",
        nameId: String = "",
        placeholder: String = "",
    ): Verdict = SensitiveFieldClassifier.classify(tag, type, autocomplete, nameId, placeholder)

    private fun refusalOf(v: Verdict): Refusal? =
        when (v) {
            is Verdict.Normal -> null
            is Verdict.Sensitive -> v.reason
        }

    // ── password ───────────────────────────────────────────────────────────────────────

    @Test
    fun aPasswordFieldIsRefusedInEveryContext() {
        assertEquals(Refusal.PASSWORD, refusalOf(classify(type = "password")))
        assertEquals(Refusal.PASSWORD, refusalOf(classify(autocomplete = "password")))
        assertEquals(Refusal.PASSWORD, refusalOf(classify(autocomplete = "current-password")))
        // password wins even when a payment signal is also present (first-match ordering).
        assertEquals(Refusal.PASSWORD, refusalOf(classify(type = "password", autocomplete = "cc-number")))
    }

    // ── payment ────────────────────────────────────────────────────────────────────────

    @Test
    fun aPaymentFieldIsRefusedByAutocompletePrefix() {
        assertEquals(Refusal.PAYMENT, refusalOf(classify(autocomplete = "cc-number")))
        assertEquals(Refusal.PAYMENT, refusalOf(classify(autocomplete = "cc-exp")))
    }

    @Test
    fun aPaymentFieldIsRefusedByAutocompleteExact() {
        assertEquals(Refusal.PAYMENT, refusalOf(classify(autocomplete = "credit-card")))
        assertEquals(Refusal.PAYMENT, refusalOf(classify(autocomplete = "on-card")))
    }

    @Test
    fun aPaymentFieldIsRefusedByNameOrPlaceholder() {
        assertEquals(Refusal.PAYMENT, refusalOf(classify(nameId = "cardNumber")))
        assertEquals(Refusal.PAYMENT, refusalOf(classify(nameId = "card-number")))
        assertEquals(Refusal.PAYMENT, refusalOf(classify(nameId = "expiry")))
        assertEquals(Refusal.PAYMENT, refusalOf(classify(nameId = "cvv")))
        assertEquals(Refusal.PAYMENT, refusalOf(classify(placeholder = "Bank account IBAN")))
    }

    // ── one-time-code ──────────────────────────────────────────────────────────────────

    @Test
    fun aOneTimeCodeFieldIsRefusedByAutocomplete() {
        assertEquals(Refusal.ONE_TIME_CODE, refusalOf(classify(autocomplete = "one-time-code")))
    }

    @Test
    fun aOneTimeCodeFieldIsRefusedByAuthStyleNameOnInputTags() {
        assertEquals(Refusal.ONE_TIME_CODE, refusalOf(classify(tag = "input", nameId = "otp")))
        assertEquals(Refusal.ONE_TIME_CODE, refusalOf(classify(tag = "input", nameId = "verification-code")))
        assertEquals(Refusal.ONE_TIME_CODE, refusalOf(classify(tag = "input", nameId = "2fa")))
        assertEquals(Refusal.ONE_TIME_CODE, refusalOf(classify(tag = "textarea", placeholder = "TOTP code")))
    }

    // ── bare “code” is NOT a trigger ───────────────────────────────────────────────────

    @Test
    fun bareCodeIsNotATrigger() {
        assertEquals(Verdict.Normal, classify(tag = "input", nameId = "postal code"))
        assertEquals(Verdict.Normal, classify(tag = "input", nameId = "promo code"))
        assertEquals(Verdict.Normal, classify(tag = "input", nameId = "coupon code"))
    }

    // ── the OTP gate applies only to input tags ────────────────────────────────────────

    @Test
    fun otpStyleNameOnANonInputTagIsNormal() {
        // A div / button is not a typeable field, so the one-time-code gate does not apply
        // (the password / payment gates still do — none fire on these names).
        assertEquals(Verdict.Normal, classify(tag = "div", nameId = "otp"))
        assertEquals(Verdict.Normal, classify(tag = "button", nameId = "captcha"))
    }

    // ── ordinary fields ────────────────────────────────────────────────────────────────

    @Test
    fun anOrdinaryFieldIsNormal() {
        assertEquals(Verdict.Normal, classify(tag = "input", nameId = "username"))
        assertEquals(Verdict.Normal, classify(tag = "input", type = "email", nameId = "email"))
        assertEquals(Verdict.Normal, classify(tag = "input", placeholder = "Search this site"))
        assertEquals(Verdict.Normal, classify(tag = "input", type = "tel", nameId = "phone"))
    }

    // ── reasonOf ───────────────────────────────────────────────────────────────────────

    @Test
    fun reasonOfIsEmptyForNormalAndLowercaseForRefusals() {
        assertEquals("", SensitiveFieldClassifier.reasonOf(Verdict.Normal))
        assertEquals("password", SensitiveFieldClassifier.reasonOf(Verdict.Sensitive(Refusal.PASSWORD)))
        assertEquals("payment", SensitiveFieldClassifier.reasonOf(Verdict.Sensitive(Refusal.PAYMENT)))
        assertEquals("one_time_code", SensitiveFieldClassifier.reasonOf(Verdict.Sensitive(Refusal.ONE_TIME_CODE)))
    }
}
