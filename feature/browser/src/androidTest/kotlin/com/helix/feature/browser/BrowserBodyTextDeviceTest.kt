package com.helix.feature.browser

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBodyTextDeviceTest {
    @Test fun readableProseExcludesHiddenAndEditableContentWithoutChangingActionIndices() =
        withBridge { c, b ->
            val html =
                """
                <h1>Example Domains</h1><p>Reserved for use in documentation.</p>
                <p>Not available for registration or transfer.</p>
                <div hidden>HIDDEN_SENTINEL</div><div style="display:none">DISPLAY_SENTINEL</div>
                <div style="visibility:hidden">VISIBILITY_SENTINEL
                    <span style="visibility:visible">Restored visible child</span>HIDDEN_AFTER_SENTINEL
                </div>
                <div style="opacity:0">OPACITY_SENTINEL</div>
                <div aria-hidden="true">ARIA_SENTINEL</div>
                <script>var x='SCRIPT_SENTINEL';</script><style>/* STYLE_SENTINEL */</style>
                <template>TEMPLATE_SENTINEL</template><textarea>TEXTAREA_SENTINEL</textarea>
                <input type="password" value="PASSWORD_SENTINEL">
                <input value="INPUT_SENTINEL"><select><option>SELECT_SENTINEL</option></select>
                <div contenteditable="true">EDITABLE_SENTINEL</div>
                <details><summary>Summary</summary>CLOSED_SENTINEL</details>
                <a href="https://example.com"
                    onclick="document.querySelector('p').textContent='Correct link clicked';return false;">Example link</a>
                <button>Continue</button>
                """.trimIndent()
            val id = b.open("data:text/html," + android.net.Uri.encode(html), "s").tabId
            settle(c, id)
            val first = b.snapshot(id)
            assertTrue(first.ok)
            assertTrue(first.text.contains("Reserved for use in documentation."))
            assertTrue(first.text.contains("Not available for registration or transfer."))
            assertFalse(first.text, first.text.contains("SENTINEL"))
            assertTrue(first.text.contains("Restored visible child"))
            assertFalse(first.textTruncated)
            val actionNodes = first.nodes.map { it.index to it.role }
            onMain {
                c.hostView(id)!!.evaluateJavascript(
                    "document.querySelector('p').textContent='Updated dynamic prose';",
                    null,
                )
            }
            val second = b.snapshot(id)
            assertTrue(second.text.contains("Updated dynamic prose"))
            assertFalse(second.text.contains("Reserved for use"))
            assertEquals(actionNodes, second.nodes.map { it.index to it.role })
            assertEquals(first.fingerprint, second.fingerprint)
            val link = second.nodes.single { it.text == "Example link" }
            assertEquals("link", link.role)
            assertEquals(com.helix.tools.browser.ActionStatus.PERFORMED, b.click(id, link.token).status)
            assertTrue(b.snapshot(id).text.contains("Correct link clicked"))
        }

    @Test fun textAndTraversalBudgetsAreExplicitAndIndependentFromNodeBudget() =
        withBridge { c, b ->
            val html = "<p>" + "x".repeat(12001) + "</p><button>After body budget</button>"
            val id = b.open("data:text/html," + android.net.Uri.encode(html), "s").tabId
            settle(c, id)
            val result = b.snapshot(id)
            assertTrue(result.ok)
            assertEquals(12000, result.text.length)
            assertTrue(result.textTruncated)
            assertFalse(result.truncated)
            assertTrue(result.nodes.any { it.text == "After body budget" })
            onMain {
                c.hostView(id)!!.evaluateJavascript(
                    "document.body.innerHTML='<div></div>'.repeat(20001)+'tail';",
                    null,
                )
            }
            val many = b.snapshot(id)
            assertTrue(many.ok)
            assertTrue(many.textTruncated)
            assertFalse(many.text.contains("tail"))
        }

    private fun withBridge(block: (BrowserController, BrowserToolBridgeImpl) -> Unit) {
        BrowserActivityFixture().use { fixture ->
            val directory =
                java.io
                    .File(
                        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                        "observation-test",
                    ).apply {
                        mkdirs()
                    }
            val bridge =
                BrowserToolBridgeImpl(
                    fixture.controller,
                    WorkspaceArtifactStore(ScopeRootResolver { directory.toPath() }),
                    "fixture",
                )
            try {
                block(fixture.controller, bridge)
            } finally {
                onMain { fixture.controller.destroy() }
            }
        }
    }

    private fun settle(
        c: BrowserController,
        id: String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < deadline) {
            val tab = c.tab(id)!!
            if (!tab.isLoading && tab.navigationGeneration > 0) return
            SystemClock.sleep(30)
        }
        error("fixture page did not settle")
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
