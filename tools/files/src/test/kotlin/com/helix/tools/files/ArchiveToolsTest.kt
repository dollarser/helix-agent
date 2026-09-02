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
 * HXA-047 (`:tools:files:test`): `files.archive` (create) and `files.extract` — scope + region
 * admission (archives are read from any user region but written into `work/` only), the
 * create→extract round trip, the matrix-mandated Zip Slip and expansion-bomb fixtures at the tool
 * level, the entry-type / corrupt / truncated refusals, conflict + overwrite policy, cross-scope
 * and region rejection, cancel short-circuit, the workspace quota, and sanitized failure messages
 * (no real path leaks, doc 10).
 */
class ArchiveToolsTest {
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

    private fun archiveExec(
        s: WorkspaceArtifactStore,
        args: JsonObject,
        cancel: CancelSignal = noCancel,
    ): ToolExecutorResult = FilesArchiveTool.executor(s).execute(call("files.archive", args, cancel))

    private fun extractExec(
        s: WorkspaceArtifactStore,
        args: JsonObject,
        cancel: CancelSignal = noCancel,
    ): ToolExecutorResult = FilesExtractTool.executor(s).execute(call("files.extract", args, cancel))

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

    private fun writeBytes(
        root: Path,
        rel: String,
        content: ByteArray,
    ): Path {
        val p = root.resolve(rel)
        p.parent?.let { Files.createDirectories(it) }
        Files.write(p, content)
        return p
    }

    private fun json(result: ToolExecutorResult): JsonObject =
        (result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")).output.jsonObject

    private fun failed(result: ToolExecutorResult): String =
        (result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")).detail

    private fun archiveArgs(
        source: String,
        destination: String,
        format: String? = null,
        overwrite: Boolean? = null,
    ): JsonObject =
        buildJsonObject {
            put("source", JsonPrimitive(source))
            put("destination", JsonPrimitive(destination))
            format?.let { put("format", JsonPrimitive(it)) }
            overwrite?.let { put("overwrite", JsonPrimitive(it)) }
        }

    private fun extractArgs(
        source: String,
        destination: String,
    ): JsonObject =
        buildJsonObject {
            put("source", JsonPrimitive(source))
            put("destination", JsonPrimitive(destination))
        }

    // ── descriptor contract ─────────────────────────────────────────────────────────────

    @Test
    fun descriptorsAreValidL2BuiltInMutations() {
        val archive = FilesArchiveTool.descriptor()
        val extract = FilesExtractTool.descriptor()
        assertEquals("files.archive", archive.name.value)
        assertEquals("files.extract", extract.name.value)
        listOf(archive, extract).forEach { d ->
            assertEquals(1, d.version.value)
            assertEquals(ToolOperationClass.LOCAL_MUTATION, d.operationClass)
            assertEquals(RiskLevel.L2, d.baseRisk)
            assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
            assertTrue(ToolSchema.check(d.inputSchema).isEmpty())
            assertTrue(ToolSchema.check(d.outputSchema).isEmpty())
        }
        assertEquals(Idempotency.IDEMPOTENT, archive.idempotency)
        assertEquals(Idempotency.NON_IDEMPOTENT, extract.idempotency)
    }

    // ── create → extract round trip ──────────────────────────────────────────────────────

    @Test
    fun archiveCreatesAZipAndRoundTripsThroughExtract() {
        val root = freshScope("a1")
        write(root, "work/src/a.txt", "hello")
        write(root, "work/src/sub/b.txt", "world")
        val out = json(archiveExec(store(root), archiveArgs("scope:ws:work/src", "scope:ws:work/archive.zip")))
        assertEquals("zip", out.getValue("format").jsonPrimitive.content)
        assertEquals(
            3,
            out
                .getValue("entryCount")
                .jsonPrimitive.content
                .toInt(),
        )
        assertTrue(Files.exists(root.resolve("work/archive.zip")))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(FilesArchiveTool.descriptor().outputSchema, out),
        )

        Files.createDirectories(root.resolve("work/ext"))
        val ext = json(extractExec(store(root), extractArgs("scope:ws:work/archive.zip", "scope:ws:work/ext")))
        assertEquals(
            2,
            ext
                .getValue("files")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            1,
            ext
                .getValue("directories")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals("hello", String(Files.readAllBytes(root.resolve("work/ext/work/src/a.txt"))))
        assertEquals("world", String(Files.readAllBytes(root.resolve("work/ext/work/src/sub/b.txt"))))
    }

    @Test
    fun archiveCreatesATarAndExtractsIt() {
        val root = freshScope("a2")
        write(root, "work/t/x.txt", "tar-payload")
        val out = json(archiveExec(store(root), archiveArgs("scope:ws:work/t", "scope:ws:work/t.tar", format = "tar")))
        assertEquals("tar", out.getValue("format").jsonPrimitive.content)
        Files.createDirectories(root.resolve("work/ext"))
        val ext = json(extractExec(store(root), extractArgs("scope:ws:work/t.tar", "scope:ws:work/ext")))
        assertEquals("tar", ext.getValue("format").jsonPrimitive.content)
        assertEquals("tar-payload", String(Files.readAllBytes(root.resolve("work/ext/work/t/x.txt"))))
    }

    // ── region + scope admission ─────────────────────────────────────────────────────────

    @Test
    fun archiveRefusesADestinationOutsideWork() {
        val root = freshScope("a3")
        write(root, "work/src/a.txt", "x")
        val detail = failed(archiveExec(store(root), archiveArgs("scope:ws:work/src", "scope:ws:output/out.zip")))
        assertTrue(detail.contains("must be inside work/"))
        assertFalse(detail.contains(root.toString()))
        assertFalse(Files.exists(root.resolve("output/out.zip")))
    }

    @Test
    fun archiveRefusesASourceOutsideUserRegions() {
        val root = freshScope("a4")
        val detail = failed(archiveExec(store(root), archiveArgs("scope:ws:.helix", "scope:ws:work/x.zip")))
        assertTrue(detail.contains("input/, work/ or output/"))
        assertFalse(detail.contains(root.toString()))
    }

    @Test
    fun archiveRefusesCrossScope() {
        val a = freshScope("a5a")
        val b = freshScope("a5b")
        val s = twoScopeStore(a, b)
        write(a, "work/src/a.txt", "x")
        val detail = failed(archiveExec(s, archiveArgs("scope:ws:work/src", "scope:other:work/o.zip")))
        assertTrue(detail.contains("same scope"))
        assertFalse(Files.exists(b.resolve("work/o.zip")))
    }

    @Test
    fun extractRefusesADestinationOutsideWork() {
        val root = freshScope("a6")
        writeBytes(root, "input/x.zip", ArchiveTestFixtures.zipWithEntry("a.txt", "hi".toByteArray()))
        Files.createDirectories(root.resolve("output/target"))
        val detail = failed(extractExec(store(root), extractArgs("scope:ws:input/x.zip", "scope:ws:output/target")))
        assertTrue(detail.contains("must be inside work/"))
        assertFalse(detail.contains(root.toString()))
    }

    @Test
    fun extractRefusesCrossScope() {
        val a = freshScope("a7a")
        val b = freshScope("a7b")
        val s = twoScopeStore(a, b)
        writeBytes(a, "input/x.zip", ArchiveTestFixtures.zipWithEntry("a.txt", "hi".toByteArray()))
        Files.createDirectories(b.resolve("work/out"))
        val detail = failed(extractExec(s, extractArgs("scope:ws:input/x.zip", "scope:other:work/out")))
        assertTrue(detail.contains("same scope"))
    }

    // ── matrix-mandated security fixtures (tool level) ───────────────────────────────────

    @Test
    fun aZipSlipArchiveIsRefusedAndEscapesNothing() {
        val root = freshScope("a8")
        writeBytes(root, "input/evil.zip", ArchiveTestFixtures.zipWithEntry("../evil.txt", "pwned".toByteArray()))
        Files.createDirectories(root.resolve("work/out"))
        val detail = failed(extractExec(store(root), extractArgs("scope:ws:input/evil.zip", "scope:ws:work/out")))
        assertTrue(detail.contains("path traversal"))
        // The slip name must never materialize outside the work/ destination.
        assertFalse(Files.exists(root.resolve("evil.txt")))
        assertFalse(Files.exists(root.resolve("work/evil.txt")))
        assertFalse(detail.contains(root.toString()))
    }

    @Test
    fun anExpansionBombIsRefusedAndWritesNothing() {
        val root = freshScope("a9")
        writeBytes(root, "input/bomb.zip", ArchiveTestFixtures.zipWithEntry("bomb.txt", ByteArray(512 * 1024) { 0x41 }))
        Files.createDirectories(root.resolve("work/out"))
        val detail = failed(extractExec(store(root), extractArgs("scope:ws:input/bomb.zip", "scope:ws:work/out")))
        assertTrue(detail.contains("expansion"))
        assertFalse(Files.exists(root.resolve("work/out/bomb.txt")))
        assertFalse(detail.contains(root.toString()))
    }

    @Test
    fun aSymlinkTarIsRefusedByTheTool() {
        val root = freshScope("a10")
        writeBytes(root, "input/sym.tar", ArchiveTestFixtures.tarBlock("link", 0x32.toByte(), ByteArray(0)))
        Files.createDirectories(root.resolve("work/out"))
        val detail = failed(extractExec(store(root), extractArgs("scope:ws:input/sym.tar", "scope:ws:work/out")))
        assertTrue(detail.contains("unsupported entry type"))
        assertFalse(detail.contains(root.toString()))
    }

    @Test
    fun aCorruptArchiveIsRefusedByTheTool() {
        val root = freshScope("a11")
        writeBytes(
            root,
            "input/bad.tar",
            ArchiveTestFixtures.tarBlock("f.txt", 0x30.toByte(), "data".toByteArray(), checksumDelta = 1),
        )
        Files.createDirectories(root.resolve("work/out"))
        val detail = failed(extractExec(store(root), extractArgs("scope:ws:input/bad.tar", "scope:ws:work/out")))
        assertTrue(detail.contains("corrupt"))
        assertFalse(detail.contains(root.toString()))
        assertFalse(Files.exists(root.resolve("work/out/f.txt")))
    }

    // ── conflict / empty / format / missing-destination ─────────────────────────────────

    @Test
    fun archiveConflictFailsClosedThenOverwrites() {
        val root = freshScope("a12")
        write(root, "work/src/a.txt", "x")
        write(root, "work/archive.zip", "existing")
        val detail = failed(archiveExec(store(root), archiveArgs("scope:ws:work/src", "scope:ws:work/archive.zip")))
        assertTrue(detail.contains("already exists"))
        assertEquals("existing", String(Files.readAllBytes(root.resolve("work/archive.zip"))))
        archiveExec(store(root), archiveArgs("scope:ws:work/src", "scope:ws:work/archive.zip", overwrite = true))
        assertTrue("the placeholder was replaced by a real archive", Files.size(root.resolve("work/archive.zip")) > 8)
    }

    @Test
    fun archiveAnEmptySourceFailsClosed() {
        val root = freshScope("a13")
        Files.createDirectories(root.resolve("work/empty"))
        val detail = failed(archiveExec(store(root), archiveArgs("scope:ws:work/empty", "scope:ws:work/e.zip")))
        assertTrue(detail.contains("empty"))
        assertFalse(Files.exists(root.resolve("work/e.zip")))
    }

    @Test
    fun anUnsupportedFormatIsRefused() {
        val root = freshScope("a14")
        write(root, "work/x/a.txt", "hi")
        val detail =
            failed(archiveExec(store(root), archiveArgs("scope:ws:work/x", "scope:ws:work/o.zip", format = "rar")))
        assertTrue(detail.contains("format"))
    }

    @Test
    fun extractRefusesAMissingDestination() {
        val root = freshScope("a15")
        writeBytes(root, "input/x.zip", ArchiveTestFixtures.zipWithEntry("a.txt", "hi".toByteArray()))
        val detail = failed(extractExec(store(root), extractArgs("scope:ws:input/x.zip", "scope:ws:work/nope")))
        assertTrue(detail.contains("does not exist"))
        assertFalse(detail.contains(root.toString()))
    }

    // ── invalid args + cancel + quota ────────────────────────────────────────────────────

    @Test
    fun invalidArgumentsFailClosed() {
        val root = freshScope("a16")
        val missing = buildJsonObject { put("source", JsonPrimitive("scope:ws:work/x")) }
        assertTrue(failed(archiveExec(store(root), missing)).contains("invalid"))
        assertTrue(failed(archiveExec(store(root), archiveArgs("/abs", "scope:ws:work/o.zip"))).contains("invalid"))
        assertTrue(failed(extractExec(store(root), buildJsonObject {})).contains("invalid"))
    }

    @Test
    fun aCancelSignalBeforeExecutionReportsCancelled() {
        val root = freshScope("a17")
        val cancelled =
            object : CancelSignal {
                override fun isCancelled(): Boolean = true
            }
        assertEquals(
            ToolExecutorResult.Cancelled,
            archiveExec(store(root), archiveArgs("scope:ws:work/x", "scope:ws:work/o.zip"), cancelled),
        )
        assertEquals(
            ToolExecutorResult.Cancelled,
            extractExec(store(root), extractArgs("scope:ws:input/x.zip", "scope:ws:work/out"), cancelled),
        )
    }

    @Test
    fun aQuotaExceedingArchiveFailsClosedAndWritesNothing() {
        val a = freshScope("a18a")
        val b = tmp.newFolder("a18b").toPath()
        val tiny =
            WorkspaceArtifactStore(ScopeRootResolver { id -> if (id == "ws") a else b }, WorkspaceQuotaPolicy(16L))
        tiny.ensureLayout("other")
        write(a, "work/src/a.txt", "x".repeat(32))
        val r = archiveExec(tiny, archiveArgs("scope:ws:work/src", "scope:ws:work/o.zip"))
        assertTrue(failed(r).contains("quota"))
        assertFalse(Files.exists(a.resolve("work/o.zip")))
    }
}
