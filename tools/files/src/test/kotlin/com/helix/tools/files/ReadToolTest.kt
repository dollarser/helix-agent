package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolSchema
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * HXA-042 (verification matrix row `:tools:files:test`): the `read` tool — the FIRST non-`time.now`
 * business tool in the production tool table. Covers the mandated contract:
 * - the descriptor is a valid, registerable built-in (L1 READ_ONLY idempotent LOCAL_ANDROID);
 * - offset/maxBytes paging: a 10 MiB file is exactly ten 1 MiB windows with no byte skipped or
 *   re-read, and each window's `nextOffset` is the next `offset`;
 * - encoding boundary: a multi-byte UTF-8 sequence straddling the window end is dropped from the
 *   returned text and `nextOffset` stops at the sequence start, so the windows concatenate to the
 *   exact original content;
 * - stable EOF: an offset at/past the end is a terminal window (no bytes, not an error);
 * - binary content returns a base64 payload (no `text`);
 * - failure / boundary: missing file, invalid model reference, and cancel fail closed.
 *
 * All cases run against a JVM temp dir (matrix device column: 无 — no emulator).
 */
class ReadToolTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): Path {
        val p = tmp.newFolder("ws").toPath()
        WorkspaceArtifactStore(ScopeRootResolver { _ -> p }).ensureLayout("ws")
        return p
    }

    private fun store(root: Path): WorkspaceArtifactStore = WorkspaceArtifactStore(ScopeRootResolver { _ -> root })

    private fun write(
        root: Path,
        rel: String,
        bytes: ByteArray,
    ): FileScopePath {
        val real = root.resolve(rel)
        real.parent?.let { Files.createDirectories(it) }
        Files.write(real, bytes)
        return FileScopePath("ws", rel)
    }

    private val noCancel =
        object : CancelSignal {
            override fun isCancelled(): Boolean = false
        }

    private fun call(
        args: JsonObject,
        cancel: CancelSignal = noCancel,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "call-1",
            toolName = "read",
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel = cancel,
        )

    private fun read(
        root: Path,
        path: FileScopePath,
        offset: Long? = null,
        maxBytes: Long? = null,
    ): ToolExecutorResult {
        val args =
            buildJsonObject {
                put("path", JsonPrimitive(path.toModelReference()))
                offset?.let { put("offset", JsonPrimitive(it)) }
                maxBytes?.let { put("maxBytes", JsonPrimitive(it)) }
            }
        return ReadTool.executor(store(root)).execute(call(args))
    }

    private fun json(result: ToolExecutorResult): JsonObject {
        val completed = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")
        return completed.output.jsonObject
    }

    private fun str(
        out: JsonObject,
        key: String,
    ): String = out.getValue(key).jsonPrimitive.content

    private fun num(
        out: JsonObject,
        key: String,
    ): Long =
        out
            .getValue(key)
            .jsonPrimitive.content
            .toLong()

    private fun flag(
        out: JsonObject,
        key: String,
    ): Boolean =
        out
            .getValue(key)
            .jsonPrimitive.content
            .toBoolean()

    // ── descriptor contract ─────────────────────────────────────────────────────────────

    @Test
    fun descriptorIsAValidRegisterableBuiltIn() {
        val d = ReadTool.descriptor()
        assertEquals("read", d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(ToolOperationClass.READ_ONLY, d.operationClass)
        assertEquals(RiskLevel.L1, d.baseRisk)
        assertEquals(Idempotency.IDEMPOTENT, d.idempotency)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, d.executionTarget)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        assertTrue(ToolSchema.check(d.inputSchema).isEmpty())
        assertTrue(ToolSchema.check(d.outputSchema).isEmpty())
    }

    // ── basic read + paging ─────────────────────────────────────────────────────────────

    @Test
    fun readsWholeSmallFileFromOffsetZero() {
        val root = root()
        val path = write(root, "work/a.txt", "hello read".toByteArray())
        val out = json(read(root, path, 0, 1024))
        assertEquals("hello read", str(out, "text"))
        assertEquals(0L, num(out, "offset"))
        assertEquals(10L, num(out, "windowLength"))
        assertEquals(10L, num(out, "sizeBytes"))
        assertEquals("UTF8", str(out, "encoding"))
        assertEquals(10L, num(out, "consumedBytes"))
        assertEquals(10L, num(out, "nextOffset"))
        assertEquals(true, flag(out, "eof"))
        assertNull(out["base64"])
    }

    @Test
    fun aWindowSmallerThanTheFileReportsNotEofAndAdvances() {
        val root = root()
        val path = write(root, "work/b.txt", "0123456789".toByteArray())
        val out = json(read(root, path, 0, 4))
        assertEquals("0123", str(out, "text"))
        assertEquals(4L, num(out, "windowLength"))
        assertEquals(4L, num(out, "nextOffset"))
        assertEquals(false, flag(out, "eof"))
        val next = json(read(root, path, num(out, "nextOffset"), 4))
        assertEquals("4567", str(next, "text"))
    }

    @Test
    fun omittingOffsetAndMaxBytesReadsFromTheStartWithTheDefaultCap() {
        val root = root()
        val path = write(root, "work/c.txt", "abc".toByteArray())
        val out = json(read(root, path))
        assertEquals("abc", str(out, "text"))
        assertEquals(true, flag(out, "eof"))
        // A file larger than the default 1 MiB cap is truncated to one 1 MiB window, not fully returned.
        val big = write(root, "work/cbig.bin", ByteArray(2 * 1024 * 1024) { 1 })
        val bigOut = json(read(root, big))
        assertEquals(1024L * 1024L, num(bigOut, "windowLength"))
        assertEquals(false, flag(bigOut, "eof"))
    }

    // ── 10 MiB chunking ─────────────────────────────────────────────────────────────────

    @Test
    fun aTenMiBFileIsExactlyTenOneMiBWindowsWithNoByteSkipped() {
        val root = root()
        val total = 10L * 1024L * 1024L
        val pattern = ByteArray(10) { (48 + it).toByte() } // '0'..'9'
        val path = write(root, "work/big.bin", ByteArray(total.toInt()) { pattern[it % 10] })
        assertEquals(total, Files.size(root.resolve("work/big.bin")))

        val out1 = json(read(root, path, 0, 1024L * 1024L))
        assertEquals(1024L * 1024L, num(out1, "windowLength"))
        assertEquals(false, flag(out1, "eof"))
        assertEquals(1024L * 1024L, num(out1, "nextOffset"))

        val last = json(read(root, path, 9L * 1024L * 1024L, 1024L * 1024L))
        assertEquals(1024L * 1024L, num(last, "windowLength"))
        assertEquals(true, flag(last, "eof"))
        assertEquals(total, num(last, "nextOffset"))

        val seen = StringBuilder()
        var offset = 0L
        var windows = 0
        while (true) {
            val w = json(read(root, path, offset, 1024L * 1024L))
            windows++
            assertEquals("window $windows offset", offset, num(w, "offset"))
            val text = str(w, "text")
            assertEquals("consumed must equal returned length", text.length.toLong(), num(w, "consumedBytes"))
            assertEquals(offset + text.length.toLong(), num(w, "nextOffset"))
            seen.append(text)
            if (flag(w, "eof")) break
            offset = num(w, "nextOffset")
            assertTrue("no infinite loop", windows <= 10)
        }
        assertEquals("exactly ten windows for a 10 MiB file", 10, windows)
        assertEquals("no byte skipped or re-read across the 10 windows", total, seen.length.toLong())
        // The pattern '0123456789' repeats every 10 bytes; the 1 MiB boundary (index 1048576,
        // 1048576 % 10 == 6) must read back exactly the source byte, proving alignment.
        assertEquals('5', seen[1024 * 1024 - 1])
        assertEquals('6', seen[1024 * 1024])
    }

    // ── encoding boundary ───────────────────────────────────────────────────────────────

    @Test
    fun aMultiByteSequenceStraddlingTheWindowEndIsTrimmedAndNextOffsetLandsOnTheBoundary() {
        val root = root()
        val path = write(root, "work/utf8.txt", "中".repeat(10).toByteArray()) // 中 = 3 bytes, 30 total
        val out = json(read(root, path, 0, 7)) // 中 中 + 1 partial byte
        assertEquals("中中", str(out, "text"))
        assertEquals(6L, num(out, "consumedBytes"))
        assertEquals("nextOffset must resume at the clean boundary, not the raw window end", 6L, num(out, "nextOffset"))
        assertEquals(7L, num(out, "windowLength"))
        assertEquals(false, flag(out, "eof"))
    }

    @Test
    fun straddledWindowsConcatenateToTheExactOriginalContent() {
        val root = root()
        val content = "a" + "中".repeat(20) // 1 + 60 = 61 bytes
        val path = write(root, "work/utf8b.txt", content.toByteArray())
        val sb = StringBuilder()
        var offset = 0L
        var guard = 0
        while (true) {
            val out = json(read(root, path, offset, 4)) // 4 bytes: always splits a 中
            sb.append(str(out, "text"))
            assertEquals("windows must advance monotonically", offset, num(out, "offset"))
            offset = num(out, "nextOffset")
            if (flag(out, "eof")) break
            guard++
            assertTrue("no infinite loop paging a straddled file", guard < 200)
        }
        assertEquals("pacing must reconstruct the file exactly", content, sb.toString())
    }

    // ── stable EOF + binary + failures ─────────────────────────────────────────────────

    @Test
    fun anOffsetAtOrPastTheEndIsAStableTerminalWindowNotAnError() {
        val root = root()
        val path = write(root, "work/eof.txt", "abc".toByteArray())
        val atEnd = json(read(root, path, 3, 100))
        assertEquals(0L, num(atEnd, "windowLength"))
        assertEquals("", str(atEnd, "text"))
        assertEquals(3L, num(atEnd, "nextOffset"))
        assertEquals(true, flag(atEnd, "eof"))
        val pastEnd = json(read(root, path, 100, 100))
        assertEquals(true, flag(pastEnd, "eof"))
        assertEquals(0L, num(pastEnd, "windowLength"))
    }

    @Test
    fun binaryContentReturnsBase64NotText() {
        val root = root()
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3)
        val path = write(root, "work/img.png", bytes)
        val out = json(read(root, path, 0, 100))
        assertEquals("BINARY", str(out, "encoding"))
        assertNull(out["text"])
        assertEquals(
            bytes.toList(),
            java.util.Base64
                .getDecoder()
                .decode(str(out, "base64"))
                .toList(),
        )
    }

    @Test
    fun aMissingFileFailsClosedWithAStableMessage() {
        val root = root()
        val path = FileScopePath("ws", "work/nope.txt")
        val r = read(root, path, 0, 100)
        val failed = r as? ToolExecutorResult.Failed ?: error("expected Failed, got $r")
        assertTrue(failed.detail.contains("not found"))
        assertFalse(failed.sideEffectFree)
    }

    @Test
    fun anInvalidModelReferenceFailsClosed() {
        val root = root()
        val args = buildJsonObject { put("path", JsonPrimitive("/abs/path")) }
        val r = ReadTool.executor(store(root)).execute(call(args))
        val failed = r as? ToolExecutorResult.Failed ?: error("expected Failed, got $r")
        assertTrue(failed.detail.contains("invalid"))
    }

    @Test
    fun aCancelSignalBeforeExecutionReportsCancelled() {
        val root = root()
        val path = write(root, "work/cancel.txt", "abc".toByteArray())
        val args = buildJsonObject { put("path", JsonPrimitive(path.toModelReference())) }
        val alreadyCancelled =
            object : CancelSignal {
                override fun isCancelled(): Boolean = true
            }
        val r = ReadTool.executor(store(root)).execute(call(args, alreadyCancelled))
        assertEquals(ToolExecutorResult.Cancelled, r)
    }

    @Test
    fun theOutputPassesTheRegisteredOutputSchema() {
        val root = root()
        val path = write(root, "work/schema.txt", "ok".toByteArray())
        val out = json(read(root, path, 0, 100))
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(ReadTool.descriptor().outputSchema, out))
    }
}
