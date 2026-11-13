package com.helix.app.files

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.files.GlobTool
import com.helix.tools.files.GrepTool
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.time.Instant

/** Same production glob/grep on the real user-selected system DocumentsProvider, never NIO. */
internal class SafNativeSearchContract(
    private val saf: SafManualFileBackend,
    private val scope: String,
    owned: String,
) {
    private val base = "$owned/native"
    private val store =
        WorkspaceArtifactStore(
            ScopeRootResolver { error("System SAF search must not fall back to native paths") },
            documentBackend = { requested ->
                require(requested == scope)
                saf
            },
        )

    fun verify() {
        saf.create(base, true)
        saf.create("$base/nested", true)
        put("$base/条款.md", "decoy\nnot the requested clause")
        put("$base/nested/条款.md", "first\nCLAUSE.[42] 甲方\nlast")
        val glob = output(false, "pattern" to "**/*.md")
        assertEquals("true", glob.getValue("complete").jsonPrimitive.content)
        assertEquals(
            setOf("scope:$scope:$base/条款.md", "scope:$scope:$base/nested/条款.md"),
            hits(glob).map { it.getValue("path").jsonPrimitive.content }.toSet(),
        )
        val page = output(false, "pattern" to "**/*.md", "maxResults" to 1)
        assertEquals("false", page.getValue("complete").jsonPrimitive.content)
        assertEquals("1", page.getValue("nextOffset").jsonPrimitive.content)
        val second = output(false, "pattern" to "**/*.md", "maxResults" to 1, "offset" to 1)
        assertNotEquals(hits(page).single()["path"], hits(second).single()["path"])
        val hit = hits(output(true, "query" to "CLAUSE.[42]")).single()
        assertEquals("2", hit.getValue("line").jsonPrimitive.content)
        val path = FileScopePath.fromModelReference(hit.getValue("path").jsonPrimitive.content)
        assertEquals("$base/nested/条款.md", path.relativePath)
        assertEquals(store.readWindow(path, 0, 1024, true).sha256, hit.getValue("sha256").jsonPrimitive.content)
        put(path.relativePath, "changed\nCLAUSE.[42] 乙方")
        val changed = hits(output(true, "query" to "CLAUSE.[42]")).single()
        assertNotEquals(hit["sha256"], changed["sha256"])
        assertEquals(store.readWindow(path, 0, 1024, true).sha256, changed.getValue("sha256").jsonPrimitive.content)
        assertTrue(hits(output(true, "query" to "CLAUSEX42")).isEmpty())
        put("$base/binary", "\u0000CLAUSE.[42]")
        val partial = output(true, "query" to "ABSENT")
        assertTrue(hits(partial).isEmpty())
        assertEquals("false", partial.getValue("complete").jsonPrimitive.content)
        assertEquals("1", partial.getValue("skippedContentFiles").jsonPrimitive.content)
    }

    fun verifyRevoked() {
        val result =
            GlobTool.executor(store).execute(
                ExecutableToolCall(
                    "system-saf-revoked",
                    "glob",
                    "1",
                    buildJsonObject {
                        put("path", JsonPrimitive("scope:$scope:$base"))
                        put("pattern", JsonPrimitive("**/*"))
                    },
                    ExecutionTargetType.LOCAL_ANDROID,
                    Instant.now().plusSeconds(30),
                    object : CancelSignal {
                        override fun isCancelled() = false
                    },
                ),
            )
        assertTrue(result is ToolExecutorResult.Failed)
    }

    private fun put(
        path: String,
        value: String,
    ) {
        if (saf.stat(path) == null) saf.create(path, false)
        saf.write(path).use { it.write(value.toByteArray()) }
    }

    private fun hits(output: JsonObject) = output.getValue("matches").jsonArray.map { it.jsonObject }

    private fun output(
        content: Boolean,
        vararg arguments: Pair<String, Any>,
    ): JsonObject {
        val tool = if (content) GrepTool.executor(store) else GlobTool.executor(store)
        val result =
            tool.execute(
                ExecutableToolCall(
                    "system-saf-search",
                    if (content) "grep" else "glob",
                    "1",
                    buildJsonObject {
                        put("path", JsonPrimitive("scope:$scope:$base"))
                        arguments.forEach { (key, value) ->
                            put(key, if (value is Int) JsonPrimitive(value) else JsonPrimitive(value.toString()))
                        }
                    },
                    ExecutionTargetType.LOCAL_ANDROID,
                    Instant.now().plusSeconds(30),
                    object : CancelSignal {
                        override fun isCancelled() = false
                    },
                ),
            )
        assertTrue("Expected completed search, got $result", result is ToolExecutorResult.Completed)
        return (result as ToolExecutorResult.Completed).output.jsonObject
    }
}
