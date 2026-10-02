package com.helix.provider.api

import com.helix.core.model.NormalizedEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CleartextWarningTest {
    @Test fun httpsNeedsNoWarningAndIsNotRewritten() {
        val endpoint = NormalizedEndpoint.parse("https://example.test/v1")
        assertNull(CleartextWarning.forEndpoint(endpoint))
        assertEquals("https://example.test:443/v1", endpoint.full)
    }

    @Test fun httpTargetsProduceFactsWithoutAnAuthorizationStore() {
        for (url in listOf("http://127.0.0.1:8000/v1", "http://192.168.1.50:11434/v1", "http://example.test/v1")) {
            val endpoint = NormalizedEndpoint.parse(url)
            assertEquals(CleartextWarning(endpoint.host, endpoint.port), CleartextWarning.forEndpoint(endpoint))
        }
    }

    @Test fun portAndIpv6ChangesUpdateTheWarningInsteadOfBlocking() {
        for (url in listOf("http://[fd00::1]:30000/v1", "http://[fd00::2]:30001/v1", "http://example.test:81/v1")) {
            val endpoint = NormalizedEndpoint.parse(url)
            assertEquals(CleartextWarning(endpoint.host, endpoint.port), CleartextWarning.forEndpoint(endpoint))
        }
    }

    @Test fun malformedDisplayFactsAreNotAccepted() {
        assertThrows(IllegalArgumentException::class.java) { CleartextWarning("", 80) }
        assertThrows(IllegalArgumentException::class.java) { CleartextWarning("example.test", 0) }
        assertThrows(IllegalArgumentException::class.java) { CleartextWarning("example.test", 65536) }
    }
}
