package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CliModelJobRecordTest {
    @Test fun terminalRecordRoundTrips() {
        val record = CliModelJobRecord(
            "job_0123456789ab",
            CliRuntimeProtocol.FIXED_CODEX_SMOKE_SHA256,
            CliModelJobState.SUCCEEDED,
            10,
            20,
            "gpt-test",
            "a".repeat(64),
        )
        assertEquals(record, CliModelJobRecordCodec.decode(CliModelJobRecordCodec.encode(record)))
    }

    @Test fun unknownFieldFailsClosed() {
        val json = """{"version":1,"jobId":"job_0123456789ab","requestSha256":"${"a".repeat(64)}","state":"RUNNING","createdAtEpochMillis":10,"token":"secret"}"""
        assertThrows(IllegalArgumentException::class.java) { CliModelJobRecordCodec.decode(json) }
    }

    @Test fun successWithoutProofFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            CliModelJobRecord("job_0123456789ab", "a".repeat(64), CliModelJobState.SUCCEEDED, 10, 20)
        }
    }
}
