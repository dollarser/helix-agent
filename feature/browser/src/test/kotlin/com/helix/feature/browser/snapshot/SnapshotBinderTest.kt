package com.helix.feature.browser.snapshot

import com.helix.feature.browser.BrowserTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotBinderTest {
    private val tab =
        BrowserTab(id = "tab-1", url = "https://helix.example/p", title = "Page", navigationGeneration = 3)
    private val now = 1_000_000L

    private fun strOrNull(v: String?): String = if (v == null) "null" else "\"$v\""

    private fun node(
        i: Int,
        tag: String = "a",
        role: String = "link",
        text: String = "t",
        value: String? = null,
        href: String? = null,
        name: String? = null,
    ): String {
        val v = strOrNull(value)
        val h = strOrNull(href)
        val n = strOrNull(name)
        return """{"i":$i,"tag":"$tag","role":"$role","text":"$text","value":$v,"href":$h,"name":$n}"""
    }

    private fun payload(
        vararg nodes: String,
        version: Int = BrowserSnapshotScript.SCRIPT_VERSION,
        truncated: Boolean = false,
    ): String = """{"v":$version,"truncated":$truncated,"nodes":[${nodes.joinToString(",")}]}"""

    private fun bind(raw: String?): SnapshotResult = SnapshotBinder.bind(raw, tab, now)

    @Test
    fun aWellFormedTreeBindsAndMintsTokens() {
        val raw =
            payload(
                node(0, "h1", "heading", "Title"),
                node(1, "a", "link", "Home", href = "/"),
                node(2, "button", "button", "Go"),
            )
        val snapshot = (bind(raw) as SnapshotResult.Success).snapshot
        assertEquals("tab-1", snapshot.tabId)
        assertEquals("https://helix.example", snapshot.origin)
        assertEquals(3L, snapshot.navigationGeneration)
        assertEquals(3, snapshot.nodeCount)
        assertEquals(3, snapshot.nodes.size)
        assertEquals("h1", snapshot.nodes[0].tag)
        assertEquals("heading", snapshot.nodes[0].role)
        assertEquals("Title", snapshot.nodes[0].text.text)
        assertEquals("/", snapshot.nodes[1].href?.text)
        assertNotNull(snapshot.fingerprint)
    }

    @Test
    fun aNodeTokenParsesBackToItsBindings() {
        val raw = payload(node(0, "a", "link", "Home", href = "/"))
        val snapshot = (bind(raw) as SnapshotResult.Success).snapshot
        val token = SnapshotToken.parse(snapshot.nodes[0].token)
        assertNotNull(token)
        assertEquals("tab-1", token!!.tabId)
        assertEquals("https://helix.example", token.origin)
        assertEquals(3L, token.navigationGeneration)
        assertEquals(snapshot.fingerprint, token.fingerprint)
        assertEquals(0, token.nodeIndex)
        assertEquals(now, token.mintedAtMillis)
    }

    @Test
    fun aPasswordFieldIsNullValueNotAFailure() {
        val raw = payload(node(0, "input", "field", "pw", value = null))
        val snapshot = (bind(raw) as SnapshotResult.Success).snapshot
        assertNull(snapshot.nodes[0].value)
        assertEquals("field", snapshot.nodes[0].role)
    }

    @Test
    fun anEmptyTreeIsAValidSuccess() {
        val snapshot = (bind(payload()) as SnapshotResult.Success).snapshot
        assertEquals(0, snapshot.nodeCount)
        assertTrue(snapshot.nodes.isEmpty())
    }

    @Test
    fun theFingerprintIsDeterministicAndContentSensitive() {
        val raw = payload(node(0, "a", "link", "A", href = "/x"), node(1, "a", "link", "B", href = "/y"))
        val a = (bind(raw) as SnapshotResult.Success).snapshot.fingerprint
        val b = (bind(raw) as SnapshotResult.Success).snapshot.fingerprint
        assertEquals(a, b)
        val other =
            payload(
                node(0, "a", "link", "A", href = "/x"),
                node(1, "a", "link", "DIFFERENT", href = "/y"),
            )
        val c = (bind(other) as SnapshotResult.Success).snapshot.fingerprint
        assertNotEquals(a, c)
    }

    @Test
    fun aDataTabBindsToItsOpaqueOrigin() {
        val dataTab = BrowserTab(id = "t", url = "data:text/html,<h1>x</h1>", navigationGeneration = 1)
        val snapshot =
            (SnapshotBinder.bind(payload(node(0, "h1", "heading", "x")), dataTab, now) as SnapshotResult.Success)
                .snapshot
        assertEquals("data:opaque", snapshot.origin)
    }

    @Test
    fun anAboutBlankTabBindsToItsBlankOrigin() {
        val blankTab = BrowserTab(id = "t", url = "about:blank", navigationGeneration = 1)
        val snapshot =
            (SnapshotBinder.bind(payload(node(0, "h1", "heading", "x")), blankTab, now) as SnapshotResult.Success)
                .snapshot
        assertEquals("about:blank", snapshot.origin)
    }

    @Test
    fun aNullOrBlankResultFailsClosed() {
        assertEquals(SnapshotResult.Failed(SnapshotFailure.NO_RESULT), bind(null))
        assertEquals(SnapshotResult.Failed(SnapshotFailure.NO_RESULT), bind("   "))
    }

    @Test
    fun aNonJsonObjectFailsUnparseable() {
        assertEquals(SnapshotResult.Failed(SnapshotFailure.UNPARSEABLE_RESULT), bind("not json at all"))
        assertEquals(SnapshotResult.Failed(SnapshotFailure.UNPARSEABLE_RESULT), bind("[1,2,3]"))
        assertEquals(SnapshotResult.Failed(SnapshotFailure.UNPARSEABLE_RESULT), bind("\"a string\""))
    }

    @Test
    fun aWrongVersionFailsVersionMismatch() {
        assertEquals(SnapshotResult.Failed(SnapshotFailure.VERSION_MISMATCH), bind(payload(node(0), version = 99)))
    }

    @Test
    fun aMissingOrNonNumericVersionFailsMalformed() {
        val noVersion = """{"truncated":false,"nodes":[]}"""
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(noVersion))
        val nonNumeric = """{"v":"one","truncated":false,"nodes":[]}"""
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(nonNumeric))
    }

    @Test
    fun aNonBooleanTruncatedFailsMalformed() {
        val raw = """{"v":${BrowserSnapshotScript.SCRIPT_VERSION},"truncated":"yes","nodes":[]}"""
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(raw))
    }

    @Test
    fun aMissingNodesFieldFailsMalformed() {
        val raw = """{"v":${BrowserSnapshotScript.SCRIPT_VERSION},"truncated":false}"""
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(raw))
    }

    @Test
    fun anOverBudgetTreeFailsOverBudget() {
        val nodes = (0 until BrowserSnapshotScript.MAX_NODES + 1).joinToString(",") { node(it) }
        val raw = """{"v":${BrowserSnapshotScript.SCRIPT_VERSION},"truncated":true,"nodes":[$nodes]}"""
        assertEquals(SnapshotResult.Failed(SnapshotFailure.OVER_BUDGET), bind(raw))
    }

    @Test
    fun anExactlyAtBudgetTreeIsAccepted() {
        val nodes = (0 until BrowserSnapshotScript.MAX_NODES).joinToString(",") { node(it) }
        val raw = """{"v":${BrowserSnapshotScript.SCRIPT_VERSION},"truncated":true,"nodes":[$nodes]}"""
        val snapshot = (bind(raw) as SnapshotResult.Success).snapshot
        assertEquals(BrowserSnapshotScript.MAX_NODES, snapshot.nodeCount)
    }

    @Test
    fun aNonSequentialNodeIndexFailsMalformed() {
        val raw = payload(node(0), node(5)) // gap: 0 then 5
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(raw))
    }

    @Test
    fun anUnknownRoleFailsMalformed() {
        val raw = payload(node(0, "div", "iframe-hostile", "x"))
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(raw))
    }

    @Test
    fun anUppercaseOrOddTagFailsMalformed() {
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(payload(node(0, "A", "link", "x"))))
        val oddTag = payload(node(0, "a b", "link", "x"))
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(oddTag))
    }

    @Test
    fun anOversizedTextFieldFailsMalformed() {
        val huge = "x".repeat(BrowserSnapshotScript.MAX_TEXT_LENGTH + 1)
        val raw = payload(node(0, "a", "link", huge))
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(raw))
    }

    @Test
    fun aExactlySizedTextFieldIsAccepted() {
        val exact = "x".repeat(BrowserSnapshotScript.MAX_TEXT_LENGTH)
        val snapshot = (bind(payload(node(0, "a", "link", exact))) as SnapshotResult.Success).snapshot
        assertEquals(
            BrowserSnapshotScript.MAX_TEXT_LENGTH,
            snapshot.nodes[0]
                .text.text.length,
        )
    }

    @Test
    fun aNonStringTextFieldFailsMalformed() {
        val nodeJson = """{"i":0,"tag":"a","role":"link","text":42,"value":null,"href":null,"name":null}"""
        val raw = """{"v":${BrowserSnapshotScript.SCRIPT_VERSION},"truncated":false,"nodes":[$nodeJson]}"""
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(raw))
    }

    @Test
    fun aPresentOversizedOptionalFieldFailsMalformed() {
        val huge = "x".repeat(BrowserSnapshotScript.MAX_TEXT_LENGTH + 1)
        val raw = payload(node(0, "a", "link", "ok", value = huge))
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(raw))
    }

    @Test
    fun aNonObjectNodeFailsMalformed() {
        val raw = """{"v":${BrowserSnapshotScript.SCRIPT_VERSION},"truncated":false,"nodes":["a string node"]}"""
        assertEquals(SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT), bind(raw))
    }
}
