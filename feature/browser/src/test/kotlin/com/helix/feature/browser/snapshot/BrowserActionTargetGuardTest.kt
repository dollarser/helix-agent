package com.helix.feature.browser.snapshot

import com.helix.feature.browser.BrowserToolResultMapper
import com.helix.tools.browser.ActionStatus
import com.helix.tools.browser.BrowserNodeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserActionTargetGuardTest {
    @Test
    fun everyNodeActionChecksHostObservedDataBeforeDispatchUsingLiteralPayloads() {
        val text = "\";throw Error('injected');//"
        val node =
            SnapshotNode(
                0,
                "button",
                "button",
                UntrustedWebContent(text),
                null,
                null,
                UntrustedWebContent("go"),
                "token",
            )
        val scripts =
            listOf(BrowserActionScript.click(0), BrowserActionScript.type(0, text)) +
                BrowserNodeAction.entries.map { BrowserActionScript.nodeAction(0, it, text) }
        for (script in scripts) {
            val bound = BrowserActionTargetGuard.bind(script, node)
            assertFalse(bound.contains(BrowserActionScript.TARGET_CHECK))
            assertTrue(bound.contains("\"text\":" + kotlinx.serialization.json.JsonPrimitive(text)))
            assertTrue(bound.contains(BrowserSnapshotScript.NODE_HELPERS))
            assertTrue(bound.indexOf("status:\"stale-target\"") < bound.indexOf("var tag ="))
            assertTrue(bound.contains("valueOf(target) === expected.value"))
            assertTrue(bound.contains("=== expected.href"))
        }
    }

    @Test
    fun staleTargetMapsToNonExecutedStaleOutcomeRatherThanUnknown() {
        val result = BrowserToolResultMapper.mapAction("""{"v":1,"status":"stale-target"}""", 0)
        assertEquals(ActionStatus.STALE_TOKEN, result.status)
        assertEquals("stale-target", result.reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scriptsWithoutAnExplicitNodeGuardBoundaryAreRejected() {
        val node = SnapshotNode(0, "button", "button", UntrustedWebContent("Go"), null, null, null, "token")
        BrowserActionTargetGuard.bind(BrowserActionScript.scroll(0, 1), node)
    }
}
