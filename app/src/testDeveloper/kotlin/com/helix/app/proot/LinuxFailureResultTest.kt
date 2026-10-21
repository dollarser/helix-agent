package com.helix.app.proot

import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxFailureResultTest {
    @Test fun unavailableArchiveKeepsOriginalTerminalFactsAndIdentity() {
        val record =
            ProotJobRecord(
                "job_0123456789ab",
                "exec",
                "a".repeat(64),
                ProotJobState.CANCELLED,
                1,
                terminalAtEpochMs = 2,
                exitCode = 137,
                truncated = true,
            )
        val result = terminalFailure(record, "captured archive unavailable", "OUTPUT_MISSING")
        val fields = Json.parseToJsonElement(result.detail).jsonObject
        assertEquals("CANCELLED", fields.getValue("state").jsonPrimitive.content)
        assertEquals("137", fields.getValue("exitCode").jsonPrimitive.content)
        assertEquals(record.jobId, fields.getValue("jobId").jsonPrimitive.content)
        assertEquals("OUTPUT_MISSING", fields.getValue("deliveryErrorCode").jsonPrimitive.content)
        assertEquals(true, result.requiresReview)
        assertEquals(false, result.sideEffectFree)
    }
}
