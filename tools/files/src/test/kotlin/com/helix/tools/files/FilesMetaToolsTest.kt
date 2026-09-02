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
import kotlinx.serialization.json.jsonArray
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
 * HXA-042 (verification matrix row `:tools:files:test`): the four `files.*` meta tools
 * (stat/list/search/mkdir). Covers the descriptor contract of each, the normal and
 * fail-closed paths, the bounded-paging/truncation semantics, the region boundary for
 * mkdir, cancel, invalid arguments, and output schema conformance.
 */
class FilesMetaToolsTest {
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

    private fun run(
        root: Path,
        name: String,
        executor: (WorkspaceArtifactStore) -> com.helix.tools.framework.ToolExecutor,
        args: JsonObject,
        cancel: CancelSignal = noCancel,
    ): ToolExecutorResult = executor(store(root)).execute(call(name, args, cancel))

    private fun json(result: ToolExecutorResult): JsonObject {
        val completed = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")
        return completed.output.jsonObject
    }

    private fun failed(result: ToolExecutorResult): String {
        val failed = result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")
        return failed.detail
    }

    private fun pathArgs(path: FileScopePath): JsonObject =
        buildJsonObject {
            put("path", JsonPrimitive(path.toModelReference()))
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

    private fun assertDescriptor(
        d: com.helix.tools.framework.ToolDescriptor,
        name: String,
        op: ToolOperationClass,
        risk: RiskLevel,
    ) {
        assertEquals(name, d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(op, d.operationClass)
        assertEquals(risk, d.baseRisk)
        assertEquals(Idempotency.IDEMPOTENT, d.idempotency)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, d.executionTarget)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        assertTrue(ToolSchema.check(d.inputSchema).isEmpty())
        assertTrue(ToolSchema.check(d.outputSchema).isEmpty())
    }

    // ── files.stat ──────────────────────────────────────────────────────────────────────

    @Test
    fun statDescriptorIsAValidReadOnlyBuiltIn() {
        assertDescriptor(FilesStatTool.descriptor(), "files.stat", ToolOperationClass.READ_ONLY, RiskLevel.L1)
    }

    @Test
    fun statReportsARegularFile() {
        val root = root()
        val path = write(root, "work/a.txt", "hello".toByteArray())
        val out = json(run(root, "files.stat", FilesStatTool::executor, pathArgs(path)))
        assertEquals(path.toModelReference(), str(out, "path"))
        assertEquals(true, flag(out, "exists"))
        assertEquals(5L, num(out, "sizeBytes"))
        assertEquals(false, flag(out, "isDirectory"))
        assertEquals(true, flag(out, "isRegularFile"))
        assertEquals(false, flag(out, "isSymlink"))
    }

    @Test
    fun statReportsADirectory() {
        val root = root()
        val out = json(run(root, "files.stat", FilesStatTool::executor, pathArgs(FileScopePath("ws", "work"))))
        assertEquals(true, flag(out, "exists"))
        assertEquals(true, flag(out, "isDirectory"))
        assertEquals(false, flag(out, "isRegularFile"))
    }

    @Test
    fun statOfAMissingPathIsAResultNotAnError() {
        val root = root()
        val out = json(run(root, "files.stat", FilesStatTool::executor, pathArgs(FileScopePath("ws", "work/nope.txt"))))
        assertEquals(false, flag(out, "exists"))
        assertEquals(-1L, num(out, "sizeBytes"))
    }

    @Test
    fun statOfAnInvalidReferenceFailsClosed() {
        val root = root()
        val r = run(root, "files.stat", FilesStatTool::executor, buildJsonObject { put("path", JsonPrimitive("/abs")) })
        assertTrue(failed(r).contains("invalid"))
    }

    // ── files.list ──────────────────────────────────────────────────────────────────────

    @Test
    fun listDescriptorIsAValidReadOnlyBuiltIn() {
        assertDescriptor(FilesListTool.descriptor(), "files.list", ToolOperationClass.READ_ONLY, RiskLevel.L1)
    }

    @Test
    fun listReturnsSortedNames() {
        val root = root()
        write(root, "work/z.txt", "z".toByteArray())
        write(root, "work/a.txt", "a".toByteArray())
        write(root, "work/m.txt", "m".toByteArray())
        val out = json(run(root, "files.list", FilesListTool::executor, pathArgs(FileScopePath("ws", "work"))))
        val names = out.getValue("entries").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a.txt", "m.txt", "z.txt"), names)
        assertEquals(false, flag(out, "truncated"))
    }

    @Test
    fun listIsBoundedAndFlagsTruncation() {
        val root = root()
        repeat(5) { i -> write(root, "work/f$i.txt", "x".toByteArray()) }
        val args =
            buildJsonObject {
                put("path", JsonPrimitive(FileScopePath("ws", "work").toModelReference()))
                put("maxEntries", JsonPrimitive(3))
            }
        val out = json(run(root, "files.list", FilesListTool::executor, args))
        assertEquals(3, out.getValue("entries").jsonArray.size)
        assertEquals(true, flag(out, "truncated"))
    }

    @Test
    fun listOfAMissingPathOrAFileFailsClosed() {
        val root = root()
        val missing = run(root, "files.list", FilesListTool::executor, pathArgs(FileScopePath("ws", "work/nope")))
        assertTrue(failed(missing).contains("not a directory"))
        val file = write(root, "work/afile.txt", "x".toByteArray())
        val onFile = run(root, "files.list", FilesListTool::executor, pathArgs(file))
        assertTrue(failed(onFile).contains("not a directory"))
    }

    // ── files.search ────────────────────────────────────────────────────────────────────

    @Test
    fun searchDescriptorIsAValidReadOnlyBuiltIn() {
        assertDescriptor(FilesSearchTool.descriptor(), "files.search", ToolOperationClass.READ_ONLY, RiskLevel.L1)
    }

    @Test
    fun searchFindsCaseInsensitiveNameMatchesRecursively() {
        val root = root()
        write(root, "work/note.txt", "x".toByteArray())
        write(root, "output/report.TXT", "x".toByteArray())
        write(root, "work/other.md", "x".toByteArray())
        val args =
            buildJsonObject {
                put("path", JsonPrimitive(FileScopePath("ws", "").toModelReference()))
                put("needle", JsonPrimitive("note"))
            }
        val out = json(run(root, "files.search", FilesSearchTool::executor, args))
        val matches = out.getValue("matches").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(FileScopePath("ws", "work/note.txt").toModelReference()), matches)
        assertEquals(false, flag(out, "truncated"))
    }

    @Test
    fun searchIsBoundedByMaxResultsAndFlagsTruncation() {
        val root = root()
        repeat(4) { i -> write(root, "work/hit$i.txt", "x".toByteArray()) }
        val args =
            buildJsonObject {
                put("path", JsonPrimitive(FileScopePath("ws", "").toModelReference()))
                put("needle", JsonPrimitive("hit"))
                put("maxResults", JsonPrimitive(2))
            }
        val out = json(run(root, "files.search", FilesSearchTool::executor, args))
        assertEquals(2, out.getValue("matches").jsonArray.size)
        assertEquals(true, flag(out, "truncated"))
    }

    @Test
    fun searchOfAMissingDirectoryOrBlankNeedleFailsClosed() {
        val root = root()
        val missing =
            run(
                root,
                "files.search",
                FilesSearchTool::executor,
                buildJsonObject {
                    put("path", JsonPrimitive(FileScopePath("ws", "work/nope").toModelReference()))
                    put("needle", JsonPrimitive("x"))
                },
            )
        assertTrue(failed(missing).contains("not a directory"))
        val blank =
            run(
                root,
                "files.search",
                FilesSearchTool::executor,
                buildJsonObject {
                    put("path", JsonPrimitive(FileScopePath("ws", "").toModelReference()))
                    put("needle", JsonPrimitive("  "))
                },
            )
        assertTrue(failed(blank).contains("invalid"))
    }

    // ── files.mkdir ─────────────────────────────────────────────────────────────────────

    @Test
    fun mkdirDescriptorIsAValidMutationBuiltIn() {
        assertDescriptor(FilesMkdirTool.descriptor(), "files.mkdir", ToolOperationClass.LOCAL_MUTATION, RiskLevel.L2)
    }

    @Test
    fun mkdirCreatesMissingParents() {
        val root = root()
        val path = FileScopePath("ws", "output/sub/dir")
        val out = json(run(root, "files.mkdir", FilesMkdirTool::executor, pathArgs(path)))
        assertEquals(path.toModelReference(), str(out, "path"))
        assertEquals(true, flag(out, "created"))
        assertTrue(Files.isDirectory(root.resolve("output/sub/dir")))
    }

    @Test
    fun mkdirRefusesAnExistingTarget() {
        val root = root()
        val path = FileScopePath("ws", "work/existing")
        Files.createDirectories(root.resolve("work/existing"))
        val detail = failed(run(root, "files.mkdir", FilesMkdirTool::executor, pathArgs(path)))
        assertTrue(detail.contains("already exists"))
    }

    @Test
    fun mkdirRefusesHelixInternalsAndOutOfRegionPaths() {
        val root = root()
        val helix = FileScopePath("ws", ".helix/newdir")
        assertTrue(
            failed(
                run(root, "files.mkdir", FilesMkdirTool::executor, pathArgs(helix)),
            ).contains("input/, work/ or output/"),
        )
        assertFalse(Files.exists(root.resolve(".helix/newdir")))
    }

    // ── cancel + output schemas ─────────────────────────────────────────────────────────

    @Test
    fun aCancelSignalBeforeExecutionReportsCancelledOnEveryTool() {
        val root = root()
        val alreadyCancelled =
            object : CancelSignal {
                override fun isCancelled(): Boolean = true
            }
        val path = FileScopePath("ws", "work")
        val args = pathArgs(path)
        assertEquals(
            ToolExecutorResult.Cancelled,
            run(root, "files.stat", FilesStatTool::executor, args, alreadyCancelled),
        )
        assertEquals(
            ToolExecutorResult.Cancelled,
            run(root, "files.list", FilesListTool::executor, args, alreadyCancelled),
        )
        val searchArgs =
            buildJsonObject {
                put("path", JsonPrimitive(path.toModelReference()))
                put("needle", JsonPrimitive("x"))
            }
        assertEquals(
            ToolExecutorResult.Cancelled,
            run(root, "files.search", FilesSearchTool::executor, searchArgs, alreadyCancelled),
        )
        assertEquals(
            ToolExecutorResult.Cancelled,
            run(root, "files.mkdir", FilesMkdirTool::executor, args, alreadyCancelled),
        )
    }

    @Test
    fun theOutputsPassTheirRegisteredSchemas() {
        val root = root()
        val file = write(root, "work/a.txt", "hello".toByteArray())
        val statOut = json(run(root, "files.stat", FilesStatTool::executor, pathArgs(file)))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(FilesStatTool.descriptor().outputSchema, statOut),
        )
        val listOut = json(run(root, "files.list", FilesListTool::executor, pathArgs(FileScopePath("ws", "work"))))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(FilesListTool.descriptor().outputSchema, listOut),
        )
        val searchArgs =
            buildJsonObject {
                put("path", JsonPrimitive(FileScopePath("ws", "").toModelReference()))
                put("needle", JsonPrimitive("a"))
            }
        val searchOut = json(run(root, "files.search", FilesSearchTool::executor, searchArgs))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(FilesSearchTool.descriptor().outputSchema, searchOut),
        )
        val mkdirOut =
            json(run(root, "files.mkdir", FilesMkdirTool::executor, pathArgs(FileScopePath("ws", "work/newdir"))))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(FilesMkdirTool.descriptor().outputSchema, mkdirOut),
        )
    }
}
