package com.helix.app.network

import com.helix.app.internal.InMemoryLineStore
import com.helix.app.internal.LineStore
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.policy.SsrfAddressPolicy
import com.helix.core.policy.SsrfCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScopeStoreTest {
    @Test
    fun explicitGrantPersistsAndRevokesWithoutWideningPort() {
        val storage = InMemoryLineStore()
        var advanced = false
        val store = LanScopeStore(storage) { advanced }
        assertThrows(IllegalStateException::class.java) { store.add("http://printer.local:8080") }
        advanced = true
        store.add("http://PRINTER.local:8080/")
        store.add("http://printer.local:8080")
        assertEquals(setOf(NetworkOriginScope("printer.local", 8080)), store.current())
        val reopened = LanScopeStore(storage) { true }
        assertEquals(store.current(), reopened.current())
        reopened.remove("http://printer.local:8080")
        assertTrue(LanScopeStore(storage) { true }.current().isEmpty())
    }

    @Test
    fun invalidOriginsAndCorruptStorageNeverGrant() {
        val storage = InMemoryLineStore()
        val store = LanScopeStore(storage) { true }
        listOf(
            "http://host/path",
            "http://host/?secret=1",
            "http://user:pass@host",
            "file:///tmp",
            "http://*.local",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { store.add(it) }
        }
        assertTrue(store.current().isEmpty())
        storage.setLines("lan_origins_v1", listOf("http://127.0.0.1:8080", "malformed"))
        assertTrue(LanScopeStore(storage) { true }.current().isEmpty())
    }

    @Test
    fun boundedStoreDoesNotSilentlyDropExistingGrants() {
        val store = LanScopeStore(InMemoryLineStore()) { true }
        repeat(64) { store.add("http://host$it.local:8080") }
        assertThrows(IllegalArgumentException::class.java) { store.add("http://overflow.local:8080") }
        assertEquals(64, store.current().size)
    }

    @Test
    fun persistedGrantDoesNotBypassStandardOrReservedAddressPolicy() {
        val store = LanScopeStore(InMemoryLineStore()) { true }
        store.add("http://127.0.0.1:8080")
        store.add("http://169.254.169.254:80")
        val loopback = listOf(byteArrayOf(127, 0, 0, 1))
        val endpoint = NormalizedEndpoint.parse("http://127.0.0.1:8080")
        assertTrue(
            SsrfAddressPolicy.check(
                loopback,
                SafetyProfile.ADVANCED,
                store.current(),
                endpoint,
            ) is SsrfCheckResult.Allowed,
        )
        assertTrue(
            SsrfAddressPolicy.check(
                loopback,
                SafetyProfile.STANDARD,
                store.current(),
                endpoint,
            ) is SsrfCheckResult.Denied,
        )
        assertTrue(
            SsrfAddressPolicy.check(
                loopback,
                SafetyProfile.ADVANCED,
                store.current(),
                endpoint.copy(port = 8081),
            ) is SsrfCheckResult.Denied,
        )
        assertTrue(
            SsrfAddressPolicy.check(
                listOf(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())),
                SafetyProfile.ADVANCED,
                store.current(),
                NormalizedEndpoint.parse("http://169.254.169.254"),
            ) is SsrfCheckResult.Denied,
        )
    }

    @Test
    fun failedPersistenceDoesNotPublishANewGrantOrReportRevocationSuccess() {
        val backend = InMemoryLineStore()
        var failing = false
        val storage =
            object : LineStore {
                override fun lines(key: String): List<String> = backend.lines(key)

                override fun setLines(
                    key: String,
                    lines: List<String>,
                ) {
                    check(!failing) { "fixture storage unavailable" }
                    backend.setLines(key, lines)
                }
            }
        val store = LanScopeStore(storage) { true }
        failing = true
        assertThrows(IllegalStateException::class.java) { store.add("http://127.0.0.1:8080") }
        assertTrue(store.current().isEmpty())
        failing = false
        store.add("http://127.0.0.1:8080")
        failing = true
        assertThrows(IllegalStateException::class.java) { store.remove("http://127.0.0.1:8080") }
        assertTrue(store.current().isEmpty())
    }
}
