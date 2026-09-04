package com.helix.runtime.proot.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeTargetDescriptorCodecTest {
    private fun sha(): String = "a1b2c3d4e5f6".repeat(5) + "a1b2"

    private fun shaTail(tail: String): String = ("f0".repeat(31) + tail).take(64)

    private fun validDescriptor(): RuntimeTargetDescriptor =
        RuntimeTargetDescriptor(
            protocolVersion = 1,
            runtimeVersion = "0.1.0",
            abi = "arm64-v8a",
            lockSha256 = sha(),
            capabilities = listOf("handshake"),
        )

    @Test
    fun `valid descriptor round-trips through encode and parse`() {
        val descriptor = validDescriptor()
        val parsed = RuntimeTargetDescriptorCodec.parse(RuntimeTargetDescriptorCodec.encode(descriptor))
        assertEquals(descriptor, parsed)
    }

    @Test
    fun `canonical encoding has the fixed key order`() {
        val encoded = RuntimeTargetDescriptorCodec.encode(validDescriptor())
        val first = encoded.indexOf("\"protocolVersion\"")
        val second = encoded.indexOf("\"runtimeVersion\"")
        val third = encoded.indexOf("\"abi\"")
        val fourth = encoded.indexOf("\"lockSha256\"")
        val fifth = encoded.indexOf("\"capabilities\"")
        assertTrue(first in 0 until second && second < third && third < fourth && fourth < fifth)
    }

    @Test
    fun `descriptor fingerprint is stable lowercase hex`() {
        val fingerprint = RuntimeTargetDescriptorCodec.sha256Hex(validDescriptor())
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(fingerprint, RuntimeTargetDescriptorCodec.sha256Hex(validDescriptor()))
    }

    @Test
    fun `unknown key is rejected`() {
        val doc = """{"protocolVersion":1,"runtimeVersion":"0.1.0","abi":"arm64-v8a","lockSha256":"${shaTail(
            "9c",
        )}","capabilities":["handshake"],"evil":true}"""
        assertFailsClosed { RuntimeTargetDescriptorCodec.parse(doc) }
    }

    @Test
    fun `missing key is rejected`() {
        val doc = """{"protocolVersion":1,"runtimeVersion":"0.1.0","abi":"arm64-v8a","capabilities":["handshake"]}"""
        assertFailsClosed { RuntimeTargetDescriptorCodec.parse(doc) }
    }

    @Test
    fun `non-object document is rejected`() {
        assertFailsClosed { RuntimeTargetDescriptorCodec.parse("[1,2,3]") }
    }

    @Test
    fun `malformed json is rejected`() {
        assertFailsClosed { RuntimeTargetDescriptorCodec.parse("{not json") }
    }

    @Test
    fun `capabilities that are not an array are rejected`() {
        val doc = """{"protocolVersion":1,"runtimeVersion":"0.1.0","abi":"arm64-v8a","lockSha256":"${shaTail(
            "9c",
        )}","capabilities":{"handshake":true}}"""
        assertFailsClosed { RuntimeTargetDescriptorCodec.parse(doc) }
    }

    @Test
    fun `protocol version that is not an integer is rejected`() {
        val doc = """{"protocolVersion":1.5,"runtimeVersion":"0.1.0","abi":"arm64-v8a","lockSha256":"${shaTail(
            "9c",
        )}","capabilities":["handshake"]}"""
        assertFailsClosed { RuntimeTargetDescriptorCodec.parse(doc) }
    }

    @Test
    fun `protocol version overflowing int is rejected`() {
        val doc = """{"protocolVersion":3000000000,"runtimeVersion":"0.1.0","abi":"arm64-v8a","lockSha256":"${shaTail(
            "9c",
        )}","capabilities":["handshake"]}"""
        assertFailsClosed { RuntimeTargetDescriptorCodec.parse(doc) }
    }

    @Test
    fun `protocol version zero is rejected`() {
        assertFailsClosed {
            RuntimeTargetDescriptor(0, "0.1.0", "arm64-v8a", sha(), listOf("handshake"))
        }
    }

    @Test
    fun `blank runtime version is rejected`() {
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "  ", "arm64-v8a", sha(), listOf("handshake"))
        }
    }

    @Test
    fun `abi outside the closed set is rejected`() {
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "0.1.0", "riscv64", sha(), listOf("handshake"))
        }
    }

    @Test
    fun `uppercase lock fingerprint is rejected`() {
        val upper = shaTail("9c").uppercase()
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "0.1.0", "arm64-v8a", upper, listOf("handshake"))
        }
    }

    @Test
    fun `short lock fingerprint is rejected`() {
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "0.1.0", "arm64-v8a", "ab12", listOf("handshake"))
        }
    }

    @Test
    fun `non-hex lock fingerprint is rejected`() {
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "0.1.0", "arm64-v8a", "z".repeat(64), listOf("handshake"))
        }
    }

    @Test
    fun `empty capabilities are rejected`() {
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "0.1.0", "arm64-v8a", shaTail("9c"), emptyList())
        }
    }

    @Test
    fun `duplicated capabilities are rejected`() {
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "0.1.0", "arm64-v8a", shaTail("9c"), listOf("handshake", "handshake"))
        }
    }

    @Test
    fun `unknown capability is rejected`() {
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "0.1.0", "arm64-v8a", shaTail("9c"), listOf("handshake", "teleport"))
        }
    }

    @Test
    fun `descriptor without only-handshake capability is rejected`() {
        // "jobs" is not in the closed set yet (HXA-084): the only valid list today
        // is exactly ["handshake"]; anything else fails domain validation.
        assertFailsClosed {
            RuntimeTargetDescriptor(1, "0.1.0", "arm64-v8a", shaTail("9c"), listOf("jobs"))
        }
    }

    @Test
    fun `the x86_64 abi is accepted by the schema`() {
        val descriptor =
            RuntimeTargetDescriptor(1, "0.1.0", "x86_64", sha(), listOf("handshake"))
        assertEquals("x86_64", RuntimeTargetDescriptorCodec.parse(RuntimeTargetDescriptorCodec.encode(descriptor)).abi)
    }

    // Codec violations surface as ProotIpcException; constructor domain violations as
    // IllegalArgumentException. Both are fail-closed; the test only requires a
    // message-bearing throw, never a silent default.
    private fun assertFailsClosed(block: () -> Unit) {
        try {
            block()
            fail("expected a fail-closed exception")
        } catch (e: ProotIpcException) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        }
    }
}
