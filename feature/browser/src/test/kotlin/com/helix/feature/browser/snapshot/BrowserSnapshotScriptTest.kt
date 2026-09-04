package com.helix.feature.browser.snapshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fixed versioned DOM-extraction script (doc 09 §3.4: only Helix's own versioned
 * script fragment is ever evaluated). The JS literals must match the Kotlin bounds so a
 * one-sided change fails the gate, and the script must stay read-only — no bridge access,
 * no network, no cookie/storage reads — because it runs in an UNTRUSTED page's JS context.
 */
class BrowserSnapshotScriptTest {
    private val script = BrowserSnapshotScript.EXTRACT

    @Test
    fun theJsLiteralsMatchTheKotlinBounds() {
        assertTrue("MAX_NODES literal drifted", script.contains("var MAX_NODES = ${BrowserSnapshotScript.MAX_NODES};"))
        assertTrue(
            "MAX_TEXT literal drifted",
            script.contains("var MAX_TEXT = ${BrowserSnapshotScript.MAX_TEXT_LENGTH};"),
        )
        assertTrue(
            "MAX_VISITED literal drifted",
            script.contains("var MAX_VISITED = ${BrowserSnapshotScript.MAX_VISITED_ELEMENTS};"),
        )
    }

    @Test
    fun theResultCarriesTheScriptVersion() {
        assertTrue("version literal drifted", script.contains("v: ${BrowserSnapshotScript.SCRIPT_VERSION}"))
        assertTrue(script.contains("truncated"))
        assertTrue(script.contains("nodes"))
    }

    @Test
    fun theScriptIsASelfContainedIife() {
        val trimmed = script.trim()
        assertTrue("must open with an IIFE", trimmed.startsWith("(function"))
        assertTrue("must close with the IIFE call", trimmed.endsWith(")();"))
    }

    @Test
    fun theScriptNeverTouchesAPrivilegedSurface() {
        // No privileged permanent JS bridge name, no network, no storage/cookie reads, no
        // DOM mutation — the script is read-only over its own document (doc 09 §3.4).
        for (forbidden in listOf(
            "helix",
            "XMLHttpRequest",
            "fetch(",
            "navigator",
            "document.cookie",
            "localStorage",
            "sessionStorage",
            "location.href =",
        )) {
            assertFalse("script must not reference $forbidden", script.contains(forbidden))
        }
    }

    @Test
    fun thePasswordFieldRuleIsPresent() {
        assertTrue("the script must special-case password fields", script.contains("password"))
    }
}
