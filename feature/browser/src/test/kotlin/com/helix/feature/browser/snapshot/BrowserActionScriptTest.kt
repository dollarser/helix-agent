package com.helix.feature.browser.snapshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fixed versioned action scripts for `browser.click` / `browser.type` / `browser.scroll`
 * (HXA-062; doc 09 §3.4: only Helix's own versioned script fragments are ever evaluated).
 *
 * Unlike the read-only [BrowserSnapshotScript.EXTRACT], these scripts DO act (focus / click / set
 * value / scroll). So the pins are about: (a) the walk bounds still match the Kotlin constants so
 * the `nodeIndex → element` mapping stays in lockstep with EXTRACT; (b) the sensitive-field policy
 * is duplicated VERBATIM so the JS gate and the host [com.helix.tools.browser.SensitiveFieldClassifier]
 * agree (an action is PERFORMED only when both agree the field is normal); and (c) the only
 * interpolated value — the `type` text — is a JSON string literal, and no privileged surface is
 * touched. The Kotlin side of (b) is pinned by `SensitiveFieldClassifierTest`; the on-device side
 * by the refusal test.
 */
class BrowserActionScriptTest {
    private val clickScript = BrowserActionScript.click(0)
    private val typeScript = BrowserActionScript.type(0, "hi")
    private val scrollScript = BrowserActionScript.scroll(3, -7)

    // The literal triggers the host SensitiveFieldClassifier.classify keys off (see
    // SensitiveFieldClassifierTest). Every one of them must appear in the JS gate too.
    private val classifierTriggers =
        listOf(
            "password",
            "cc-",
            "credit-card",
            "on-card",
            "card[-_]?number",
            "iban",
            "expiry",
            "one-time-code",
            "otp",
            "captcha",
        )

    @Test
    fun theJsBoundsMatchTheKotlinBounds() {
        // A one-sided drift here would desync the click/type walk from EXTRACT and silently map a
        // nodeIndex to the wrong element — so both field scripts must carry the same literals.
        for (script in listOf(clickScript, typeScript)) {
            assertTrue(
                "MAX_NODES literal drifted",
                script.contains("var MAX_NODES = ${BrowserActionScript.MAX_NODES};"),
            )
            assertTrue(
                "MAX_VISITED literal drifted",
                script.contains("var MAX_VISITED = ${BrowserActionScript.MAX_VISITED_ELEMENTS};"),
            )
        }
    }

    @Test
    fun theActionScriptsAreSelfContainedIifes() {
        for (script in listOf(clickScript, typeScript, scrollScript)) {
            val trimmed = script.trim()
            assertTrue("must open with an IIFE", trimmed.startsWith("(function"))
            assertTrue("must close with the IIFE call", trimmed.endsWith(")();"))
        }
    }

    @Test
    fun theResultCarriesTheScriptVersion() {
        for (script in listOf(clickScript, typeScript, scrollScript)) {
            assertTrue("version literal drifted", script.contains("v: ${BrowserActionScript.SCRIPT_VERSION}"))
        }
    }

    @Test
    fun theFieldScriptsMirrorTheHostClassifierTriggers() {
        // Both the click and the type gate must carry the SAME password/payment/one-time-code
        // policy the host re-applies, because an action is PERFORMED only when both agree.
        for (trigger in classifierTriggers) {
            assertTrue("click script missing trigger: $trigger", clickScript.contains(trigger))
            assertTrue("type script missing trigger: $trigger", typeScript.contains(trigger))
        }
        // scroll acts on the viewport, not a field, so it carries NONE of the classifier — a pin
        // that the policy is scoped to field actions, not tacked onto every script.
        for (trigger in classifierTriggers) {
            assertFalse("scroll script should not carry trigger: $trigger", scrollScript.contains(trigger))
        }
    }

    @Test
    fun theTypeTextIsInjectedAsAJsonStringLiteral() {
        // The `type` text is the only value interpolated into a script; it must land as a JSON
        // string literal (a valid JS string literal) so a hostile text cannot break out of the
        // script's structure. A quote in the text must be escaped, not raw.
        val script = BrowserActionScript.type(0, "a\"b")
        assertTrue("type text must be a JSON string literal", script.contains("var TEXT = \"a\\\"b\";"))
    }

    @Test
    fun theActionScriptsNeverTouchAPrivilegedSurface() {
        // No privileged permanent bridge name, no network, no cookie/storage reads, no redirect.
        // (These scripts DO mutate their own element — focus/click/value/scroll — but never reach
        // outside the page's own document.)
        for (forbidden in listOf(
            "helix",
            "XMLHttpRequest",
            "fetch(",
            "navigator",
            "document.cookie",
            "localStorage",
            "sessionStorage",
            "location.href =",
            "addJavascriptInterface",
        )) {
            for (script in listOf(clickScript, typeScript, scrollScript)) {
                assertFalse("script must not reference $forbidden", script.contains(forbidden))
            }
        }
    }
}
