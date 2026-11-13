package com.helix.app.proot

import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DetachedJobBindingTest {
    @Test fun originalSessionCanResolveStableJobIdentity() {
        val binding = ProotJobBindingStore.detachedBinding(payload(), "session", "call")
        assertEquals("turn", binding.turnId)
        assertEquals("job_0123456789ab", binding.jobId)
        assertEquals("exec-1", binding.executionId)
        assertEquals("a".repeat(64), binding.inputManifestSha256)
    }

    @Test fun anotherSessionOrCallCannotAcquireControl() {
        assertThrows(IllegalStateException::class.java) {
            ProotJobBindingStore.detachedBinding(payload(), "another-session", "call")
        }
        assertThrows(IllegalStateException::class.java) {
            ProotJobBindingStore.detachedBinding(payload(), "session", "another-call")
        }
    }

    @Test fun synchronousAndUnknownBindingsCannotBecomeDetachedJobs() {
        listOf("2", "3").forEach { version ->
            val legacy = JsonObject(payload() + ("version" to JsonPrimitive(version)))
            assertThrows(IllegalStateException::class.java) {
                ProotJobBindingStore.detachedBinding(legacy, "session", "call")
            }
        }
        val wrongMode = JsonObject(payload() + ("executionMode" to JsonPrimitive("SYNC")))
        assertThrows(IllegalStateException::class.java) {
            ProotJobBindingStore.detachedBinding(wrongMode, "session", "call")
        }
    }

    @Test fun malformedIdentityCannotReachRuntime() {
        val malformed = JsonObject(payload() + ("inputManifestSha256" to JsonPrimitive("bad")))
        assertThrows(IllegalArgumentException::class.java) {
            ProotJobBindingStore.detachedBinding(malformed, "session", "call")
        }
    }

    @Test fun admissionRejectsForeignSynchronousAndMalformedHandlesWithoutRuntimeAccess() {
        val valid = payload().toString()
        assertEquals("exec-1", ProotJobBindingStore.controlBinding(valid, "session", "call").executionId)
        listOf(
            Triple(valid, "foreign", "call"),
            Triple(valid, "session", "other-call"),
            Triple(JsonObject(payload() - "executionMode").toString(), "session", "call"),
            Triple("{}", "session", "call"),
            Triple("[]", "session", "call"),
            Triple("not-json", "session", "call"),
        ).forEach { (json, session, call) ->
            assertThrows(ExecutionOwnership.ControlBindingRejected::class.java) {
                ProotJobBindingStore.controlBinding(json, session, call)
            }
        }
    }

    private fun payload() =
        ProotJobBindingStore.jobPreparedPayload(
            "call",
            "turn",
            "session",
            ProotJobSpec(
                executionId = "exec-1",
                jobId = "job_0123456789ab",
                command = ProotJobCommand.Argv(listOf("true")),
                relativeWorkingDirectory = "",
                environment = emptyMap(),
                deadlineMs = 60_000L,
                maxOutputBytes = 1_024L,
                inputManifestSha256 = "a".repeat(64),
            ),
            config = null,
            detached = true,
        )
}
