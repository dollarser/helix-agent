package com.helix.feature.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.browser.ActionStatus
import com.helix.tools.browser.BrowserUploadRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No remote service: a synthetic HTTPS-origin document receives only fixture bytes. */
@RunWith(AndroidJUnit4::class)
class BrowserUploadDeviceTest {
    private lateinit var fixture: BrowserActivityFixture
    private lateinit var bridge: BrowserToolBridgeImpl
    private lateinit var source: File
    private lateinit var tab: String
    private val url = "https://helix-upload.invalid/fixture"
    private val content = "synthetic upload fixture".toByteArray()
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        fixture = BrowserActivityFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "upload-fixture").apply { mkdirs() }
        source = File(root, "fixture.txt").apply { writeBytes(content) }
        bridge =
            BrowserToolBridgeImpl(
                fixture.controller,
                WorkspaceArtifactStore(
                    ScopeRootResolver {
                        require(it == "upload-test")
                        root.toPath()
                    },
                ),
                "upload-test",
            )
        fixture.scenario.onActivity {
            tab = fixture.controller.newTab()
            fixture.controller.navigate(tab, "data:text/html,<h1>Warmup</h1>")
        }
        Thread.sleep(300)
        fixture.scenario.onActivity {
            fixture.controller.hostView(tab)!!.loadDataWithBaseURL(
                url,
                "<input id='file' type='file' accept='.txt'><input id='result' value='none'>" +
                    "<script>var changes=0;document.getElementById('file').onchange=function(){changes++;" +
                    "var f=this.files[0];document.getElementById('result').value=" +
                    "f.name+':'+f.size+':'+changes;};</script>",
                "text/html",
                "UTF-8",
                null,
            )
        }
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until &&
            bridge.snapshot(tab).nodes.none { it.value == "none" }
        ) {
            Thread.sleep(50)
        }
        assertTrue(bridge.snapshot(tab).nodes.any { it.value == "none" })
    }

    @After
    fun close() {
        fixture.close()
    }

    private fun request() =
        BrowserUploadRequest(
            tab,
            bridge
                .snapshot(tab)
                .nodes
                .first { it.role == "field" }
                .token,
            url,
            "scope:upload-test:fixture.txt",
            hash,
        )

    @Test
    fun attachesVerifiedBytesAndDispatchesChangeExactlyOnce() {
        val result = bridge.upload(request()) { true }
        assertEquals(result.action.reason, ActionStatus.PERFORMED, result.action.status)
        assertEquals(content.size, result.sizeBytes)
        assertTrue(bridge.snapshot(tab).nodes.any { it.value == "fixture.txt:${content.size}:1" })
    }

    @Test
    fun sourceVersionPageRawPathAndCancellationFailBeforeDisclosure() {
        val request = request()
        source.writeText("changed")
        assertEquals("source-version-changed", bridge.upload(request) { true }.action.reason)
        source.writeBytes(content)
        assertEquals(ActionStatus.REFUSED, bridge.upload(request.copy(pageUrl = "$url/changed")) { true }.action.status)
        assertEquals(
            ActionStatus.REFUSED,
            bridge.upload(request.copy(path = source.absolutePath)) { true }.action.status,
        )
        assertEquals(ActionStatus.REFUSED, bridge.upload(request) { false }.action.status)
        assertTrue(bridge.snapshot(tab).nodes.any { it.value == "none" })
    }

    @Test
    fun oversizedSourceAndWrongInputDoNotAttach() {
        val request = request()
        source.writeBytes(ByteArray(1024 * 1024 + 1))
        assertEquals(ActionStatus.REFUSED, bridge.upload(request) { true }.action.status)
        source.writeBytes(content)
        val normal = bridge.snapshot(tab).nodes.single { it.value == "none" }
        assertEquals(ActionStatus.REFUSED, bridge.upload(request.copy(token = normal.token)) { true }.action.status)
    }

    @Test
    fun liveDomReorderingAndSameUrlReloadRejectOriginalToken() {
        val original = request()
        val changed = CountDownLatch(1)
        fixture.scenario.onActivity {
            fixture.controller.hostView(tab)!!.evaluateJavascript(
                "document.body.insertAdjacentHTML('afterbegin', '<input type=file id=other>');",
                { changed.countDown() },
            )
        }
        assertTrue(changed.await(5, TimeUnit.SECONDS))
        assertEquals("live-dom-changed", bridge.upload(original) { true }.action.reason)
        val beforeReload = request()
        fixture.scenario.onActivity {
            fixture.controller.hostView(tab)!!.loadDataWithBaseURL(
                url,
                "<input type=file><input value=none>",
                "text/html",
                "UTF-8",
                null,
            )
        }
        Thread.sleep(300)
        assertEquals(ActionStatus.REFUSED, bridge.upload(beforeReload) { true }.action.status)
    }
}
