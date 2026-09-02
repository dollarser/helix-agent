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
import java.time.Instant

/**
 * HXA-043 (`:tools:files:test`): `files.copy` / `files.move` / `files.delete` — the explicit
 * conflict policy (an existing destination is refused without `overwrite`; a directory
 * destination is always refused), cross-scope operations, region admission (only
 * `input/`/`work/`/`output/`), delete-into-trash with a returned trash reference, sanitized
 * failure messages (no real path leaks, doc 10), cancel short-circuit, and output-schema
 * conformance.
 */
class FilesMutateToolsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun freshScope(name: String): Path {
        val p = tmp.newFolder(name).toPath()
        WorkspaceArtifactStore(ScopeRootResolver { _ -> p }).ensureLayout("ws")
        return p
    }

    private fun store(root: Path): WorkspaceArtifactStore = WorkspaceArtifactStore(ScopeRootResolver { _ -> root })

    private fun twoScopeStore(
        a: Path,
        b: Path,
    ): WorkspaceArtifactStore = WorkspaceArtifactStore(ScopeRootResolver { id -> if (id == "ws") a else b })

    private val noCancel =
        object : CancelSignal {
            override fun isCancelled(): Boolean = false
        }

    private fun call(
        toolName: String,
        args: JsonObject,
        cancel: CancelSignal = noCancel,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "call-1",
            toolName = toolName,
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel = cancel,
        )

    private fun copyExec(
        s: WorkspaceArtifactStore,
        args: JsonObject,
        cancel: CancelSignal = noCancel,
    ): ToolExecutorResult = FilesCopyTool.executor(s).execute(call("files.copy", args, cancel))

    private fun moveExec(
        s: WorkspaceArtifactStore,
        args: JsonObject,
    ): ToolExecutorResult = FilesMoveTool.executor(s).execute(call("files.move", args))

    private fun deleteExec(
        s: WorkspaceArtifactStore,
        args: JsonObject,
        cancel: CancelSignal = noCancel,
    ): ToolExecutorResult = FilesDeleteTool.executor(s).execute(call("files.delete", args, cancel))

    private fun write(
        root: Path,
        rel: String,
        content: String,
    ): Path {
        val p = root.resolve(rel)
        p.parent?.let { Files.createDirectories(it) }
        Files.write(p, content.toByteArray())
        return p
    }

    private fun json(result: ToolExecutorResult): JsonObject =
        (result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")).output.jsonObject

    private fun failed(result: ToolExecutorResult): String =
        (result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")).detail

    private fun copyArgs(
        source: String,
        destination: String,
        overwrite: Boolean? = null,
    ): JsonObject =
        buildJsonObject {
            put("source", JsonPrimitive(source))
            put("destination", JsonPrimitive(destination))
            overwrite?.let { put("overwrite", JsonPrimitive(it)) }
        }

    private fun deleteArgs(path: String): JsonObject = buildJsonObject { put("path", JsonPrimitive(path)) }

    private val srcRef = "scope:ws:work/a.txt"

    private val dstRef = "scope:ws:output/b.txt"

    // ── descriptor contract ─────────────────────────────────────────────────────────────

    @Test
    fun descriptorsAreValidL2BuiltInMutations() {
        val copy = FilesCopyTool.descriptor()
        val move = FilesMoveTool.descriptor()
        val delete = FilesDeleteTool.descriptor()
        assertEquals("files.copy", copy.name.value)
        assertEquals("files.move", move.name.value)
        assertEquals("files.delete", delete.name.value)
        listOf(copy, move, delete).forEach { d ->
            assertEquals(1, d.version.value)
            assertEquals(ToolOperationClass.LOCAL_MUTATION, d.operationClass)
            assertEquals(RiskLevel.L2, d.baseRisk)
            assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
            assertTrue(ToolSchema.check(d.inputSchema).isEmpty())
            assertTrue(ToolSchema.check(d.outputSchema).isEmpty())
        }
        assertEquals(Idempotency.NON_IDEMPOTENT, copy.idempotency)
        assertEquals(Idempotency.NON_IDEMPOTENT, move.idempotency)
        assertEquals(Idempotency.IDEMPOTENT, delete.idempotency)
    }

    // ── files.copy ──────────────────────────────────────────────────────────────────────

    @Test
    fun copyCompletesAndPassesTheOutputSchema() {
        val root = freshScope("c1")
        write(root, "work/a.txt", "payload")
        val out = json(copyExec(store(root), copyArgs(srcRef, dstRef)))
        assertEquals("scope:ws:work/a.txt", out.getValue("source").jsonPrimitive.content)
        assertEquals("scope:ws:output/b.txt", out.getValue("destination").jsonPrimitive.content)
        assertEquals(
            7L,
            out
                .getValue("sizeBytes")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            false,
            out
                .getValue("overwritten")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals("payload", String(Files.readAllBytes(root.resolve("output/b.txt"))))
        assertEquals("the source survives", "payload", String(Files.readAllBytes(root.resolve("work/a.txt"))))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(FilesCopyTool.descriptor().outputSchema, out),
        )
    }

    @Test
    fun copyConflictFailsClosedWithAStableMessageAndKeepsTheDestination() {
        val root = freshScope("c2")
        write(root, "work/a.txt", "src")
        write(root, "output/b.txt", "keep")
        val detail = failed(copyExec(store(root), copyArgs(srcRef, dstRef)))
        assertTrue(detail.contains("already exists"))
        assertEquals("keep", String(Files.readAllBytes(root.resolve("output/b.txt"))))
        val out = json(copyExec(store(root), copyArgs(srcRef, dstRef, overwrite = true)))
        assertEquals(
            true,
            out
                .getValue("overwritten")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    @Test
    fun copyCrossScopePublishesIntoTheOtherScope() {
        val a = freshScope("c3a")
        val b = freshScope("c3b")
        val s = twoScopeStore(a, b)
        write(a, "work/a.txt", "cross")
        val out = json(copyExec(s, copyArgs(srcRef, "scope:other:output/c.txt")))
        assertEquals("cross", String(Files.readAllBytes(b.resolve("output/c.txt"))))
        assertTrue(Files.exists(a.resolve("work/a.txt")))
        assertEquals("scope:other:output/c.txt", out.getValue("destination").jsonPrimitive.content)
    }

    @Test
    fun copyRefusesASourceOutsideUserRegions() {
        val root = freshScope("c4")
        val detail = failed(copyExec(store(root), copyArgs("scope:ws:.helix/metadata.json", "scope:ws:output/x.txt")))
        assertTrue(detail.contains("input/, work/ or output/"))
        // doc 10: the stable message must not carry the real scope-root path.
        assertFalse(detail.contains(root.toString()))
        assertFalse(Files.exists(root.resolve("output/x.txt")))
    }

    @Test
    fun copyRefusesADirectoryDestination() {
        val root = freshScope("c5")
        write(root, "work/a.txt", "src")
        Files.createDirectories(root.resolve("output/dir"))
        val detail = failed(copyExec(store(root), copyArgs(srcRef, "scope:ws:output/dir", overwrite = true)))
        assertTrue(detail.contains("directory"))
        assertFalse(detail.contains(root.toString()))
        assertTrue(Files.isDirectory(root.resolve("output/dir")))
    }

    // ── files.move ──────────────────────────────────────────────────────────────────────

    @Test
    fun moveCompletesAndRemovesTheSource() {
        val root = freshScope("m1")
        write(root, "work/a.txt", "payload")
        val out = json(moveExec(store(root), copyArgs(srcRef, dstRef)))
        assertFalse(Files.exists(root.resolve("work/a.txt")))
        assertEquals("payload", String(Files.readAllBytes(root.resolve("output/b.txt"))))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(FilesMoveTool.descriptor().outputSchema, out),
        )
    }

    @Test
    fun moveConflictFailsClosedAndKeepsTheSource() {
        val root = freshScope("m2")
        write(root, "work/a.txt", "src")
        write(root, "output/b.txt", "keep")
        val detail = failed(moveExec(store(root), copyArgs(srcRef, dstRef)))
        assertTrue(detail.contains("already exists"))
        assertTrue("a refused move never deletes the source", Files.exists(root.resolve("work/a.txt")))
    }

    @Test
    fun moveCrossScopeRemovesTheSourceOnlyAfterPublish() {
        val a = freshScope("m3a")
        val b = freshScope("m3b")
        val s = twoScopeStore(a, b)
        write(a, "work/a.txt", "cross")
        moveExec(s, copyArgs(srcRef, "scope:other:output/c.txt"))
        assertEquals("cross", String(Files.readAllBytes(b.resolve("output/c.txt"))))
        assertFalse(Files.exists(a.resolve("work/a.txt")))
    }

    // ── files.delete ────────────────────────────────────────────────────────────────────

    @Test
    fun deleteMovesTheFileToTrashAndReturnsItsReference() {
        val root = freshScope("d1")
        write(root, "work/a.txt", "payload")
        val out = json(deleteExec(store(root), deleteArgs(srcRef)))
        val trashRef = out.getValue("trashRef").jsonPrimitive.content
        assertTrue("the trash reference is model-safe", trashRef.startsWith("scope:ws:.helix/trash/"))
        assertFalse("the original path is gone", Files.exists(root.resolve("work/a.txt")))
        val entry = root.resolve(trashRef.removePrefix("scope:ws:"))
        assertEquals("the file is parked in the trash with its bytes", "payload", String(Files.readAllBytes(entry)))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(FilesDeleteTool.descriptor().outputSchema, out),
        )
    }

    @Test
    fun deleteOfAMissingOrNonFileTargetFailsClosed() {
        val root = freshScope("d2")
        Files.createDirectories(root.resolve("work/dir"))
        val missing = failed(deleteExec(store(root), deleteArgs("scope:ws:work/nope.txt")))
        assertTrue(missing.contains("not found"))
        val detail = failed(deleteExec(store(root), deleteArgs("scope:ws:work/dir")))
        assertTrue(detail.contains("not a file"))
        assertTrue(Files.isDirectory(root.resolve("work/dir")))
    }

    @Test
    fun deleteRefusesPathsOutsideUserRegions() {
        val root = freshScope("d3")
        val detail = failed(deleteExec(store(root), deleteArgs("scope:ws:.helix/metadata.json")))
        assertTrue(detail.contains("input/, work/ or output/"))
        assertTrue("internal metadata is never deleted", Files.exists(root.resolve(".helix/metadata.json")))
    }

    // ── invalid args + cancel + quota ───────────────────────────────────────────────────

    @Test
    fun invalidArgumentsFailClosed() {
        val root = freshScope("x1")
        val missingDest = buildJsonObject { put("source", JsonPrimitive("scope:ws:work/a.txt")) }
        assertTrue(failed(copyExec(store(root), missingDest)).contains("invalid"))
        val badRef = copyArgs("/abs", dstRef)
        assertTrue(failed(copyExec(store(root), badRef)).contains("invalid"))
        assertTrue(failed(deleteExec(store(root), buildJsonObject {})).contains("invalid"))
    }

    @Test
    fun aCancelSignalBeforeExecutionReportsCancelled() {
        val root = freshScope("x2")
        val cancelled =
            object : CancelSignal {
                override fun isCancelled(): Boolean = true
            }
        assertEquals(ToolExecutorResult.Cancelled, copyExec(store(root), copyArgs(srcRef, dstRef), cancelled))
        assertEquals(ToolExecutorResult.Cancelled, deleteExec(store(root), deleteArgs(srcRef), cancelled))
    }

    @Test
    fun aQuotaExceedingCopyFailsClosedAndWritesNothing() {
        val a = freshScope("q1a")
        val b = tmp.newFolder("q1b").toPath()
        val tiny =
            WorkspaceArtifactStore(ScopeRootResolver { id -> if (id == "ws") a else b }, WorkspaceQuotaPolicy(16L))
        tiny.ensureLayout("other")
        write(a, "work/big.txt", "x".repeat(32))
        val r = copyExec(tiny, copyArgs("scope:ws:work/big.txt", "scope:other:output/big.txt"))
        assertTrue(failed(r).contains("quota"))
        assertFalse(Files.exists(b.resolve("output/big.txt")))
    }
}
