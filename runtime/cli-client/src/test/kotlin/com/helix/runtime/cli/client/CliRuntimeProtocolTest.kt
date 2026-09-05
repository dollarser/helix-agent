package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliRuntimeProtocolTest {
    @Test fun sharedHandshakeContractIsBoundedAndExplicit() {
        assertEquals("com.helix.runtime.cli", CliRuntimeProtocol.RUNTIME_PACKAGE)
        assertEquals("com.helix.runtime.cli.app.CliRuntimeService", CliRuntimeProtocol.SERVICE_CLASS)
        assertEquals("com.helix.permission.BIND_CLI_RUNTIME", CliRuntimeProtocol.PERMISSION)
        assertTrue(CliRuntimeProtocol.MAX_STATUS_BYTES in 1..64 * 1024)
        assertTrue(CliRuntimeProtocol.BIND_DEADLINE_MS in 1..30_000)
    }
}
