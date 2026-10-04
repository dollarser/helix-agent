package com.helix.app.vision

import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionCapabilityResolverTest {
    @Test
    fun confirmedVisionDoesNotProbeAgain() =
        runBlocking {
            var probes = 0
            val resolver =
                VisionCapabilityResolver(
                    capabilities = { _, _ -> caps(true, CapabilitySource.PROBED) },
                    probe = { _, _ ->
                        probes++
                        ProbeOutcome.Ok(caps(true, CapabilitySource.PROBED), null)
                    },
                )
            assertTrue(resolver.available("p", "m"))
            assertEquals(0, probes)
        }

    @Test
    fun connectionOnlyCapabilityIsProbedLazily() =
        runBlocking {
            var probes = 0
            val resolver =
                VisionCapabilityResolver(
                    capabilities = { _, _ -> caps(false, CapabilitySource.CONNECTION_ONLY) },
                    probe = { _, _ ->
                        probes++
                        ProbeOutcome.Ok(caps(true, CapabilitySource.PROBED), null)
                    },
                )
            assertTrue(resolver.available("p", "m"))
            assertEquals(1, probes)
        }

    @Test
    fun explicitOrProbedFalseVisionIsNotRetried() =
        runBlocking {
            for (source in listOf(CapabilitySource.PROBED, CapabilitySource.MANUAL)) {
                var probes = 0
                val resolver =
                    VisionCapabilityResolver(
                        capabilities = { _, _ -> caps(false, source) },
                        probe = { _, _ ->
                            probes++
                            ProbeOutcome.Ok(caps(true, CapabilitySource.PROBED), null)
                        },
                    )
                assertFalse(resolver.available("p", "m"))
                assertEquals(source.name, 0, probes)
            }
        }

    @Test
    fun failedLazyProbeKeepsVisionUnavailable() =
        runBlocking {
            val resolver =
                VisionCapabilityResolver(
                    capabilities = { _, _ -> null },
                    probe = { _, _ ->
                        ProbeOutcome.Failed(
                            5,
                            ModelErrorCode.PROTOCOL,
                            "vision probe failed",
                            false,
                        )
                    },
                )
            assertFalse(resolver.available("p", "m"))
        }

    @Test
    fun connectionOnlyCannotAssertVisionWithoutProbingTheExactModel() =
        runBlocking {
            val resolver =
                VisionCapabilityResolver(
                    capabilities = { _, _ -> caps(true, CapabilitySource.CONNECTION_ONLY) },
                    probe = { provider, model ->
                        assertEquals("selected-provider", provider)
                        assertEquals("selected-model", model)
                        ProbeOutcome.Ok(caps(false, CapabilitySource.PROBED), null)
                    },
                )
            assertFalse(resolver.available("selected-provider", "selected-model"))
        }

    @Test(expected = CancellationException::class)
    fun cancellationEscapesTheLazyProbe() =
        runBlocking {
            VisionCapabilityResolver(
                capabilities = { _, _ -> null },
                probe = { _, _ -> throw CancellationException("cancelled") },
            ).available("p", "m")
            Unit
        }

    @Test
    fun probeExceptionDoesNotClaimVisionSupport() =
        runBlocking {
            val resolver =
                VisionCapabilityResolver(
                    capabilities = { _, _ -> null },
                    probe = { _, _ -> error("transport unavailable") },
                )
            assertFalse(resolver.available("p", "m"))
        }

    private fun caps(
        vision: Boolean,
        source: CapabilitySource,
    ) = ProviderCapabilities(
        streaming = true,
        toolCalls = true,
        parallelToolCalls = false,
        vision = vision,
        reasoning = false,
        jsonSchemaOutput = false,
        maxContextTokens = null,
        source = source,
    )
}
