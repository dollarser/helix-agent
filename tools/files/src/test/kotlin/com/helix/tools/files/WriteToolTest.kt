package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceQuotaPolicy
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * HXA-042 (`:tools:files:test`): the `write` tool — atomic UTF-8 publish with an EXPLICIT,
 * fail-closed overwrite policy and a 前置 hash guard. Covers the descriptor contract, a clean
 * write, overwrite refusal without the flag, guarded overwrite, hash-mismatch rejection, region
 * restriction (`.helix/` internals refused), directory-target refusal (no real path in the
 * failure, doc 10), the quota boundary, cancel, and output-schema conformance.
 */
class WriteToolTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun root(): Path {
        val p = tmp.newFolder("ws").toPath()
        WorkspaceArtifactStore(ScopeRootResolver { _ -> p }).ensureLayout("ws")
        return p
    }

    private fun store(root: Path): WorkspaceArtifactStore = WorkspaceArtifactStore(ScopeRootResolver { _ -> root })

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
            toolName = "write",
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel = cancel,
        )

    private fun write(
        root: Path,
        ref: String,
        content: String,
        overwrite: Boolean? = null,
        expectedSha256: String? = null,
    ): ToolExecutorResult {
        val args =
            buildJsonObject {
                put("path", JsonPrimitive(ref))
                put("content", JsonPrimitive(content))
                overwrite?.let { put("overwrite", JsonPrimitive(it)) }
                expectedSha256?.let { put("expectedSha256", JsonPrimitive(it)) }
            }
        return WriteTool.executor(store(root)).execute(call(args))
    }

    private fun json(result: ToolExecutorResult): JsonObject =
        (result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")).output.jsonObject

    private fun failed(result: ToolExecutorResult): String =
        (result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")).detail

    // ── descriptor contract ─────────────────────────────────────────────────────────────

    @Test
    fun descriptorIsAValidRegisterableBuiltIn() {
        val d = WriteTool.descriptor()
        assertEquals("write", d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, d.operationClass)
        assertEquals(RiskLevel.L2, d.baseRisk)
        assertEquals(Idempotency.IDEMPOTENT, d.idempotency)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        assertTrue(ToolSchema.check(d.inputSchema).isEmpty())
        assertTrue(ToolSchema.check(d.outputSchema).isEmpty())
    }

    // ── clean write + explicit overwrite ────────────────────────────────────────────────

    @Test
    fun writesNewFileAtomicallyAndReportsItsHash() {
        val root = root()
        val out = json(write(root, "scope:ws:work/a.txt", "hello"))
        assertEquals("scope:ws:work/a.txt", out.getValue("path").jsonPrimitive.content)
        assertEquals(
            5L,
            out
                .getValue("sizeBytes")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(sha("hello".toByteArray()), out.getValue("sha256").jsonPrimitive.content)
        assertEquals("UTF8", out.getValue("encoding").jsonPrimitive.content)
        assertEquals(
            false,
            out
                .getValue("overwritten")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals("hello", String(Files.readAllBytes(root.resolve("work/a.txt"))))
    }

    @Test
    fun anExistingFileIsNotOverwrittenWithoutTheFlag() {
        val root = root()
        write(root, "scope:ws:work/a.txt", "first")
        val r = write(root, "scope:ws:work/a.txt", "second")
        assertTrue(failed(r).contains("already exists"))
        assertEquals("the file is unchanged", "first", String(Files.readAllBytes(root.resolve("work/a.txt"))))
    }

    @Test
    fun overwriteTrueReplacesTheFile() {
        val root = root()
        write(root, "scope:ws:work/a.txt", "first")
        val out = json(write(root, "scope:ws:work/a.txt", "second", overwrite = true))
        assertEquals(
            true,
            out
                .getValue("overwritten")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals("second", String(Files.readAllBytes(root.resolve("work/a.txt"))))
    }

    @Test
    fun aGuardedOverwriteSucceedsWhenTheExpectedHashMatches() {
        val root = root()
        write(root, "scope:ws:work/a.txt", "v1")
        val out =
            json(
                write(root, "scope:ws:work/a.txt", "v2", overwrite = true, expectedSha256 = sha("v1".toByteArray())),
            )
        assertEquals(sha("v2".toByteArray()), out.getValue("sha256").jsonPrimitive.content)
    }

    @Test
    fun aGuardedOverwriteFailsClosedOnHashMismatchAndLeavesTheFile() {
        val root = root()
        write(root, "scope:ws:work/a.txt", "v1")
        val r =
            write(root, "scope:ws:work/a.txt", "v2", overwrite = true, expectedSha256 = "0".repeat(64))
        assertTrue(failed(r).contains("hash mismatch"))
        assertEquals("the file is intact", "v1", String(Files.readAllBytes(root.resolve("work/a.txt"))))
    }

    // ── region / containment boundary ───────────────────────────────────────────────────

    @Test
    fun writingIntoHelixInternalsIsRefused() {
        val root = root()
        val r = write(root, "scope:ws:.helix/trash/x", "x")
        assertTrue(failed(r).contains("input/, work/ or output/"))
    }

    @Test
    fun writingToTheScopeRootIsRefused() {
        val root = root()
        val r = write(root, "scope:ws:.", "x")
        assertTrue(failed(r).contains("input/, work/ or output/"))
    }

    @Test
    fun aDirectoryTargetIsRefusedWithAStableMessageAndNoRealPathLeak() {
        val root = root()
        Files.createDirectories(root.resolve("work/sub"))
        val detail = failed(write(root, "scope:ws:work/sub", "x"))
        assertTrue(detail.contains("directory"))
        // doc 10: the sanitized message must not carry the real scope-root path.
        assertFalse(detail.contains(root.toString()))
        // overwrite=true does not turn a directory into a writable target.
        assertTrue(failed(write(root, "scope:ws:work/sub", "x", overwrite = true)).contains("directory"))
        assertTrue("the directory must remain untouched", Files.isDirectory(root.resolve("work/sub")))
    }

    // ── quota boundary ──────────────────────────────────────────────────────────────────

    @Test
    fun aQuotaExceedingWriteFailsClosedAndWritesNothing() {
        val root = tmp.newFolder("q").toPath()
        val tiny = WorkspaceArtifactStore(ScopeRootResolver { _ -> root }, WorkspaceQuotaPolicy(16L))
        tiny.ensureLayout("ws")
        val args =
            buildJsonObject {
                put("path", JsonPrimitive("scope:ws:work/big.bin"))
                put("content", JsonPrimitive("x".repeat(32)))
            }
        val r = WriteTool.executor(tiny).execute(call(args))
        assertTrue(failed(r).contains("quota"))
        assertFalse(Files.exists(root.resolve("work/big.bin")))
    }

    // ── invalid args + cancel ───────────────────────────────────────────────────────────

    @Test
    fun anInvalidModelReferenceFailsClosed() {
        val root = root()
        val args =
            buildJsonObject {
                put("path", JsonPrimitive("/abs"))
                put("content", JsonPrimitive("x"))
            }
        val r = WriteTool.executor(store(root)).execute(call(args))
        assertTrue(failed(r).contains("invalid"))
    }

    @Test
    fun aMalformedExpectedSha256FailsClosed() {
        val root = root()
        val r = write(root, "scope:ws:work/a.txt", "x", overwrite = true, expectedSha256 = "zz")
        assertTrue(failed(r).contains("invalid"))
    }

    @Test
    fun aCancelSignalBeforeExecutionReportsCancelled() {
        val root = root()
        val args =
            buildJsonObject {
                put("path", JsonPrimitive("scope:ws:work/a.txt"))
                put("content", JsonPrimitive("x"))
            }
        val cancelled =
            object : CancelSignal {
                override fun isCancelled(): Boolean = true
            }
        assertEquals(ToolExecutorResult.Cancelled, WriteTool.executor(store(root)).execute(call(args, cancelled)))
    }

    @Test
    fun theOutputPassesTheRegisteredOutputSchema() {
        val root = root()
        val out = json(write(root, "scope:ws:work/a.txt", "ok"))
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(WriteTool.descriptor().outputSchema, out))
    }
}
