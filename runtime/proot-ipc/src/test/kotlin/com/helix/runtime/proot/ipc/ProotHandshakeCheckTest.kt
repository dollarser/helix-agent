package com.helix.runtime.proot.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProotHandshakeCheckTest {
    private val fingerprint = "a1b2c3d4e5f6".repeat(5) + "a1b2"
    private val anchor = "f0".repeat(31) + "9c"

    private fun descriptor(
        protocolVersion: Int = ProotRuntimeProtocol.PROTOCOL_VERSION,
        abi: String = "arm64-v8a",
        lockSha256: String = fingerprint,
    ) = RuntimeTargetDescriptor(protocolVersion, "0.1.0", abi, lockSha256, listOf("handshake"))

    @Test
    fun `first verification passes without an anchor`() {
        assertNull(ProotHandshakeClient.check(descriptor(), ProotRuntimeProtocol.PROTOCOL_VERSION, "arm64-v8a", null))
    }

    @Test
    fun `matching anchor passes`() {
        assertNull(
            ProotHandshakeClient.check(
                descriptor(lockSha256 = anchor),
                ProotRuntimeProtocol.PROTOCOL_VERSION,
                "arm64-v8a",
                anchor,
            ),
        )
    }

    @Test
    fun `protocol mismatch wins`() {
        val cause =
            ProotHandshakeClient.check(
                descriptor(protocolVersion = ProotRuntimeProtocol.PROTOCOL_VERSION - 1),
                ProotRuntimeProtocol.PROTOCOL_VERSION,
                "arm64-v8a",
                anchor,
            )
        assertEquals(UnavailableCause.PROTOCOL_MISMATCH, cause)
    }

    @Test
    fun `protocol wins over abi`() {
        val cause =
            ProotHandshakeClient.check(
                descriptor(protocolVersion = ProotRuntimeProtocol.PROTOCOL_VERSION + 1, abi = "x86_64"),
                ProotRuntimeProtocol.PROTOCOL_VERSION,
                "arm64-v8a",
                anchor,
            )
        assertEquals(UnavailableCause.PROTOCOL_MISMATCH, cause)
    }

    @Test
    fun `abi mismatch is reported`() {
        val cause =
            ProotHandshakeClient.check(
                descriptor(abi = "x86_64"),
                ProotRuntimeProtocol.PROTOCOL_VERSION,
                "arm64-v8a",
                anchor,
            )
        assertEquals(UnavailableCause.ABI_MISMATCH, cause)
    }

    @Test
    fun `abi wins over lock`() {
        val cause =
            ProotHandshakeClient.check(
                descriptor(abi = "x86_64", lockSha256 = "e".repeat(64)),
                ProotRuntimeProtocol.PROTOCOL_VERSION,
                "arm64-v8a",
                anchor,
            )
        assertEquals(UnavailableCause.ABI_MISMATCH, cause)
    }

    @Test
    fun `changed lock fingerprint is a lock mismatch`() {
        val cause =
            ProotHandshakeClient.check(
                descriptor(lockSha256 = "e".repeat(64)),
                ProotRuntimeProtocol.PROTOCOL_VERSION,
                "arm64-v8a",
                anchor,
            )
        assertEquals(UnavailableCause.LOCK_MISMATCH, cause)
    }
}
