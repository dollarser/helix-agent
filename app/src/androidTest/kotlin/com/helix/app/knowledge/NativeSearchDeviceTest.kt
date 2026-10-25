package com.helix.app.knowledge

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
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
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Android filesystem execution only: synthetic private files, no model or runtime process. */
class NativeSearchDeviceTest {
    @Test fun androidGlobGrepAndReadBackKeepOneResourceIdentity() =
        fixture { store, root ->
            Files.createDirectories(root.resolve("nested"))
            Files.write(root.resolve("nested/笔记.md"), "first\nSYNTHETIC_NEEDLE".toByteArray())
            val glob = GlobTool.executor(store).execute(call("glob", "pattern" to "**/*.md"))
            val path =
                output(glob)
                    .getValue("matches")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("path")
                    .jsonPrimitive.content
            assertEquals("scope:synthetic:nested/笔记.md", path)
            val grep = output(GrepTool.executor(store).execute(call("grep", "query" to "SYNTHETIC_NEEDLE")))
            val hit =
                grep
                    .getValue("matches")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(path, hit.getValue("path").jsonPrimitive.content)
            assertEquals("2", hit.getValue("line").jsonPrimitive.content)
            val read = store.readWindow(FileScopePath.fromModelReference(path), 0, 1024, true)
            assertEquals(read.sha256, hit.getValue("sha256").jsonPrimitive.content)
        }

    @Test fun androidChangeAndScopeRevocationAreNotHidden() =
        fixture { store, root ->
            Files.write(root.resolve("note"), "needle".toByteArray())

            fun hash() =
                output(GrepTool.executor(store).execute(call("grep", "query" to "needle")))
                    .getValue("matches")
                    .jsonArray
                    .single()
                    .jsonObject["sha256"]
            val old = hash()
            Files.write(root.resolve("note"), "changed needle".toByteArray())
            assertNotEquals(old, hash())
            val denied = call("glob", "path" to "scope:revoked:.", "pattern" to "**/*")
            assertTrue(GlobTool.executor(store).execute(denied) is ToolExecutorResult.Failed)
        }

    @Test fun androidCancellationDoesNotReturnAnEmptySuccess() =
        fixture { store, _ ->
            val cancelled =
                call("glob", "pattern" to "**/*").copy(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = true
                        },
                )
            assertEquals(ToolExecutorResult.Cancelled, GlobTool.executor(store).execute(cancelled))
        }

    private fun fixture(block: (WorkspaceArtifactStore, java.nio.file.Path) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".asrvalidation"))
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "native-search-")
        val store =
            WorkspaceArtifactStore(
                ScopeRootResolver {
                    if (it == "synthetic") root else throw ScopeNotAvailable(it)
                },
            )
        try {
            block(store, root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun output(value: ToolExecutorResult) = (value as ToolExecutorResult.Completed).output.jsonObject

    private fun call(
        name: String,
        vararg arguments: Pair<String, String>,
    ) = ExecutableToolCall(
        "synthetic-call",
        name,
        "1",
        JsonObject(
            buildJsonObject {
                put("path", JsonPrimitive("scope:synthetic:."))
                arguments.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            },
        ),
        ExecutionTargetType.LOCAL_ANDROID,
        Instant.now().plusSeconds(30),
        object : CancelSignal {
            override fun isCancelled() = false
        },
    )
}
