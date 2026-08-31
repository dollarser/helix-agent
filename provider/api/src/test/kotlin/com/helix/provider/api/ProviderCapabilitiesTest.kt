package com.helix.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilitiesTest {
    private val probed =
        ProviderCapabilities(
            streaming = true,
            toolCalls = true,
            parallelToolCalls = false,
            vision = false,
            reasoning = false,
            jsonSchemaOutput = false,
            maxContextTokens = 131_072L,
            source = CapabilitySource.PROBED,
        )

    @Test
    fun jsonFormRoundTrips() {
        val raw = ProviderCapabilities.toJsonString(probed)
        assertEquals(probed, ProviderCapabilities.parse(raw))
    }

    @Test
    fun jsonFormIsCanonical() {
        assertEquals(
            "{\"streaming\":true,\"toolCalls\":true,\"parallelToolCalls\":false," +
                "\"vision\":false,\"reasoning\":false,\"jsonSchemaOutput\":false," +
                "\"maxContextTokens\":131072,\"source\":\"PROBED\"}",
            ProviderCapabilities.toJsonString(probed),
        )
    }

    @Test
    fun nullMaxContextTokensRoundTrips() {
        val c = probed.copy(maxContextTokens = null, source = CapabilitySource.MANUAL)
        val raw = ProviderCapabilities.toJsonString(c)
        assertTrue(raw.contains("\"maxContextTokens\":null,"))
        assertEquals(c, ProviderCapabilities.parse(raw))
    }

    @Test
    fun manualSourceRoundTrips() {
        val c = probed.withManualSource()
        assertEquals(CapabilitySource.MANUAL, c.source)
        assertEquals(probed.copy(), c.copy(source = CapabilitySource.PROBED))
        assertEquals(c, ProviderCapabilities.parse(ProviderCapabilities.toJsonString(c)))
    }

    @Test
    fun unknownSourceIsRejected() {
        val raw =
            ProviderCapabilities.toJsonString(probed).replace("\"PROBED\"", "\"GUessed\"")
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities.parse(raw) }
    }

    @Test
    fun extraKeyIsRejected() {
        val raw =
            ProviderCapabilities.toJsonString(probed).removeSuffix("}") + ",\"extra\":true}"
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities.parse(raw) }
    }

    @Test
    fun missingKeyIsRejected() {
        val raw = ProviderCapabilities.toJsonString(probed).replace("\"vision\":false,", "")
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities.parse(raw) }
    }

    @Test
    fun nonBooleanValueIsRejected() {
        val raw = ProviderCapabilities.toJsonString(probed).replace("\"streaming\":true", "\"streaming\":\"yes\"")
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities.parse(raw) }
    }

    @Test
    fun nonNumericMaxContextTokensIsRejected() {
        val raw =
            ProviderCapabilities.toJsonString(probed).replace(
                "\"maxContextTokens\":131072",
                "\"maxContextTokens\":\"131072\"",
            )
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities.parse(raw) }
    }

    @Test
    fun notJsonIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities.parse("nope") }
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities.parse("[1,2]") }
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities.parse("") }
    }

    @Test
    fun zeroMaxContextTokensIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            ProviderCapabilities(true, true, false, false, false, false, 0L, CapabilitySource.PROBED)
        }
    }

    @Test
    fun oversizeMaxContextTokensIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            ProviderCapabilities(
                true,
                true,
                false,
                false,
                false,
                false,
                ProviderCapabilities.MAX_CONTEXT_BOUND + 1,
                CapabilitySource.PROBED,
            )
        }
    }

    @Test
    fun maxContextTokensAtBoundIsAccepted() {
        ProviderCapabilities(
            true,
            true,
            false,
            false,
            false,
            false,
            ProviderCapabilities.MAX_CONTEXT_BOUND,
            CapabilitySource.PROBED,
        )
    }
}
