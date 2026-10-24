package com.helix.app.proot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.ProotLogClient
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Two explicitly selected phases; the host stops ONLY its temporary package between phases. */
class ProotLogRestartDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val anchor get() = File(context.cacheDir, "closeout-restart-owner.json")

    @Test fun prepareOwnedFailedExecution() {
        check(!anchor.exists()) { "Existing owned evidence must be inspected before repeating preparation" }
        val fixture = InPlaceLogCloseoutDeviceTest.Fixture()
        var retained = false
        try {
            val record = fixture.run("echo persistent > document.txt; echo restart-error >&2; exit 7")
            assertEquals(ProotJobState.FAILED, record.state)
            fixture.persist(record)
            anchor.writeText(fixture.retainForProcessRestart().toString())
            retained = true
        } finally {
            if (!retained) fixture.close()
        }
    }

    @Test fun readOriginalEvidenceAfterHostStoppedTheTemporaryPackage() {
        val identity = Json.parseToJsonElement(anchor.readText()).jsonObject
        val name = identity.getValue("database").jsonPrimitive.content
        require(Regex("closeout-[a-f0-9]{12}").matches(name))
        val workspaceName = identity.getValue("workspaceName").jsonPrimitive.content
        require(workspaceName == name)
        val scratch = File(context.cacheDir, name)
        val workspace = File(context.filesDir, "workspaces/app/$workspaceName")
        val storage = HelixStorage.open(context, name, File(scratch, "content"))
        try {
            val store = ProotResultStore(storage, File(context.filesDir, "workspaces/app"), File(scratch, "verify"))
            val job = identity.getValue("jobId").jsonPrimitive.content
            assertNull(ProotLogClient().read(job, identity.getValue("inputHash").jsonPrimitive.content, null))
            val call =
                ExecutableToolCall(
                    "read-after-restart",
                    ProotLogTool.NAME,
                    "1",
                    buildJsonObject {
                        put("logRef", "turn/call")
                        put("source", "saved")
                        put("stream", "stderr")
                        put("offset", 0)
                    },
                    ExecutionTargetType.LOCAL_ANDROID,
                    Instant.now().plusSeconds(5),
                    NoCancellation,
                    "session",
                    "turn",
                )
            val result =
                ProotLogTool.read(storage, store, call, { emptySet() }, File(scratch, "read"))
                    as ToolExecutorResult.Completed
            assertTrue(
                result.output.jsonObject
                    .getValue("content")
                    .jsonPrimitive.content
                    .contains("restart-error"),
            )
            assertEquals(ProotJobState.FAILED, store.savedRecord("turn", "call")!!.state)
            assertEquals("FAILED", storage.toolCalls.resolve("row").state)
            assertEquals("persistent\n", File(workspace, "document.txt").readText())
            assertEquals(1, storage.toolCalls.listByTurn("turn").size)
            storage.deleteSessionPermanently("session")
            File(context.filesDir, "workspaces/app/.helix/proot-results/$job.zip").delete()
        } finally {
            storage.close()
            context.deleteDatabase(name)
            workspace.deleteRecursively()
            scratch.deleteRecursively()
            anchor.delete()
        }
    }
}
