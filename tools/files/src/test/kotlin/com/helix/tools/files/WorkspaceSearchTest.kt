package com.helix.tools.files

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceFileBackend
import com.helix.core.workspace.WorkspaceFileInfo
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant

class WorkspaceSearchTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root by lazy { tmp.newFolder().toPath() }
    private val store by lazy {
        WorkspaceArtifactStore(
            ScopeRootResolver {
                if (it ==
                    "ws"
                ) {
                    root
                } else {
                    throw com.helix.core.workspace
                        .ScopeNotAvailable(it)
                }
            },
        )
    }
    private val active =
        object : CancelSignal {
            override fun isCancelled() = false
        }

    private fun write(
        path: String,
        text: String,
    ) {
        Files.createDirectories(root.resolve(path).parent)
        Files.write(root.resolve(path), text.toByteArray())
    }

    private fun args(vararg values: Pair<String, Any>): JsonObject =
        buildJsonObject {
            put("path", JsonPrimitive("scope:ws:."))
            values.forEach { (key, value) ->
                put(
                    key,
                    when (value) {
                        is Int -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
                        else -> JsonPrimitive(value.toString())
                    },
                )
            }
        }

    private fun execute(
        content: Boolean,
        args: JsonObject,
        cancel: CancelSignal = active,
        deadline: Instant = Instant.now().plusSeconds(30),
        source: WorkspaceArtifactStore = store,
    ): ToolExecutorResult =
        SearchToolContract.executor(source, content).execute(
            ExecutableToolCall(
                "call",
                if (content) "grep" else "glob",
                "1",
                args,
                ExecutionTargetType.LOCAL_ANDROID,
                deadline,
                cancel,
            ),
        )

    private fun result(
        content: Boolean,
        vararg values: Pair<String, Any>,
    ): JsonObject = (execute(content, args(*values)) as ToolExecutorResult.Completed).output.jsonObject

    @Test fun globFindsNestedUnicodeFilesAndAnchoredReadBack() {
        write("报告.md", "root")
        write("nested/报告.md", "nested")
        write("nested/unrelated.txt", "other")
        val out = result(false, "pattern" to "**/*.md")
        val paths =
            out.getValue("matches").jsonArray.map {
                it.jsonObject
                    .getValue("path")
                    .jsonPrimitive.content
            }
        assertEquals(setOf("scope:ws:报告.md", "scope:ws:nested/报告.md"), paths.toSet())
        assertEquals(
            setOf("root", "nested"),
            paths
                .map {
                    store.readAll(FileScopePath.fromModelReference(it)).toString(Charsets.UTF_8)
                }.toSet(),
        )
        assertEquals("true", out.getValue("complete").jsonPrimitive.content)
    }

    @Test fun globIsSegmentAwareAndBoundedWithoutRegexBacktracking() {
        assertTrue(PathGlob.matches("**/*.md", "a.md", false))
        assertTrue(PathGlob.matches("**/*.md", "a/b.md", false))
        assertFalse(PathGlob.matches("*.md", "a/b.md", false))
        assertTrue(PathGlob.matches("**/*REPORT?*", "deep/report1.txt", true))
        assertFalse(PathGlob.matches("**/(a+)+", "aaaa", false))
        assertFalse(PathGlob.matches("*a".repeat(120) + "b", "a".repeat(400), false))
    }

    @Test fun exclusionsPruneDirectoriesAndHiddenFilesAreExplicitlyIncluded() {
        write("skip/hidden.txt", "needle")
        write(".hidden.txt", "needle")
        val out = result(false, "pattern" to "**/*", "exclude" to listOf("skip"))
        assertEquals(
            "scope:ws:.hidden.txt",
            out
                .getValue("matches")
                .jsonArray
                .single()
                .jsonObject
                .getValue("path")
                .jsonPrimitive.content,
        )
    }

    @Test fun grepReturnsLiteralUnicodeLinesAndHashOfTheSameRead() {
        write("nested/a.md", "zero\n你好 [a.*]\nTAIL")
        val out = result(true, "query" to "[a.*]")
        val match =
            out
                .getValue("matches")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("2", match.getValue("line").jsonPrimitive.content)
        assertEquals("你好 [a.*]", match.getValue("text").jsonPrimitive.content)
        val read =
            store.readWindow(
                FileScopePath.fromModelReference(match.getValue("path").jsonPrimitive.content),
                0,
                1024,
                true,
            )
        assertEquals(read.sha256, match.getValue("sha256").jsonPrimitive.content)
    }

    @Test fun grepCaseAndPatternCanNarrowSearch() {
        write("one.md", "NEEDLE")
        write("two.txt", "needle")
        assertEquals(
            1,
            result(
                true,
                "query" to "needle",
                "ignoreCase" to true,
                "pattern" to "**/*.md",
            ).getValue("matches").jsonArray.size,
        )
        assertTrue(result(true, "query" to "needle", "pattern" to "**/*.md").getValue("matches").jsonArray.isEmpty())
    }

    @Test fun pagesRescanAndKeepDistinctLineLocations() {
        write("one.txt", "hit\nhit\nhit")
        val first = result(true, "query" to "hit", "maxResults" to 2)
        assertEquals("2", first.getValue("nextOffset").jsonPrimitive.content)
        assertEquals("true", first.getValue("truncated").jsonPrimitive.content)
        val last = result(true, "query" to "hit", "maxResults" to 2, "offset" to 2)
        assertEquals(
            "3",
            last
                .getValue("matches")
                .jsonArray
                .single()
                .jsonObject
                .getValue("line")
                .jsonPrimitive.content,
        )
        assertEquals("true", last.getValue("complete").jsonPrimitive.content)
    }

    @Test fun binaryAndOversizeDoNotBecomeFalseCompleteAbsence() {
        write("binary", "\u0000needle")
        write("large", "x".repeat(262145))
        val out = result(true, "query" to "not-present")
        assertEquals("2", out.getValue("skippedContentFiles").jsonPrimitive.content)
        assertEquals("false", out.getValue("complete").jsonPrimitive.content)
    }

    @Test fun cancelledAndExpiredCallsDoNotPretendCompleted() {
        write("one", "hello")
        assertEquals(
            ToolExecutorResult.Cancelled,
            execute(
                false,
                args("pattern" to "**/*"),
                object : CancelSignal {
                    override fun isCancelled() = true
                },
            ),
        )
        assertEquals(ToolExecutorResult.TimedOut, execute(false, args("pattern" to "**/*"), deadline = Instant.EPOCH))
        var checkpoints = 0
        assertEquals(
            ToolExecutorResult.Cancelled,
            execute(
                true,
                args("query" to "hello"),
                object : CancelSignal {
                    override fun isCancelled() = ++checkpoints > 3
                },
            ),
        )
    }

    @Test fun scopeRevocationAndTraversalFailClosed() {
        assertTrue(execute(false, args("path" to "scope:other:.", "pattern" to "**/*")) is ToolExecutorResult.Failed)
        assertTrue(
            execute(false, args("path" to "scope:ws:../private", "pattern" to "**/*")) is ToolExecutorResult.Failed,
        )
        assertTrue(execute(false, args("pattern" to "../*")) is ToolExecutorResult.Failed)
    }

    @Test fun symlinksAreNotFollowed() {
        val outside = tmp.newFolder().toPath()
        Files.write(outside.resolve("secret"), "needle".toByteArray())
        Files.createSymbolicLink(root.resolve("link"), outside)
        val out = result(true, "query" to "needle")
        assertTrue(out.getValue("matches").jsonArray.isEmpty())
        assertEquals("false", out.getValue("complete").jsonPrimitive.content)
    }

    @Test fun modifiedFileHasDifferentIdentityAndOldLocationIsNotAWriteToken() {
        write("a", "needle")
        val first =
            result(true, "query" to "needle")
                .getValue("matches")
                .jsonArray
                .single()
                .jsonObject
        write("a", "other\nneedle")
        val second =
            result(true, "query" to "needle")
                .getValue("matches")
                .jsonArray
                .single()
                .jsonObject
        assertNotEquals(first["sha256"], second["sha256"])
        assertEquals(first["path"], second["path"])
    }

    @Test fun documentBackendUsesSameSearchAndReadIdentity() {
        val backend =
            object : WorkspaceFileBackend {
                override fun stat(path: String) =
                    when (path) {
                        "" -> WorkspaceFileInfo(true, 0)
                        "note.md" -> WorkspaceFileInfo(false, 6)
                        else -> null
                    }

                override fun children(path: String) = listOf("note.md")

                override fun read(path: String) = ByteArrayInputStream("needle".toByteArray())

                override fun validateMutation(path: String) = error("read-only fixture")

                override fun create(
                    path: String,
                    directory: Boolean,
                ) = error("read-only fixture")

                override fun write(path: String): java.io.OutputStream = error("read-only fixture")

                override fun rename(
                    path: String,
                    destination: String,
                ) = error("read-only fixture")

                override fun delete(path: String) = error("read-only fixture")
            }
        val document =
            WorkspaceArtifactStore(
                ScopeRootResolver {
                    throw com.helix.core.workspace
                        .ScopeNotAvailable(it)
                },
                documentBackend = { backend },
            )
        val out =
            (
                execute(
                    true,
                    args("query" to "needle"),
                    source = document,
                ) as ToolExecutorResult.Completed
            ).output.jsonObject
        val hit =
            out
                .getValue("matches")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("scope:ws:note.md", hit.getValue("path").jsonPrimitive.content)
        assertEquals(
            64,
            hit
                .getValue("sha256")
                .jsonPrimitive.content.length,
        )
    }

    @Test fun schemasRejectRegexAndOutputsConform() {
        val invalid =
            ToolSchemaValidator.validate(
                GrepTool.descriptor().inputSchema,
                args(
                    "query" to "x",
                    "regex" to true,
                ),
            )
        assertNotEquals(ToolSchemaValidation.Valid, invalid)
        write("a", "needle")
        val out = result(true, "query" to "needle")
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(GrepTool.descriptor().outputSchema, out))
    }
}
