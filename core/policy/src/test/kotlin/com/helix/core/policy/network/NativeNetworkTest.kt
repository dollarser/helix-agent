package com.helix.core.policy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeNetworkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun independentReadersSeeDurableUpdatesAndRemoval() {
        val previous = NativeNetwork.settings
        try {
            NativeNetwork.settings = null
            val writer = NativeNetwork.initialize(temporary.root)
            NativeNetwork.settings = null
            val reader = NativeNetwork.initialize(temporary.root)
            assertNull(reader.lookup("dns.invalid"))
            writer.save("127.0.0.1 dns.invalid\n::1 dns.invalid")
            assertEquals(2, NativeNetwork.resolve("dns.invalid").size)
            writer.save("192.0.2.1 dns.invalid")
            assertEquals("192.0.2.1", reader.lookup("dns.invalid")!!.single().hostAddress)
            assertThrows(IllegalArgumentException::class.java) { writer.save("not-an-ip dns.invalid") }
            assertEquals("192.0.2.1", reader.lookup("dns.invalid")!!.single().hostAddress)
            writer.save("")
            assertNull(reader.lookup("dns.invalid"))
            assertEquals(
                16,
                NativeNetwork
                    .resolve("::1")
                    .single()
                    .address.size,
            )
        } finally {
            NativeNetwork.settings = previous
        }
    }
}
