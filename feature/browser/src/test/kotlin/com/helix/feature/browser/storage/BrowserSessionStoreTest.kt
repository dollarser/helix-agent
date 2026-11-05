package com.helix.feature.browser.storage

import com.helix.feature.browser.BrowserTabController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BrowserSessionStoreTest {
    @Test
    fun roundTripKeepsOnlyOrdinaryMetadataAndRestoresFreshIdentities() {
        val tabs = BrowserTabController()
        val regular = tabs.newTab()
        tabs.navigate(regular, "https://example.com/ordinary")
        tabs.onPageFinished(regular, "https://example.com/ordinary", "Ordinary", true, true)
        tabs.setDesktopMode(regular, true)
        val privateTab = tabs.newTab(isIncognito = true)
        tabs.navigate(privateTab, "https://example.com/private")
        val task = tabs.newTab(originSessionId = "source-session")
        tabs.navigate(task, "https://example.com/task")
        val session = BrowserSession.from(tabs.state())
        withStore { store, file ->
            store.write(session)
            assertEquals(session, store.read())
            val bytes = file.readText()
            listOf(regular, privateTab, task, "source-session", "/private", "/task", "navigationGeneration").forEach {
                assertFalse(bytes.contains(it))
            }
            val restored = BrowserTabController()
            restored.restoreSession(store.read())
            val tab = restored.state().selectedTab!!
            assertNotEquals(regular, tab.id)
            assertEquals("Ordinary", tab.title)
            assertTrue(tab.isDesktopMode)
            assertFalse(tab.canGoBack)
            assertFalse(tab.isLoading)
            assertEquals(null, tab.originSessionId)
            assertTrue(restored.state().isHomeVisible)
        }
    }

    @Test
    fun homeDoesNotNavigateAndCloseDiscardsPrivateAndTaskTabs() {
        val controller = BrowserTabController()
        val id = controller.newTab()
        controller.navigate(id, "https://example.com/")
        controller.onPageFinished(id, "https://example.com/", "Page", true, false)
        val before = controller.state().selectedTab!!
        controller.showHome()
        assertEquals(before, controller.state().selectedTab)
        controller.showSelectedPage()
        assertEquals(before, controller.state().selectedTab)
        controller.newTab(isIncognito = true)
        controller.newTab(originSessionId = "source")
        controller.closeBrowser()
        assertEquals(listOf(id), controller.state().tabs.map { it.id })
        assertTrue(controller.state().isClosed)
        assertFalse(controller.state().selectedTab!!.canGoBack)
        assertTrue(controller.state().selectedTab!!.navigationGeneration > before.navigationGeneration)
        controller.openBrowser()
        controller.closeTab(id)
        assertEquals(BrowserSession(), BrowserSession.from(controller.state()))
    }

    @Test
    fun invalidVersionSelectionAndForbiddenUrlFailInsteadOfLoading() {
        withStore { store, file ->
            assertEquals(BrowserSession(), store.read())
            for (content in listOf(
                "{}",
                "{\"version\":2,\"tabs\":[],\"selectedIndex\":-1}",
                "{\"version\":1,\"tabs\":[],\"selectedIndex\":0}",
                "{\"version\":1,\"tabs\":[{\"url\":\"file:///secret\",\"desktopMode\":false}],\"selectedIndex\":0}",
            )) {
                file.writeText(content)
                assertThrows(RuntimeException::class.java) { store.read() }
            }
        }
    }

    @Test
    fun oversizedCheckpointDoesNotReplaceLastSavedSession() {
        withStore { store, _ ->
            val saved = BrowserSession(listOf(SavedBrowserTab("https://example.com/", "Page", false)), 0)
            store.write(saved)
            val oversizedTab = SavedBrowserTab("data:text/html," + "x".repeat(BrowserFileStore.MAX_BYTES), null, false)
            val tooLarge = BrowserSession(listOf(oversizedTab), 0)
            assertThrows(IllegalArgumentException::class.java) { store.write(tooLarge) }
            assertEquals(saved, store.read())
        }
    }

    private fun withStore(block: (BrowserSessionStore, java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("browser-session").toFile()
        try {
            val file = dir.resolve("session.json")
            block(BrowserSessionStore(file), file)
        } finally {
            dir.deleteRecursively()
        }
    }
}
