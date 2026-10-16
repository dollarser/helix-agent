package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ReadWindow
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceFileBackend
import com.helix.core.workspace.WorkspaceFileInfo
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant

class FileReadIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val path = FileScopePath("ws", "file.txt")

    @Test fun windowHashCoversTheFullFileAndGuardsAnActualEdit() {
        val text = "header 😀 target footer"
        val file = temporary.newFile("file.txt").also { it.writeText(text) }
        val store = WorkspaceArtifactStore(ScopeRootResolver { temporary.root.toPath() })
        val result = read(store, true, offset = 7, maxBytes = 10)
        val output = (result as ToolExecutorResult.Completed).output.jsonObject
        assertEquals(hash(text.toByteArray()), output.getValue("sha256").jsonPrimitive.content)
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(ReadTool.descriptor().outputSchema, output),
        )
        assertTrue(edit(store, hash(text.toByteArray())) is ToolExecutorResult.Completed)
        assertEquals("header 😀 changed footer", file.readText())
        val preview = (read(store, false) as ToolExecutorResult.Completed).output.jsonObject
        assertFalse("sha256" in preview)
    }

    @Test fun documentWindowAndHashUseOneStreamAndDoNotNeedALocalPath() {
        val backend = Documents("header target footer")
        val result = read(store(backend), true, offset = 7, maxBytes = 3) as ToolExecutorResult.Completed
        assertEquals(
            "tar",
            result.output.jsonObject
                .getValue("text")
                .jsonPrimitive.content,
        )
        assertEquals(
            hash(backend.bytes),
            result.output.jsonObject
                .getValue("sha256")
                .jsonPrimitive.content,
        )
        assertEquals(1, backend.reads)
        assertEquals(0, backend.writes)
    }

    @Test fun editRejectsLoadedContentFromAnotherVersionEvenIfTheFileReturnsToTheExpectedVersion() {
        val original = "original target"
        val backend = Documents("unseen target")
        backend.afterRead = { if (backend.reads == 2) backend.bytes = original.toByteArray() }
        val result = edit(store(backend), hash(original.toByteArray()))
        assertTrue(result is ToolExecutorResult.Failed)
        assertEquals(0, backend.writes)
        assertEquals(original, String(backend.bytes))
    }

    @Test fun failedArtifactPublicationDoesNotClaimTheFileWasUnchanged() {
        val file = temporary.newFile("file.txt").also { it.writeText("target") }
        val store = WorkspaceArtifactStore(ScopeRootResolver { temporary.root.toPath() })
        val sink =
            object : WorkspaceArtifactStore.ArtifactSink {
                override fun register(
                    sessionId: String,
                    record: WorkspaceArtifactStore.ArtifactRecord,
                ): Unit = throw IOException("observer failed after publish")
            }
        val result = EditTool.executor(store, sink).execute(editCall(hash("target".toByteArray())))
        assertTrue((result as ToolExecutorResult.Failed).requiresReview)
        assertFalse(result.sideEffectFree)
        assertEquals("changed", file.readText())
    }

    @Test fun partialDocumentWriteReportsUncertaintyAndNeverReplays() {
        val backend = Documents("target").also { it.failWrite = true }
        val result = edit(store(backend), hash(backend.bytes)) as ToolExecutorResult.Failed
        assertTrue(result.requiresReview)
        assertFalse(result.sideEffectFree)
        assertEquals("c", String(backend.bytes))
        assertEquals(1, backend.writes)
    }

    @Test fun prePublishReadFailureAndCancellationDoNotAttemptAnEdit() {
        val backend = Documents("target")
        val hash = hash(backend.bytes)
        backend.onRead = { throw IOException("read failed") }
        val failed = edit(store(backend), hash) as ToolExecutorResult.Failed
        assertTrue(failed.sideEffectFree)
        assertFalse(failed.requiresReview)
        backend.onRead = {}
        var cancelled = false
        backend.afterRead = { cancelled = true }
        val signal =
            object : CancelSignal {
                override fun isCancelled() = cancelled
            }
        val result = EditTool.executor(store(backend)).execute(editCall(hash).copy(cancel = signal))
        assertEquals(ToolExecutorResult.Cancelled, result)
        assertEquals(0, backend.writes)
        assertEquals("target", String(backend.bytes))
    }

    @Test fun cancelledOrExpiredReadNeverReturnsWindowOrHash() {
        val backend = Documents("target")
        var cancelled = false
        backend.onRead = { cancelled = true }
        val signal =
            object : CancelSignal {
                override fun isCancelled() = cancelled
            }
        assertEquals(ToolExecutorResult.Cancelled, read(store(backend), true, cancel = signal))
        backend.onRead = {}
        assertEquals(ToolExecutorResult.TimedOut, read(store(backend), true, deadline = Instant.EPOCH))
    }

    @Test fun changedSizeAndOversizedHashAreRefusedButLargeFilePreviewsRemainAvailable() {
        for (delta in listOf(-1L, 1L)) {
            val backend = Documents("target").also { it.declaredSize = it.bytes.size + delta }
            assertTrue(read(store(backend), true, maxBytes = 2) is ToolExecutorResult.Failed)
        }
        val backend = Documents("target").also { it.declaredSize = ReadWindow.MAX_HASH_BYTES + 1 }
        assertTrue(read(store(backend), true) is ToolExecutorResult.Failed)
        assertEquals(0, backend.bytesRead)
        assertTrue(read(store(backend), false, maxBytes = 2) is ToolExecutorResult.Completed)
        assertEquals(2, backend.bytesRead)
    }

    @Test fun emptyFileAndPastEofHashStillDescribeTheWholeStream() {
        for (text in listOf("", "target")) {
            val result = read(store(Documents(text)), true, offset = 100) as ToolExecutorResult.Completed
            assertEquals(
                hash(text.toByteArray()),
                result.output.jsonObject
                    .getValue("sha256")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "",
                result.output.jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            )
        }
    }

    private fun read(
        store: WorkspaceArtifactStore,
        includeHash: Boolean,
        offset: Long = 0,
        maxBytes: Long = 1024,
        cancel: CancelSignal = NoCancellation,
        deadline: Instant = Instant.now().plusSeconds(30),
    ) = ReadTool.executor(store).execute(
        call(
            "read",
            buildJsonObject {
                put("path", path.toModelReference())
                put("offset", offset)
                put("maxBytes", maxBytes)
                put("includeSha256", includeHash)
            },
        ).copy(cancel = cancel, deadline = deadline),
    )

    private fun edit(
        store: WorkspaceArtifactStore,
        hash: String,
    ) = EditTool.executor(store).execute(editCall(hash))

    private fun editCall(hash: String) =
        call(
            "edit",
            buildJsonObject {
                put("path", path.toModelReference())
                put("oldText", "target")
                put("newText", "changed")
                put("expectedSha256", hash)
            },
        )

    private fun call(
        name: String,
        args: JsonObject,
    ) = ExecutableToolCall(
        "c",
        name,
        "1",
        args,
        ExecutionTargetType.LOCAL_ANDROID,
        Instant.now().plusSeconds(30),
        NoCancellation,
        "s",
        "t",
    )

    private fun store(backend: Documents) =
        WorkspaceArtifactStore(
            ScopeRootResolver { error("Documents do not resolve to a local root") },
            documentBackend = { backend },
        )

    private fun hash(bytes: ByteArray) =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class Documents(
        text: String,
    ) : WorkspaceFileBackend {
        var bytes = text.toByteArray()
        var declaredSize: Long? = null
        var reads = 0
        var writes = 0
        var bytesRead = 0
        var failWrite = false
        var onRead: () -> Unit = {}
        var afterRead: () -> Unit = {}

        override fun validateMutation(path: String) = Unit

        override fun stat(path: String) = WorkspaceFileInfo(false, declaredSize ?: bytes.size.toLong())

        override fun children(path: String) = emptyList<String>()

        override fun create(
            path: String,
            directory: Boolean,
        ) = error("Unexpected create")

        override fun rename(
            path: String,
            destination: String,
        ) = error("Unexpected rename")

        override fun delete(path: String) = error("Unexpected delete")

        override fun read(path: String): ByteArrayInputStream {
            reads++
            return object : ByteArrayInputStream(bytes) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    onRead()
                    return super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
                }

                override fun close() {
                    afterRead()
                }
            }
        }

        override fun write(path: String): ByteArrayOutputStream {
            writes++
            return object : ByteArrayOutputStream() {
                override fun close() {
                    bytes = if (failWrite) toByteArray().take(1).toByteArray() else toByteArray()
                    if (failWrite) throw IOException("partial write")
                }
            }
        }
    }
}
