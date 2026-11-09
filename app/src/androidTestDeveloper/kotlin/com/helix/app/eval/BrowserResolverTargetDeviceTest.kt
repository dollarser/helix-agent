package com.helix.app.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserResolverTargetDeviceTest {
    private val list = BrowserResolverNode("", "android:id/resolver_list", "android", listOf(0, 1), false, true)
    private val row = BrowserResolverNode("", "", "android", listOf(0, 1, 0), true, true)
    private val chrome = BrowserResolverNode("Chrome", "android:id/text1", "android", listOf(0, 1, 0, 1), false, true)
    private val htmlRow = row.copy(path = listOf(0, 1, 1))
    private val html = chrome.copy(text = "HTML Viewer", path = listOf(0, 1, 1, 1))
    private val once =
        BrowserResolverNode("Just once", "android:id/button_once", "android", listOf(0, 2, 0), true, true)
    private val nodes = listOf(list, row, chrome, htmlRow, html, once)

    @Test fun uniqueChromeLabelAndItsExactRowAreAllowed() {
        assertTrue(BrowserResolverTarget.allowed(row, nodes))
        assertTrue(BrowserResolverTarget.allowed(chrome, nodes))
        assertTrue(BrowserResolverTarget.allowed(once, nodes))
    }

    @Test fun htmlViewerAlwaysAndDisabledButtonsRefuse() {
        assertFalse(BrowserResolverTarget.allowed(htmlRow, nodes))
        assertFalse(BrowserResolverTarget.allowed(html, nodes))
        val always = once.copy(text = "Always", viewId = "android:id/button_always")
        assertFalse(BrowserResolverTarget.allowed(always, nodes + always))
        val disabled = once.copy(enabled = false)
        assertFalse(BrowserResolverTarget.allowed(disabled, nodes - once + disabled))
        val misleading = once.copy(text = "Always")
        assertFalse(BrowserResolverTarget.allowed(misleading, nodes - once + misleading))
    }

    @Test fun duplicateChromeAndMixedLabelsRefuse() {
        assertFalse(BrowserResolverTarget.allowed(row, nodes + chrome.copy(path = listOf(0, 1, 2, 1))))
        assertFalse(BrowserResolverTarget.allowed(row, nodes + chrome.copy(path = listOf(0, 1, 0, 2))))
        assertFalse(BrowserResolverTarget.allowed(row, nodes + html.copy(path = listOf(0, 1, 0, 2))))
    }

    @Test fun wrongPackageContainerAndSiblingPathsRefuse() {
        assertFalse(BrowserResolverTarget.allowed(row, nodes - list))
        assertFalse(BrowserResolverTarget.allowed(row, nodes - list + list.copy(viewId = "android:id/unknown")))
        assertFalse(BrowserResolverTarget.allowed(row, nodes - chrome + chrome.copy(path = listOf(0, 1, 10, 1))))
        assertFalse(BrowserResolverTarget.allowed(row, nodes - list + list.copy(path = emptyList())))
        assertFalse(BrowserResolverTarget.allowed(row, nodes - chrome + chrome.copy(packageName = "other.app")))
        val unknownRow = row.copy(viewId = "android:id/button_always")
        assertFalse(BrowserResolverTarget.allowed(unknownRow, nodes - row + unknownRow))
        val wrongPackage = nodes.map { it.copy(packageName = "other.app") }
        assertFalse(BrowserResolverTarget.allowed(wrongPackage[1], wrongPackage))
    }
}
