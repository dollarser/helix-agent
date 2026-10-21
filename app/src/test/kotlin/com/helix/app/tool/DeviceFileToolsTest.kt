package com.helix.app.tool

import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeviceFileToolsTest {
    private var launches = 0
    private val handoff =
        DeviceFileTools.Handoff { _, _, _ ->
            launches++
            ToolExecutorResult.Cancelled
        }

    private fun run(
        args: String,
        open: Boolean = false,
        cancel: Boolean = false,
    ): ToolExecutorResult =
        DeviceFileTools.executor(open, handoff).execute(
            ExecutableToolCall(
                "call",
                DeviceFileTools.SHARE,
                "1",
                Json.parseToJsonElement(args) as JsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(30),
                object : CancelSignal {
                    override fun isCancelled() = cancel
                },
            ),
        )

    @Test fun rejectsRawPathsAndUrls() {
        listOf("/sdcard/test.txt", "file:///test.txt", "https://example.com/test", "content://other/file").forEach {
            assertTrue(run("""{"paths":["$it"]}""") is ToolExecutorResult.Failed)
        }
        assertEquals(0, launches)
    }

    @Test fun rejectsMixedValidAndInvalidReferences() {
        assertTrue(run("""{"paths":["scope:app:test.txt",1]}""") is ToolExecutorResult.Failed)
        assertEquals(0, launches)
    }

    @Test fun scopedReferenceReachesHandoff() {
        run("""{"paths":["scope:app:test.txt"]}""")
        assertEquals(1, launches)
    }

    @Test fun openRequiresExactlyOneFile() {
        assertTrue(run("""{"paths":["scope:app:a","scope:app:b"]}""", open = true) is ToolExecutorResult.Failed)
        assertEquals(0, launches)
    }

    @Test fun emptyAndOversizedListsRefused() {
        assertTrue(run("""{"paths":[]}""") is ToolExecutorResult.Failed)
        val paths = (1..9).joinToString(",") { "\"scope:app:$it\"" }
        assertTrue(run("""{"paths":[$paths]}""") is ToolExecutorResult.Failed)
        assertEquals(0, launches)
    }

    @Test fun cancellationDoesNotLaunch() {
        assertEquals(ToolExecutorResult.Cancelled, run("""{"paths":["scope:app:a"]}""", cancel = true))
        assertEquals(0, launches)
    }

    @Test fun descriptorsValidate() {
        assertEquals(DeviceFileTools.OPEN, DeviceFileTools.descriptor(true).name.value)
        assertEquals(DeviceFileTools.SHARE, DeviceFileTools.descriptor(false).name.value)
    }
}
