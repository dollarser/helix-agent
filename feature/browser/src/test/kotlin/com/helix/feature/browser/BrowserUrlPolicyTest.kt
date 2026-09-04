package com.helix.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlPolicyTest {
    @Test
    fun httpAndHttpsHostsAreAllowed() {
        assertTrue(
            BrowserUrlPolicy
                .evaluate("https://helix.example/a?b=c#frag")
                .let { it is BrowserUrlDecision.Allowed },
        )
        assertTrue(BrowserUrlPolicy.evaluate("http://example.com") is BrowserUrlDecision.Allowed)
    }

    @Test
    fun schemeComparisonIsCaseInsensitive() {
        assertTrue(BrowserUrlPolicy.evaluate("HTTPS://Example.COM/x") is BrowserUrlDecision.Allowed)
    }

    @Test
    fun onlyTheExactAboutBlankFormIsAllowed() {
        assertTrue(BrowserUrlPolicy.evaluate("about:blank") is BrowserUrlDecision.Allowed)
        assertTrue(BrowserUrlPolicy.evaluate("ABOUT:BLANK") is BrowserUrlDecision.Allowed)
        assertEquals(
            BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME),
            BrowserUrlPolicy.evaluate("about:config"),
        )
        assertEquals(
            BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME),
            BrowserUrlPolicy.evaluate("about:srcdoc"),
        )
    }

    @Test
    fun dataIsAllowedOnlyForTextHtml() {
        assertTrue(BrowserUrlPolicy.evaluate("data:text/html,<h1>hi</h1>") is BrowserUrlDecision.Allowed)
        assertTrue(
            BrowserUrlPolicy
                .evaluate("data:text/html;charset=utf-8,<h1>hi</h1>")
                .let { it is BrowserUrlDecision.Allowed },
        )
        assertTrue(BrowserUrlPolicy.evaluate("data:text/html;base64,PGgxPmhpPC9oMT4=") is BrowserUrlDecision.Allowed)
        assertEquals(
            BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME),
            BrowserUrlPolicy.evaluate("data:text/plain,hi"),
        )
        assertEquals(
            BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME),
            BrowserUrlPolicy.evaluate("data:application/pdf,%PDF-1.4"),
        )
        assertEquals(
            BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME),
            BrowserUrlPolicy.evaluate("data:,no-media-type"),
        )
    }

    @Test
    fun dangerousSchemesAreDenied() {
        for (url in listOf(
            "file:///etc/passwd",
            "content://settings/system",
            "javascript:alert(1)",
            "view-source:https://example.com",
            "chrome://settings",
            "ftp://example.com/x",
            "blob:https://example.com/uuid",
        )) {
            assertEquals(
                url,
                BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME),
                BrowserUrlPolicy.evaluate(url),
            )
        }
        // Android intent URL: parseable on some JDKs (UNSUPPORTED_SCHEME), rejected by the
        // parser itself on others (INVALID) — denied either way.
        val intent = BrowserUrlPolicy.evaluate("intent:#Intent;end")
        assertTrue("intent: must be denied", intent is BrowserUrlDecision.Denied)
        assertTrue(
            (intent as BrowserUrlDecision.Denied).reason
                in setOf(DenialReason.UNSUPPORTED_SCHEME, DenialReason.INVALID),
        )
    }

    @Test
    fun httpWithoutAHostIsDenied() {
        assertEquals(
            BrowserUrlDecision.Denied(DenialReason.MISSING_HOST),
            BrowserUrlPolicy.evaluate("https:///path-only"),
        )
        // "http://" is rejected by the URI parser itself on this JDK (reason INVALID) and
        // by the empty-host check on JDKs that tolerate it — denied either way.
        val decision = BrowserUrlPolicy.evaluate("http://")
        assertTrue("http:// must be denied", decision is BrowserUrlDecision.Denied)
        assertTrue(
            (decision as BrowserUrlDecision.Denied).reason
                in setOf(DenialReason.MISSING_HOST, DenialReason.INVALID),
        )
    }

    @Test
    fun emptyAndWhitespaceAreDeniedAsEmpty() {
        assertEquals(BrowserUrlDecision.Denied(DenialReason.EMPTY), BrowserUrlPolicy.evaluate(""))
        assertEquals(BrowserUrlDecision.Denied(DenialReason.EMPTY), BrowserUrlPolicy.evaluate("   "))
    }

    @Test
    fun surroundingWhitespaceIsTrimmedBeforeEvaluation() {
        assertTrue(BrowserUrlPolicy.evaluate("  https://example.com  ") is BrowserUrlDecision.Allowed)
    }

    @Test
    fun unparseableRelativeOrControlledInputIsDeniedAsInvalid() {
        assertEquals(BrowserUrlDecision.Denied(DenialReason.INVALID), BrowserUrlPolicy.evaluate("example.com"))
        assertEquals(BrowserUrlDecision.Denied(DenialReason.INVALID), BrowserUrlPolicy.evaluate("ht tp://bad url"))
        assertEquals(BrowserUrlDecision.Denied(DenialReason.INVALID), BrowserUrlPolicy.evaluate("https://[::1"))
        assertEquals(
            BrowserUrlDecision.Denied(DenialReason.INVALID),
            BrowserUrlPolicy.evaluate("http://ex\u0000ample.com"),
        )
        assertEquals(
            BrowserUrlDecision.Denied(DenialReason.INVALID),
            BrowserUrlPolicy.evaluate("https://example.com/path\u007Fend"),
        )
    }
}
