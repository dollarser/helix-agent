package com.helix.app.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTaskFileTargetDeviceTest {
    private val row = BrowserFileNode("", "com.google.android.documentsui:id/item_root", listOf(2), true)
    private val title = BrowserFileNode("task.html", "android:id/title", listOf(2, 0, 0), false)
    private val breadcrumb =
        BrowserFileNode("Downloads", "com.google.android.documentsui:id/breadcrumb_text", listOf(0), false)
    private val nodes = listOf(breadcrumb, row, title)

    @Test fun taskTitleAndItsExactClickableAncestorAreAllowed() {
        assertTrue(BrowserTaskFileTarget.allowed(row, nodes))
        assertTrue(BrowserTaskFileTarget.allowed(title, nodes))
    }

    @Test fun overlappingOrAdjacentSiblingDoesNotBecomeTheTaskRow() {
        assertFalse(BrowserTaskFileTarget.allowed(row.copy(path = listOf(3)), nodes))
        assertFalse(BrowserTaskFileTarget.allowed(row, listOf(breadcrumb, row, title.copy(path = listOf(3, 0)))))
        assertFalse(BrowserTaskFileTarget.allowed(row, listOf(breadcrumb, row, title.copy(path = listOf(20, 0)))))
    }

    @Test fun ambiguousDifferentOrMissingFileTitlesRefuse() {
        assertFalse(BrowserTaskFileTarget.allowed(row, nodes + title.copy(path = listOf(2, 1))))
        assertFalse(BrowserTaskFileTarget.allowed(row, nodes + title.copy(text = "other.html", path = listOf(2, 1))))
        assertFalse(BrowserTaskFileTarget.allowed(row, nodes + title.copy(path = listOf(3, 0))))
        assertFalse(BrowserTaskFileTarget.allowed(row, listOf(breadcrumb, row, title.copy(text = "other.html"))))
        assertFalse(BrowserTaskFileTarget.allowed(row, listOf(breadcrumb, row)))
    }

    @Test fun unknownContainersDisabledClickTargetsAndMissingDownloadsContextRefuse() {
        assertFalse(BrowserTaskFileTarget.allowed(row.copy(viewId = "unknown"), nodes))
        val disabled = row.copy(clickable = false)
        assertFalse(BrowserTaskFileTarget.allowed(disabled, listOf(breadcrumb, disabled, title)))
        assertFalse(BrowserTaskFileTarget.allowed(row, nodes.filter { it != breadcrumb }))
        assertFalse(BrowserTaskFileTarget.allowed(row, listOf(breadcrumb.copy(text = "Documents"), row, title)))
        assertFalse(BrowserTaskFileTarget.allowed(title.copy(text = "other.html"), nodes))
    }
}
