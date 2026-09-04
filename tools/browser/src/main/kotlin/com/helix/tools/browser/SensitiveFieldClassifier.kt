package com.helix.tools.browser

/**
 * The trusted host-side sensitive-field policy for `browser.click` / `browser.type` (HXA-062;
 * doc 09 §3.3 “密码框、支付框和验证码框默认拒绝” + §3.4 “对支付、账号恢复、系统权限、验证码和生物
 * 识别页面禁止自主点击或输入”).
 *
 * This is the host's re-validation. The fixed action script (in :feature:browser) refuses
 * sensitive fields as its runtime gate, and the host independently re-classifies the SAME field
 * attributes the script returned: an action is PERFORMED only when both agree the field is normal
 * (fail-closed on any disagreement). The rule is a closed, deterministic heuristic over the
 * field's (tag, type, autocomplete, name/id, placeholder) — pure JVM, so it is unit-tested here
 * and mirrored verbatim by the fixed script (exercised on device).
 *
 * Bias: when in doubt, REFUSE (a false refusal costs one manual retry; a false allow types into
 * a payment / OTP / password field). Bare “code” is deliberately NOT a trigger (it would refuse
 * postal / coupon codes); the one-time-code rule requires an explicit auth-style name.
 */
object SensitiveFieldClassifier {
    enum class Refusal { PASSWORD, PAYMENT, ONE_TIME_CODE }

    sealed interface Verdict {
        data object Normal : Verdict

        data class Sensitive(
            val reason: Refusal,
        ) : Verdict
    }

    /** The field tags that can hold a typed value (the OTP gate applies only to these). */
    private val INPUT_TAGS = setOf("input", "select", "textarea")

    // The name/placeholder/payment + one-time-code signals. IGNORE_CASE. These strings are
    // duplicated verbatim inside the fixed action script (BrowserActionScript); the two
    // implementations are pinned to agree by the classifier unit tests + the on-device refusal
    // test, so a one-sided drift is caught (the same two-sided pin as BrowserSnapshotScript).
    private val PAYMENT_NAME =
        Regex(
            "(card[-_]?number|cc[-_]?number|cv[vy]|credit[-_]?card|debit[-_]?card|bank[-_]?card|" +
                "pay[-_]?card|expiry|exp[-_]?(date|month|year|mm|yy|mo|yr)|iban)",
            RegexOption.IGNORE_CASE,
        )

    private val OTP_NAME =
        Regex(
            "(otp|one[-_]?time[-_]?code|verification[-_]?code|verify[-_]?code|auth[-_]?code|" +
                "sms[-_]?code|mfa[-_]?code|2[-_]?fa|totp|captcha|passcode|secret[-_]?code|device[-_]?code)",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Classifies one field from its (tag, type, autocomplete, name/id, placeholder). [nameId] is
     * the `name` or `id` attribute; [placeholder] the `placeholder` attribute. All inputs are
     * host-supplied field attributes (data), already bounded by the extraction / action script.
     */
    @Suppress("ReturnCount") // one early return per sensitive category, then a normal fall-through
    fun classify(
        tag: String,
        type: String,
        autocomplete: String,
        nameId: String,
        placeholder: String,
    ): Verdict {
        val t = type.trim().lowercase()
        val ac = autocomplete.trim().lowercase()
        val label = (nameId + " " + placeholder).lowercase()
        val isInputTag = tag.trim().lowercase() in INPUT_TAGS

        // 1) password — refused in every context (doc 09 §3.3 密码框默认拒绝).
        if (t == "password" || ac == "password" || ac.endsWith("-password")) {
            return Verdict.Sensitive(Refusal.PASSWORD)
        }
        // 2) payment — card number / holder / expiry / IBAN fields (doc 09 §3.4 支付).
        if (ac.startsWith("cc-") || ac == "credit-card" || ac == "on-card") {
            return Verdict.Sensitive(Refusal.PAYMENT)
        }
        if (PAYMENT_NAME.containsMatchIn(label)) return Verdict.Sensitive(Refusal.PAYMENT)
        // 3) one-time-code / verification / biometric-adjacent (doc 09 §3.4 验证码).
        if (ac == "one-time-code") return Verdict.Sensitive(Refusal.ONE_TIME_CODE)
        if (isInputTag && OTP_NAME.containsMatchIn(label)) return Verdict.Sensitive(Refusal.ONE_TIME_CODE)

        return Verdict.Normal
    }

    /** The model-visible reason string for a [Refusal] ("" for [Verdict.Normal]). */
    fun reasonOf(verdict: Verdict): String =
        when (verdict) {
            is Verdict.Normal -> ""
            is Verdict.Sensitive -> verdict.reason.name.lowercase()
        }
}
