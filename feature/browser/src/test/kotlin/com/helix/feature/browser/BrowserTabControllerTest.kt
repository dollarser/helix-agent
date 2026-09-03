package com.helix.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabControllerTest {
    @Test
    fun newTabStartsBlankSelectedAndAtGenerationZero() {
        val c = BrowserTabController()
        val id = c.newTab()
        val tab = c.state().tabs.single()
        assertEquals(id, tab.id)
        assertEquals(BrowserTabController.ABOUT_BLANK, tab.url)
        assertFalse(tab.isLoading)
        assertNull(tab.error)
        assertEquals(0L, tab.navigationGeneration)
        assertEquals(id, c.state().selectedId)
    }

    @Test
    fun newTabFailsAtTheConfiguredLimit() {
        val c = BrowserTabController(maxTabs = 2)
        c.newTab()
        c.newTab()
        assertThrows(IllegalStateException::class.java) { c.newTab() }
    }

    @Test
    fun maxTabsOutsideTheHardRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { BrowserTabController(maxTabs = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserTabController(
                maxTabs =
                    BrowserTabController.HARD_MAX_TABS + 1,
            )
        }
    }

    @Test
    fun navigateEmitsLoadAndEntersLoadingState() {
        val c = BrowserTabController()
        val id = c.newTab()
        val command = c.navigate(id, "https://helix.example/a?b=c")
        assertEquals(BrowserTabController.TabCommand.Load(id, "https://helix.example/a?b=c"), command)
        val tab = c.state().tabs.single()
        assertTrue(tab.isLoading)
        assertNull(tab.error)
        assertEquals("https://helix.example/a?b=c", tab.url)
    }

    @Test
    fun navigateDeniesUnsupportedSchemesWithoutTouchingTheWebView() {
        val c = BrowserTabController()
        val id = c.newTab()
        assertNull(c.navigate(id, "file:///etc/passwd"))
        val tab = c.state().tabs.single()
        assertFalse(tab.isLoading)
        assertTrue(tab.error is PolicyBlockedError)
        val blocked = tab.error as PolicyBlockedError
        assertEquals(BrowserErrorKind.POLICY_BLOCKED, blocked.kind)
        assertEquals(DenialReason.UNSUPPORTED_SCHEME, blocked.reason)
    }

    @Test
    fun navigateDeniesEmptyInputWithTheEmptyReason() {
        val c = BrowserTabController()
        val id = c.newTab()
        assertNull(c.navigate(id, "   "))
        val error =
            c
                .state()
                .tabs
                .single()
                .error
        assertEquals(DenialReason.EMPTY, (error as PolicyBlockedError).reason)
    }

    @Test
    fun goBackAndGoForwardAreGuardedByHistoryAndLoading() {
        val c = BrowserTabController()
        val id = c.newTab()
        assertNull(c.goBack(id))
        assertNull(c.goForward(id))
        c.onPageFinished(id, "https://example.com/", "title", canGoBack = true, canGoForward = false)
        assertEquals(BrowserTabController.TabCommand.Back(id), c.goBack(id))
        assertNull(c.goBack(id))
        assertTrue(
            c
                .state()
                .tabs
                .single()
                .isLoading,
        )
        assertNull(c.goForward(id))
    }

    @Test
    fun reloadIsRefusedOnBlankTabsAndWhileLoading() {
        val c = BrowserTabController()
        val id = c.newTab()
        assertNull(c.reload(id))
        c.navigate(id, "https://example.com/")
        assertNull(c.reload(id))
        c.onPageFinished(id, "https://example.com/", "t", canGoBack = false, canGoForward = false)
        assertEquals(BrowserTabController.TabCommand.Reload(id), c.reload(id))
    }

    @Test
    fun stopClearsLoadingAndEmitsStopOnlyWhileLoading() {
        val c = BrowserTabController()
        val id = c.newTab()
        assertNull(c.stop(id))
        c.navigate(id, "https://example.com/")
        assertEquals(BrowserTabController.TabCommand.Stop(id), c.stop(id))
        val tab = c.state().tabs.single()
        assertFalse(tab.isLoading)
        assertNull(tab.error)
    }

    @Test
    fun committedPagesBumpTheNavigationGeneration() {
        val c = BrowserTabController()
        val id = c.newTab()
        c.onPageStarted(id, "https://a.example/")
        c.onPageFinished(id, "https://a.example/", "A", canGoBack = false, canGoForward = false)
        assertEquals(
            1L,
            c
                .state()
                .tabs
                .single()
                .navigationGeneration,
        )
        c.navigate(id, "https://b.example/")
        c.onPageFinished(id, "https://b.example/", "B", canGoBack = true, canGoForward = false)
        val tab = c.state().tabs.single()
        assertEquals(2L, tab.navigationGeneration)
        assertEquals("B", tab.title)
        assertTrue(tab.canGoBack)
    }

    @Test
    fun mainFrameErrorsSetAMappedErrorAndStopLoading() {
        val c = BrowserTabController()
        val id = c.newTab()
        c.navigate(id, "https://nonexistent.invalid/")
        c.onMainFrameError(id, netError = -105, clientError = -7, failingUrl = "https://nonexistent.invalid/")
        val tab = c.state().tabs.single()
        assertFalse(tab.isLoading)
        val error = tab.error
        assertEquals(BrowserErrorKind.HOST_LOOKUP_FAILED, error?.kind)
        assertEquals(-105, (error as LoadError).rawCode)
    }

    @Test
    fun aCodelessMainframeFailureSetsAnUnknownErrorPage() {
        // The modern onReceivedError carries no numeric code: the current System WebView
        // fires it for a DNS failure instead of the legacy typed callback.
        val c = BrowserTabController()
        val id = c.newTab()
        c.navigate(id, "https://nonexistent.invalid/")
        c.onMainFrameUnknownError(id, "https://nonexistent.invalid/")
        val tab = c.state().tabs.single()
        assertFalse(tab.isLoading)
        val error = tab.error
        assertEquals(BrowserErrorKind.UNKNOWN, error?.kind)
        assertNull((error as LoadError).rawCode)
    }

    @Test
    fun aTypedErrorWinsOverTheCodelessOneRegardlessOfOrder() {
        val c = BrowserTabController()
        val id = c.newTab()
        c.navigate(id, "https://nonexistent.invalid/")
        // Codeless first, typed second: the typed mapping must replace it.
        c.onMainFrameUnknownError(id, "https://nonexistent.invalid/")
        c.onMainFrameError(id, netError = -105, clientError = -7, failingUrl = "https://nonexistent.invalid/")
        assertEquals(
            BrowserErrorKind.HOST_LOOKUP_FAILED,
            c
                .state()
                .tabs
                .single()
                .error
                ?.kind,
        )

        // Typed first, codeless second: the codeless callback must not clobber it.
        val c2 = BrowserTabController()
        val id2 = c2.newTab()
        c2.navigate(id2, "https://nonexistent.invalid/")
        c2.onMainFrameError(id2, netError = -105, clientError = -7, failingUrl = "https://nonexistent.invalid/")
        c2.onMainFrameUnknownError(id2, "https://nonexistent.invalid/")
        assertEquals(
            BrowserErrorKind.HOST_LOOKUP_FAILED,
            c2
                .state()
                .tabs
                .single()
                .error
                ?.kind,
        )
    }

    @Test
    fun aCodelessFailureOnAStoppedTabIsNotAnErrorPage() {
        // The user pressed stop; the platform reports the abort through the codeless
        // callback — a stopped load is an honest non-error state.
        val c = BrowserTabController()
        val id = c.newTab()
        c.navigate(id, "https://example.com/")
        c.stop(id)
        c.onMainFrameUnknownError(id, "https://example.com/")
        val tab = c.state().tabs.single()
        assertFalse(tab.isLoading)
        assertNull(tab.error)
    }

    @Test
    fun aCodelessFailureStillSurfacesWhenLegacyClearedLoadingFirst() {
        // The legacy callback can fire first with a code that maps to null (clearing
        // loading, no error); the codeless callback for the SAME failure must still
        // surface the failure — which is why the guard is the stop marker, not isLoading.
        val c = BrowserTabController()
        val id = c.newTab()
        c.navigate(id, "https://nonexistent.invalid/")
        c.onMainFrameError(id, netError = 0, clientError = 0, failingUrl = null)
        c.onMainFrameUnknownError(id, "https://nonexistent.invalid/")
        val tab = c.state().tabs.single()
        assertFalse(tab.isLoading)
        assertEquals(BrowserErrorKind.UNKNOWN, tab.error?.kind)
    }

    @Test
    fun aFinishedEventForAnErrorPageDoesNotClearTheError() {
        // The current System WebView fires onPageFinished for the built-in error page
        // right after a main-frame failure (observed on API 29 and 36 emulators). That
        // finish commits nothing: the error page must stay, and the navigation generation
        // must not bump (HXA-061/062 tokens bind to committed pages only).
        val c = BrowserTabController()
        val id = c.newTab()
        c.navigate(id, "https://nonexistent.invalid/")
        c.onMainFrameUnknownError(id, "https://nonexistent.invalid/")
        c.onPageFinished(
            id,
            "https://nonexistent.invalid/",
            "nonexistent.invalid",
            canGoBack = false,
            canGoForward = false,
        )
        val tab = c.state().tabs.single()
        assertFalse(tab.isLoading)
        assertEquals(BrowserErrorKind.UNKNOWN, tab.error?.kind)
        assertEquals(0L, tab.navigationGeneration)
    }

    @Test
    fun zeroCodesAreNotAnErrorPage() {
        val c = BrowserTabController()
        val id = c.newTab()
        c.navigate(id, "https://example.com/")
        c.onMainFrameError(id, netError = 0, clientError = 0, failingUrl = null)
        val tab = c.state().tabs.single()
        assertFalse(tab.isLoading)
        assertNull(tab.error)
    }

    @Test
    fun sslErrorsSetAnSslError() {
        val c = BrowserTabController()
        val id = c.newTab()
        c.navigate(id, "https://bad.example/")
        c.onSslError(id, "https://bad.example/")
        val tab = c.state().tabs.single()
        assertEquals(BrowserErrorKind.SSL, tab.error?.kind)
    }

    @Test
    fun closeTabSelectsTheNeighborAndDestroysTheHost() {
        val c = BrowserTabController()
        val a = c.newTab()
        val b = c.newTab()
        val d = c.newTab()
        c.select(a)
        assertEquals(BrowserTabController.TabCommand.Destroy(b), c.closeTab(b))
        assertEquals(a, c.state().selectedId)
        c.closeTab(a)
        assertEquals(d, c.state().selectedId)
        assertThrows(IllegalArgumentException::class.java) { c.closeTab(a) }
    }

    @Test
    fun closingTheLastTabClearsTheSelection() {
        val c = BrowserTabController()
        val a = c.newTab()
        c.closeTab(a)
        val state = c.state()
        assertTrue(state.tabs.isEmpty())
        assertNull(state.selectedId)
    }

    @Test
    fun selectingAnUnknownTabIsAProgrammingError() {
        val c = BrowserTabController()
        assertThrows(IllegalStateException::class.java) { c.select("nope") }
        assertThrows(IllegalArgumentException::class.java) { c.navigate("nope", "https://example.com/") }
    }

    // ---------------------------------------------------------------- normalizeInput

    @Test
    fun aTypedFileUrlPassesThroughToThePolicyUnchanged() {
        // The regression that broke the device gate: the scheme-prefix check must be a
        // PREFIX test. Whole-string matching rewrote `file:///etc/passwd` into
        // `https://file:///etc/passwd` and the denial never happened.
        assertEquals("file:///etc/passwd", BrowserTabController.normalizeInput("file:///etc/passwd"))
        assertEquals("content://x/y", BrowserTabController.normalizeInput("content://x/y"))
        assertEquals("chrome://settings", BrowserTabController.normalizeInput("chrome://settings"))
    }

    @Test
    fun schemelessFormsWithASchemeColonPassThrough() {
        assertEquals("about:blank", BrowserTabController.normalizeInput("about:blank"))
        assertEquals(
            "data:text/html,<h1>x</h1>",
            BrowserTabController.normalizeInput("data:text/html,<h1>x</h1>"),
        )
    }

    @Test
    fun plainHostsArePrefixedWithHttpsAndTrimmed() {
        assertEquals("https://example.com", BrowserTabController.normalizeInput("example.com"))
        assertEquals("https://example.com/a?b=c", BrowserTabController.normalizeInput("  example.com/a?b=c  "))
        // Typed case is preserved (a browser hands it to the resolver verbatim); the
        // policy and DNS treat hosts case-insensitively.
        assertEquals("https://EXAMPLE.com", BrowserTabController.normalizeInput("EXAMPLE.com"))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", BrowserTabController.normalizeInput(""))
        assertEquals("", BrowserTabController.normalizeInput("   "))
    }
}
