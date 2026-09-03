package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HXA-064 (verification matrix row `:tools:android:testDebugUnitTest`): the `android.*` /
 * `clipboard.*` tools' fail-closed outcome → result mapping. A [FakeBridge] returns canned
 * [AndroidSystemBridge] outcomes; each tool's executor must never claim success on a refusal or a
 * timeout, must fail invalid/missing arguments, must emit no JSON nulls, and the http/https gate
 * [isHttpUrl] must refuse every non-http(s) scheme. The mapping IS the tool layer — the intent /
 * clipboard / foreground work lives in the port impl (this module), exercised on device.
 */
class AndroidSystemToolsMappingTest {
    private val bridge = FakeBridge()

    private val noCancel =
        object : CancelSignal {
            override fun isCancelled(): Boolean = false
        }

    private fun call(
        name: String,
        args: JsonObject,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "call-1",
            toolName = name,
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel = noCancel,
        )

    private fun run(
        name: String,
        executor: ToolExecutor,
        args: JsonObject,
    ): ToolExecutorResult = executor.execute(call(name, args))

    private fun json(result: ToolExecutorResult): JsonObject {
        val c = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")
        return c.output.jsonObject
    }

    private fun failed(result: ToolExecutorResult): ToolExecutorResult.Failed {
        val f = result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")
        return f
    }

    /** Walks a result's Completed output and asserts it carries no JSON nulls anywhere. */
    private fun assertNoNulls(result: ToolExecutorResult) {
        val c = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")

        fun walk(e: JsonElement) {
            assertTrue("found a JSON null in tool output", e !is JsonNull)
            when (e) {
                is JsonObject -> e.values.forEach { walk(it) }
                is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(c.output)
    }

    // ── android.open_uri ──────────────────────────────────────────────────────────────────

    @Test
    fun openUriOpenedEmitsOpenedWithEmptyReason() {
        bridge.openResult = OpenUriOutcome(OpenUriStatus.OPENED, "https://example.com/", "")
        val out =
            json(
                run(
                    AndroidOpenUriTool.NAME,
                    AndroidOpenUriTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://example.com/")) },
                ),
            )
        assertEquals("opened", out.getValue("status").jsonPrimitive.content)
        assertEquals("https://example.com/", out.getValue("url").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun openUriRefusedKeepsTheSchemeReason() {
        bridge.openResult = OpenUriOutcome(OpenUriStatus.REFUSED, "file:///etc/passwd", "scheme")
        val out =
            json(
                run(
                    AndroidOpenUriTool.NAME,
                    AndroidOpenUriTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("file:///etc/passwd")) },
                ),
            )
        assertEquals("refused", out.getValue("status").jsonPrimitive.content)
        assertEquals("scheme", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun openUriNoHandlerEmitsNoHandler() {
        bridge.openResult = OpenUriOutcome(OpenUriStatus.NO_HANDLER, "https://example.com/", "no app to open")
        val out =
            json(
                run(
                    AndroidOpenUriTool.NAME,
                    AndroidOpenUriTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://example.com/")) },
                ),
            )
        assertEquals("no-handler", out.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun openUriErrorIsAFailClosedFailure() {
        bridge.openResult = OpenUriOutcome(OpenUriStatus.ERROR, "https://example.com/", "launch failed")
        val f =
            failed(
                run(
                    AndroidOpenUriTool.NAME,
                    AndroidOpenUriTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://example.com/")) },
                ),
            )
        assertEquals("launch failed", f.detail)
    }

    @Test
    fun openUriMissingUrlIsAFailed() {
        val f =
            failed(
                run(AndroidOpenUriTool.NAME, AndroidOpenUriTool.executor(bridge), buildJsonObject {}),
            )
        assertTrue(f.detail.contains("url"))
    }

    // ── clipboard.read ─────────────────────────────────────────────────────────────────────

    @Test
    fun clipboardReadEmitsTheBoundedText() {
        bridge.clipReadResult =
            ClipboardReadOutcome(ClipboardReadStatus.READ, "hello", 5, false, "")
        val out =
            json(run(ClipboardReadTool.NAME, ClipboardReadTool.executor(bridge), buildJsonObject {}))
        assertEquals("read", out.getValue("status").jsonPrimitive.content)
        assertEquals("hello", out.getValue("text").jsonPrimitive.content)
        assertEquals(
            5,
            out
                .getValue("length")
                .jsonPrimitive.content
                .toInt(),
        )
        assertFalse(
            out
                .getValue("truncated")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clipboardReadRefusedEmitsRefusedWithEmptyText() {
        bridge.clipReadResult = ClipboardReadOutcome(ClipboardReadStatus.REFUSED, "", 0, false, "not-foreground")
        val out =
            json(run(ClipboardReadTool.NAME, ClipboardReadTool.executor(bridge), buildJsonObject {}))
        assertEquals("refused", out.getValue("status").jsonPrimitive.content)
        assertEquals("", out.getValue("text").jsonPrimitive.content)
        assertEquals("not-foreground", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clipboardReadErrorIsAFailClosedFailure() {
        bridge.clipReadResult = ClipboardReadOutcome(ClipboardReadStatus.ERROR, "", 0, false, "clipboard read failed")
        val f = failed(run(ClipboardReadTool.NAME, ClipboardReadTool.executor(bridge), buildJsonObject {}))
        assertEquals("clipboard read failed", f.detail)
    }

    // ── clipboard.write ────────────────────────────────────────────────────────────────────

    @Test
    fun clipboardWriteEmitsWrittenWithLength() {
        bridge.clipWriteResult = ClipboardWriteOutcome(ClipboardWriteStatus.WRITTEN, 12, "")
        val out =
            json(
                run(
                    ClipboardWriteTool.NAME,
                    ClipboardWriteTool.executor(bridge),
                    buildJsonObject { put("text", JsonPrimitive("hello world!")) },
                ),
            )
        assertEquals("written", out.getValue("status").jsonPrimitive.content)
        assertEquals(
            12,
            out
                .getValue("length")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clipboardWriteRefusedKeepsTheReason() {
        bridge.clipWriteResult = ClipboardWriteOutcome(ClipboardWriteStatus.REFUSED, 0, "not-foreground")
        val out =
            json(
                run(
                    ClipboardWriteTool.NAME,
                    ClipboardWriteTool.executor(bridge),
                    buildJsonObject { put("text", JsonPrimitive("secret")) },
                ),
            )
        assertEquals("refused", out.getValue("status").jsonPrimitive.content)
        assertEquals(
            0,
            out
                .getValue("length")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals("not-foreground", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clipboardWriteErrorIsAFailClosedFailure() {
        bridge.clipWriteResult = ClipboardWriteOutcome(ClipboardWriteStatus.ERROR, 0, "clipboard write failed")
        val f =
            failed(
                run(
                    ClipboardWriteTool.NAME,
                    ClipboardWriteTool.executor(bridge),
                    buildJsonObject { put("text", JsonPrimitive("secret")) },
                ),
            )
        assertEquals("clipboard write failed", f.detail)
    }

    @Test
    fun clipboardWriteMissingTextIsAFailed() {
        val f =
            failed(
                run(ClipboardWriteTool.NAME, ClipboardWriteTool.executor(bridge), buildJsonObject {}),
            )
        assertTrue(f.detail.contains("text"))
    }

    // ── android.share ──────────────────────────────────────────────────────────────────────

    @Test
    fun shareSharedEmitsSharedWithEmptyReason() {
        bridge.shareResult = ShareOutcome(ShareStatus.SHARED, "")
        val out =
            json(
                run(
                    AndroidShareTool.NAME,
                    AndroidShareTool.executor(bridge),
                    buildJsonObject {
                        put("text", JsonPrimitive("hello"))
                        put("subject", JsonPrimitive("greeting"))
                    },
                ),
            )
        assertEquals("shared", out.getValue("status").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun shareNoHandlerEmitsNoHandler() {
        bridge.shareResult = ShareOutcome(ShareStatus.NO_HANDLER, "no app to share to")
        val out =
            json(
                run(
                    AndroidShareTool.NAME,
                    AndroidShareTool.executor(bridge),
                    buildJsonObject { put("text", JsonPrimitive("hello")) },
                ),
            )
        assertEquals("no-handler", out.getValue("status").jsonPrimitive.content)
        assertEquals("no app to share to", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun shareErrorIsAFailClosedFailure() {
        bridge.shareResult = ShareOutcome(ShareStatus.ERROR, "share failed")
        val f =
            failed(
                run(
                    AndroidShareTool.NAME,
                    AndroidShareTool.executor(bridge),
                    buildJsonObject { put("text", JsonPrimitive("hello")) },
                ),
            )
        assertEquals("share failed", f.detail)
    }

    @Test
    fun shareMissingTextIsAFailed() {
        val f =
            failed(
                run(AndroidShareTool.NAME, AndroidShareTool.executor(bridge), buildJsonObject {}),
            )
        assertTrue(f.detail.contains("text"))
    }

    // ── output has no JSON nulls ───────────────────────────────────────────────────────────

    @Test
    fun everyCompletedOutputCarriesNoJsonNulls() {
        bridge.openResult = OpenUriOutcome(OpenUriStatus.OPENED, "https://x", "")
        bridge.clipReadResult = ClipboardReadOutcome(ClipboardReadStatus.READ, "abc", 3, false, "")
        bridge.clipWriteResult = ClipboardWriteOutcome(ClipboardWriteStatus.WRITTEN, 3, "")
        bridge.shareResult = ShareOutcome(ShareStatus.SHARED, "")

        assertNoNulls(
            run(
                AndroidOpenUriTool.NAME,
                AndroidOpenUriTool.executor(bridge),
                buildJsonObject {
                    put("url", JsonPrimitive("https://x"))
                },
            ),
        )
        assertNoNulls(run(ClipboardReadTool.NAME, ClipboardReadTool.executor(bridge), buildJsonObject {}))
        assertNoNulls(
            run(
                ClipboardWriteTool.NAME,
                ClipboardWriteTool.executor(bridge),
                buildJsonObject {
                    put("text", JsonPrimitive("abc"))
                },
            ),
        )
        assertNoNulls(
            run(
                AndroidShareTool.NAME,
                AndroidShareTool.executor(bridge),
                buildJsonObject {
                    put("text", JsonPrimitive("abc"))
                },
            ),
        )
    }

    // ── the http/https gate (isHttpUrl) ────────────────────────────────────────────────────

    @Test
    fun isHttpUrlAcceptsOnlyAbsoluteHttpAndHttps() {
        assertTrue(isHttpUrl("https://example.com"))
        assertTrue(isHttpUrl("http://example.com"))
        assertTrue(isHttpUrl("https://example.com/path?x=1#frag"))
        assertTrue(isHttpUrl("  https://example.com  ")) // surrounding whitespace is trimmed
    }

    @Test
    fun isHttpUrlRefusesEveryOtherScheme() {
        assertFalse(isHttpUrl("file:///etc/passwd"))
        assertFalse(isHttpUrl("ftp://example.com/file"))
        assertFalse(isHttpUrl("javascript:alert(1)"))
        assertFalse(isHttpUrl("tel:12345"))
        assertFalse(isHttpUrl("market://details?id=com.example"))
        assertFalse(isHttpUrl("intent://x#Intent;end"))
    }

    @Test
    fun isHttpUrlRefusesOpaqueOrMalformed() {
        assertFalse(isHttpUrl(""))
        assertFalse(isHttpUrl("   "))
        assertFalse(isHttpUrl("http:foo")) // opaque http URI with no host
        assertFalse(isHttpUrl("https:")) // no host
        assertFalse(isHttpUrl("not a url at all")) // unparseable
    }

    // ── the clipboard read bound (boundClipboardText) ───────────────────────────────────────

    @Test
    fun boundClipboardTextPassesShortTextThroughUnchanged() {
        val b = boundClipboardText("hello")
        assertEquals("hello", b.text)
        assertEquals(5, b.length)
        assertFalse(b.truncated)
    }

    @Test
    fun boundClipboardTextPassesEmptyThroughUnchanged() {
        val b = boundClipboardText("")
        assertEquals("", b.text)
        assertEquals(0, b.length)
        assertFalse(b.truncated)
    }

    @Test
    fun boundClipboardTextPassesTextExactlyAtTheBoundUnchanged() {
        val exact = "x".repeat(MAX_CLIPBOARD_READ)
        val b = boundClipboardText(exact)
        assertEquals(MAX_CLIPBOARD_READ, b.text.length)
        assertEquals(MAX_CLIPBOARD_READ, b.length)
        assertFalse("a string exactly at the bound is NOT truncated", b.truncated)
    }

    @Test
    fun boundClipboardTextCutsLongTextAndFlagsTruncation() {
        val big = "y".repeat(MAX_CLIPBOARD_READ + 1_000)
        val b = boundClipboardText(big)
        assertEquals(MAX_CLIPBOARD_READ, b.text.length)
        assertEquals(MAX_CLIPBOARD_READ + 1_000, b.length)
        assertTrue(b.truncated)
    }

    @Test
    fun boundClipboardTextHonoursAnExplicitBound() {
        val b = boundClipboardText("0123456789", 4)
        assertEquals("0123", b.text)
        assertEquals(10, b.length)
        assertTrue(b.truncated)
    }

    // ── descriptor contract fields ─────────────────────────────────────────────────────────

    private fun checkDescriptor(
        d: ToolDescriptor,
        name: String,
    ) {
        assertEquals(name, d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(RiskLevel.L2, d.baseRisk)
        assertEquals(ToolOperationClass.EXTERNAL_ACTION, d.operationClass)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, d.executionTarget)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        assertTrue(d.requiredCapabilities.isEmpty())
    }

    @Test
    fun descriptorsCarryTheExpectedContract() {
        checkDescriptor(AndroidOpenUriTool.descriptor(), "android.open_uri")
        checkDescriptor(ClipboardReadTool.descriptor(), "clipboard.read")
        checkDescriptor(ClipboardWriteTool.descriptor(), "clipboard.write")
        checkDescriptor(AndroidShareTool.descriptor(), "android.share")
    }

    @Test
    fun clipboardReadIsIdempotentAndTheRestAreNot() {
        assertEquals(Idempotency.IDEMPOTENT, ClipboardReadTool.descriptor().idempotency)
        assertEquals(Idempotency.IDEMPOTENT, ClipboardWriteTool.descriptor().idempotency)
        assertEquals(Idempotency.NON_IDEMPOTENT, AndroidOpenUriTool.descriptor().idempotency)
        assertEquals(Idempotency.NON_IDEMPOTENT, AndroidShareTool.descriptor().idempotency)
    }
}

/** A settable fake of the [AndroidSystemBridge] port for the mapping tests. */
private class FakeBridge : AndroidSystemBridge {
    var openResult: OpenUriOutcome = OpenUriOutcome(OpenUriStatus.ERROR, "", "unset")
    var clipReadResult: ClipboardReadOutcome = ClipboardReadOutcome(ClipboardReadStatus.ERROR, "", 0, false, "unset")
    var clipWriteResult: ClipboardWriteOutcome = ClipboardWriteOutcome(ClipboardWriteStatus.ERROR, 0, "unset")
    var shareResult: ShareOutcome = ShareOutcome(ShareStatus.ERROR, "unset")

    override fun openUri(url: String): OpenUriOutcome = openResult

    override fun clipboardRead(): ClipboardReadOutcome = clipReadResult

    override fun clipboardWrite(text: String): ClipboardWriteOutcome = clipWriteResult

    override fun share(
        text: String,
        subject: String,
    ): ShareOutcome = shareResult
}
