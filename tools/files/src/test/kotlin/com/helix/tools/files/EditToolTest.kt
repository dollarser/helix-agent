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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * HXA-042 (verification matrix row `:tools:files:test`): the `edit` tool. Covers:
 * - descriptor contract (L2 LOCAL_MUTATION idempotent built-in, both schemas valid);
 * - the 前置-hash guard: a stale expectedSha256 is refused WITHOUT mutating the file;
 * - fail-closed uniqueness: a span that does not occur exactly once is refused (single mode);
 * - replaceAll replaces every occurrence;
 * - binary / non-UTF-8 files are refused rather than mangled, INCLUDING a bad byte past the
 *   8 KiB probe window (the whole content is strictly decoded, not just the prefix);
 * - region and model-reference boundary: `.helix/` internals and bad references fail closed;
 * - cancel and invalid arguments fail closed; the output validates against the registered schema.
 */
class EditToolTest {
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
            toolName = "edit",
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel = cancel,
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun shaOf(
        root: Path,
        path: FileScopePath,
    ): String = sha256(Files.readAllBytes(root.resolve(path.relativePath)))

    private fun edit(
        root: Path,
        path: FileScopePath,
        oldText: String,
        newText: String,
        expectedSha256: String,
        replaceAll: Boolean? = null,
    ): ToolExecutorResult {
        val args =
            buildJsonObject {
                put("path", JsonPrimitive(path.toModelReference()))
                put("oldText", JsonPrimitive(oldText))
                put("newText", JsonPrimitive(newText))
                put("expectedSha256", JsonPrimitive(expectedSha256))
                replaceAll?.let { put("replaceAll", JsonPrimitive(it)) }
            }
        return EditTool.executor(store(root)).execute(call(args))
    }

    private fun json(result: ToolExecutorResult): JsonObject {
        val completed = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")
        return completed.output.jsonObject
    }

    private fun failed(result: ToolExecutorResult): String {
        val failed = result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")
        return failed.detail
    }

    private fun num(
        out: JsonObject,
        key: String,
    ): Long =
        out
            .getValue(key)
            .jsonPrimitive.content
            .toLong()

    // ── descriptor contract ─────────────────────────────────────────────────────────────

    @Test
    fun descriptorIsAValidRegisterableBuiltIn() {
        val d = EditTool.descriptor()
        assertEquals("edit", d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, d.operationClass)
        assertEquals(RiskLevel.L2, d.baseRisk)
        assertEquals(Idempotency.IDEMPOTENT, d.idempotency)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, d.executionTarget)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        assertTrue(ToolSchema.check(d.inputSchema).isEmpty())
        assertTrue(ToolSchema.check(d.outputSchema).isEmpty())
    }

    // ── happy path + 前置 hash guard ───────────────────────────────────────────────────

    @Test
    fun aSingleUnambiguousSpanIsReplacedAndTheNewHashIsReported() {
        val root = root()
        val path = write(root, "work/a.txt", "alpha beta gamma".toByteArray())
        val out = json(edit(root, path, "beta", "BETA", shaOf(root, path)))
        assertEquals(1L, num(out, "replacements"))
        assertEquals("alpha BETA gamma", Files.readString(root.resolve(path.relativePath)))
        assertEquals(sha256("alpha BETA gamma".toByteArray()), out.getValue("sha256").jsonPrimitive.content)
        assertEquals("alpha BETA gamma".toByteArray().size.toLong(), num(out, "sizeBytes"))
    }

    @Test
    fun aStaleExpectedHashIsRefusedAndTheFileIsLeftUnchanged() {
        val root = root()
        val path = write(root, "work/stale.txt", "hello world".toByteArray())
        val stale = sha256("something else entirely".toByteArray())
        val detail = failed(edit(root, path, "world", "there", stale))
        assertTrue(detail.contains("hash mismatch"))
        assertEquals("hello world", Files.readString(root.resolve(path.relativePath)))
    }

    @Test
    fun aMissingFileFailsClosed() {
        val root = root()
        val path = FileScopePath("ws", "work/nope.txt")
        val detail = failed(edit(root, path, "a", "b", sha256("a".toByteArray())))
        assertTrue(detail.contains("not found"))
    }

    @Test
    fun aSpanThatDoesNotOccurIsRefused() {
        val root = root()
        val path = write(root, "work/none.txt", "abc".toByteArray())
        val detail = failed(edit(root, path, "zzz", "y", shaOf(root, path)))
        assertTrue(detail.contains("exactly once"))
        assertEquals("abc", Files.readString(root.resolve(path.relativePath)))
    }

    @Test
    fun anAmbiguousSpanIsRefusedUnlessReplaceAllIsSet() {
        val root = root()
        val path = write(root, "work/dup.txt", "x x x".toByteArray())
        val h = shaOf(root, path)
        val detail = failed(edit(root, path, "x", "y", h))
        assertTrue(detail.contains("found 3"))
        // replaceAll then succeeds and reports the count.
        val out = json(edit(root, path, "x", "y", h, replaceAll = true))
        assertEquals(3L, num(out, "replacements"))
        assertEquals("y y y", Files.readString(root.resolve(path.relativePath)))
    }

    @Test
    fun replaceAllWithZeroMatchesIsRefused() {
        val root = root()
        val path = write(root, "work/zero.txt", "abc".toByteArray())
        val detail = failed(edit(root, path, "zzz", "y", shaOf(root, path), replaceAll = true))
        assertTrue(detail.contains("not found"))
        assertEquals("abc", Files.readString(root.resolve(path.relativePath)))
    }

    // ── content boundary ────────────────────────────────────────────────────────────────

    @Test
    fun aBinaryFileIsRefusedRatherThanMangled() {
        val root = root()
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val path = write(root, "work/bin.dat", bytes)
        val detail = failed(edit(root, path, String(bytes, Charsets.ISO_8859_1), "x", shaOf(root, path)))
        assertTrue(detail.contains("not UTF-8"))
        assertEquals(bytes.toList(), Files.readAllBytes(root.resolve(path.relativePath)).toList())
    }

    @Test
    fun anInvalidUtf8ByteBeyondTheProbeWindowIsRefusedAndTheBytesStayUnchanged() {
        val root = root()
        // 9000 clean ASCII bytes keep the 8 KiB probe window valid UTF-8; the lone 0x80 at
        // byte 9000 (past the window) is an invalid lead byte. The edit must be refused and the
        // byte must NOT be replaced by U+FFFD (EF BF BD) and republished.
        val bytes = "a".repeat(9000).toByteArray() + byteArrayOf(0x80.toByte()) + "end".toByteArray()
        val path = write(root, "work/tail.txt", bytes)
        val detail = failed(edit(root, path, "end", "END", shaOf(root, path)))
        assertTrue(detail.contains("not UTF-8"))
        assertEquals(bytes.toList(), Files.readAllBytes(root.resolve(path.relativePath)).toList())
    }

    @Test
    fun aNulByteBeyondTheProbeWindowIsRefusedAndTheBytesStayUnchanged() {
        val root = root()
        // A NUL in the tail classifies BINARY by the same rule the probe applies to prefixes, so
        // the whole-file gate must refuse it regardless of position.
        val bytes = "a".repeat(9000).toByteArray() + byteArrayOf(0) + "end".toByteArray()
        val path = write(root, "work/nul-tail.txt", bytes)
        val detail = failed(edit(root, path, "end", "END", shaOf(root, path)))
        assertTrue(detail.contains("not UTF-8"))
        assertEquals(bytes.toList(), Files.readAllBytes(root.resolve(path.relativePath)).toList())
    }

    @Test
    fun anEmptyFilePassesTheEncodingCheckButTheMatchFailsClosed() {
        val root = root()
        val path = write(root, "work/empty.txt", ByteArray(0))
        val detail = failed(edit(root, path, "a", "b", shaOf(root, path)))
        assertTrue(detail.contains("exactly once"))
    }

    // ── region + reference boundary ─────────────────────────────────────────────────────

    @Test
    fun anEditIntoHelixInternalsIsRefused() {
        val root = root()
        val meta = root.resolve(".helix/metadata.json")
        Files.write(meta, "old".toByteArray())
        val path = FileScopePath("ws", ".helix/metadata.json")
        val detail = failed(edit(root, path, "old", "new", shaOf(root, path)))
        assertTrue(detail.contains("input/, work/ or output/"))
        assertEquals("old", Files.readString(meta))
    }

    @Test
    fun anInvalidModelReferenceFailsClosed() {
        val root = root()
        val args =
            buildJsonObject {
                put("path", JsonPrimitive("/abs/path"))
                put("oldText", JsonPrimitive("a"))
                put("newText", JsonPrimitive("b"))
                put("expectedSha256", JsonPrimitive(sha256("a".toByteArray())))
            }
        val detail = failed(EditTool.executor(store(root)).execute(call(args)))
        assertTrue(detail.contains("invalid"))
    }

    @Test
    fun aMalformedHashIsRefusedAsInvalidArguments() {
        val root = root()
        val path = write(root, "work/hash.txt", "abc".toByteArray())
        val detail = failed(edit(root, path, "a", "b", "not-a-hash"))
        assertTrue(detail.contains("invalid"))
    }

    @Test
    fun aCancelSignalBeforeExecutionReportsCancelled() {
        val root = root()
        val path = write(root, "work/cancel.txt", "abc".toByteArray())
        val args =
            buildJsonObject {
                put("path", JsonPrimitive(path.toModelReference()))
                put("oldText", JsonPrimitive("a"))
                put("newText", JsonPrimitive("b"))
                put("expectedSha256", JsonPrimitive(shaOf(root, path)))
            }
        val alreadyCancelled =
            object : CancelSignal {
                override fun isCancelled(): Boolean = true
            }
        val r = EditTool.executor(store(root)).execute(call(args, alreadyCancelled))
        assertEquals(ToolExecutorResult.Cancelled, r)
    }

    @Test
    fun theOutputPassesTheRegisteredOutputSchema() {
        val root = root()
        val path = write(root, "work/schema.txt", "ok".toByteArray())
        val out = json(edit(root, path, "ok", "fine", shaOf(root, path)))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(EditTool.descriptor().outputSchema, out),
        )
    }
}
