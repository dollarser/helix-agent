package com.helix.feature.browser

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceRequest
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page navigation must not promote subframes or replace a readable page with an app-link error. */
class BrowserPageNavigationDeviceTest {
    @Test fun rejectedAppLinksLeaveTheCurrentPageReadable() =
        withPage { c, b, id ->
            val before = c.tab(id)!!
            for (mainFrame in listOf(true, false)) {
                val targets =
                    listOf(
                        "snssdk1128://feed",
                        "intent://feed#Intent;scheme=snssdk1128;end",
                        "file:///secret",
                    )
                for (url in targets) {
                    onMain {
                        val view = c.hostView(id)!!
                        assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request(url, mainFrame)))
                    }
                    assertEquals("a refused page request must not replace the document", before, c.tab(id))
                    assertEquals(
                        "Download",
                        b
                            .snapshot(id)
                            .nodes
                            .single()
                            .text,
                    )
                }
            }
        }

    @Test fun allowedMainAndSubframeRequestsContinueWithoutAnExtraTopLevelLoad() =
        withPage { c, _, id ->
            val before = c.tab(id)!!
            for (mainFrame in listOf(true, false)) {
                onMain {
                    val view = c.hostView(id)!!
                    assertFalse(
                        view.webViewClient.shouldOverrideUrlLoading(
                            view,
                            request("https://example.com/next", mainFrame),
                        ),
                    )
                }
                assertEquals("admission alone must not start another top-level load", before, c.tab(id))
            }
        }

    @Test fun aRealPageScriptCannotDestroyItsDocumentByOpeningAnAppLink() =
        withPage { c, b, id ->
            onMain {
                c.hostView(id)!!.evaluateJavascript("location.href='snssdk1128://feed';", null)
            }
            SystemClock.sleep(500)
            val result = b.snapshot(id)
            assertTrue(result.message, result.ok)
            assertEquals("Download", result.nodes.single().text)
        }

    @Test fun aForbiddenCommittedDocumentStillFailsClosed() =
        withPage { c, b, id ->
            onMain {
                val view = c.hostView(id)!!
                view.webViewClient.doUpdateVisitedHistory(view, "file:///secret", false)
            }
            assertTrue(c.tab(id)!!.error is PolicyBlockedError)
            assertTrue(b.snapshot(id).message.startsWith("page_error:"))
        }

    @Test fun loadingAndMissingHostsHaveDistinctSnapshotFailures() =
        withPage { c, b, id ->
            assertEquals("no_page", b.snapshot("missing").message)
            onMain {
                val view = c.hostView(id)!!
                view.webViewClient.onPageStarted(view, c.tab(id)!!.url, null)
            }
            assertTrue(b.snapshot(id).message.startsWith("page_loading:"))
            onMain { c.destroy() }
            assertTrue(b.snapshot(id).message.startsWith("host_unavailable:"))
        }

    private fun withPage(block: (BrowserController, BrowserToolBridgeImpl, String) -> Unit) {
        BrowserActivityFixture().use { fixture ->
            val c = fixture.controller
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val store = WorkspaceArtifactStore(ScopeRootResolver { directory.toPath() })
            val bridge = BrowserToolBridgeImpl(c, store, "page")
            val id = bridge.open("data:text/html,<h1>Download</h1>", "fixture").tabId
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (c.tab(id)!!.let { it.isLoading || it.navigationGeneration < 1 } &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(30)
            }
            assertTrue("fixture page must be readable", bridge.snapshot(id).ok)
            block(c, bridge, id)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun request(
        url: String,
        mainFrame: Boolean,
    ) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)

        override fun isForMainFrame(): Boolean = mainFrame

        override fun isRedirect(): Boolean = false

        override fun hasGesture(): Boolean = false

        override fun getMethod(): String = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
