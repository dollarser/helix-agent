package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Test

class CliRuntimeStatusTest {
    private val valid = """{"protocolVersion":1,"runtimeVersion":"0.1.0","abi":"arm64-v8a","lockSha256":"${"a".repeat(
        64,
    )}","agentBackendState":"NOT_REGISTERED"}"""

    @Test fun validStatusDecodes() {
        assertEquals("0.1.0", CliRuntimeStatusCodec.decode(valid).runtimeVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun protocolMismatchFailsClosed() {
        CliRuntimeStatusCodec.decode(valid.replace("\"protocolVersion\":1", "\"protocolVersion\":2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun registeredBackendCannotBeClaimedBeforeModelJobHxa() {
        CliRuntimeStatusCodec.decode(valid.replace("NOT_REGISTERED", "REGISTERED"))
    }
}
