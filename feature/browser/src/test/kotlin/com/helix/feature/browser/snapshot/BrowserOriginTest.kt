package com.helix.feature.browser.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserOriginTest {
    @Test
    fun httpsStandardPortDropsThePort() {
        assertEquals("https://helix.example", BrowserOrigin.of("https://helix.example/a?b=c"))
        assertEquals("https://helix.example", BrowserOrigin.of("https://helix.example:443/a"))
    }

    @Test
    fun httpStandardPortDropsThePort() {
        assertEquals("http://helix.example", BrowserOrigin.of("http://helix.example/a"))
        assertEquals("http://helix.example", BrowserOrigin.of("http://helix.example:80/a"))
    }

    @Test
    fun nonStandardPortIsKept() {
        assertEquals("https://helix.example:8443", BrowserOrigin.of("https://helix.example:8443/a"))
    }

    @Test
    fun schemeAndHostAreCasefolded() {
        assertEquals("https://helix.example", BrowserOrigin.of("HTTPS://Helix.Example/a"))
    }

    @Test
    fun aboutBlankAndDataHaveCanonicalOpaqueOrigins() {
        assertEquals("about:blank", BrowserOrigin.of("about:blank"))
        assertEquals("data:opaque", BrowserOrigin.of("data:text/html,<h1>hi</h1>"))
        assertEquals("data:opaque", BrowserOrigin.of("data:text/html;base64,AAAA"))
    }

    @Test
    fun otherSchemesAndGarbageYieldNoOrigin() {
        assertNull(BrowserOrigin.of("file:///etc/passwd"))
        assertNull(BrowserOrigin.of("content://settings/system"))
        assertNull(BrowserOrigin.of("not a url"))
        assertNull(BrowserOrigin.of("https:///no-host"))
    }
}
