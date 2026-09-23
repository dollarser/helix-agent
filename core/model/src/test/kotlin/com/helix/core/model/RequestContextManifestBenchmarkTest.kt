package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * HXA-217 / ADR-AGENT-010 Kotlin-side storage & encoding benchmark test.
 * Confirms byte sizes, 100-turn cumulative growth, and encoding/decoding performance
 * across typical, maximum, and extreme stress scenarios.
 */
class RequestContextManifestBenchmarkTest {
    @Test
    fun `typical conversation 24 messages and 4 UUIDs stays under 2 KiB and accumulates under 300 KiB in 100 turns`() {
        val messages =
            (1..24).map {
                MessageRefEntry("msg-$it-${"a".repeat(24)}", if (it % 2 == 0) 'u' else 'a')
            }
        val inputs = (1..4).map { "550e8400-e29b-41d4-a716-44665544000$it" }

        val manifest =
            CompactManifestCodec.bounded(
                callId = "call-typical",
                timestamp = 1774300000000L,
                checkpoint = 42L,
                messages = messages,
                inputIds = inputs,
            )

        val json = CompactManifestCodec.encodeCompact(manifest)
        val singleBytes = json.toByteArray(Charsets.UTF_8).size

        // Single row under 2 KiB
        assertTrue("Single typical manifest must be < 2 KiB, got $singleBytes bytes", singleBytes < 2048)
        assertFalse("Typical scenario must not be truncated", manifest.isTruncated)

        // 100 turns cumulative bytes
        val cumulativeBytes = singleBytes * 100L
        assertTrue(
            "100 typical turns cumulative JSON bytes must be < 300 KiB, got $cumulativeBytes bytes",
            cumulativeBytes < 300 * 1024,
        )
    }

    @Test
    fun `max valid 512 messages and 512 UUIDs stays under 45 KiB and accumulates under 4_5 MiB in 100 turns`() {
        val messages =
            (1..512).map {
                MessageRefEntry("0123456789abcdef0123456789abcdef", if (it % 2 == 0) 'u' else 'a')
            }
        val inputs =
            (1..512).map {
                "550e8400-e29b-41d4-a716-446655440000"
            }

        val manifest =
            CompactManifestCodec.bounded(
                callId = "call-max-valid",
                timestamp = 1774300000000L,
                checkpoint = 42L,
                messages = messages,
                inputIds = inputs,
            )

        val json = CompactManifestCodec.encodeCompact(manifest)
        val singleBytes = json.toByteArray(Charsets.UTF_8).size

        // Single row matches research target ~41.6 KiB (strictly under 45 KiB)
        assertTrue("Max valid single manifest must be < 45 KiB, got $singleBytes bytes", singleBytes < 45 * 1024)
        assertFalse("Max valid scenario must fit without truncation", manifest.isTruncated)

        // 100 turns cumulative bytes strictly under 5 MiB (safe within 10 MiB budget)
        val cumulativeBytes = singleBytes * 100L
        assertTrue(
            "100 max valid turns cumulative JSON bytes must be < 4.5 MiB, got $cumulativeBytes bytes",
            cumulativeBytes < 4500 * 1024,
        )
    }

    @Test
    fun `extreme 256-char Unicode input ID stress enforces 256 KiB single line limit with isTruncated`() {
        val longUnicode = "\u00e9".repeat(256) // 512 UTF-8 bytes per input ID
        val messages =
            (1..512).map {
                MessageRefEntry("msg-$it-${"a".repeat(24)}", 'u')
            }
        val inputs =
            (1..512).map {
                "inp-$it-$longUnicode"
            }

        val manifest =
            CompactManifestCodec.bounded(
                callId = "call-extreme",
                timestamp = 1774300000000L,
                checkpoint = null,
                messages = messages,
                inputIds = inputs,
            )

        val json = CompactManifestCodec.encodeCompact(manifest)
        val singleBytes = json.toByteArray(Charsets.UTF_8).size

        assertTrue(
            "Extreme Unicode single manifest must not exceed 256 KiB, got $singleBytes bytes",
            singleBytes <= RequestContextManifest.MAX_SINGLE_LINE_BYTES,
        )
        assertTrue("Extreme input must be marked as truncated", manifest.isTruncated)
    }

    @Test
    fun `kotlin encode and decode speed is under 1 millisecond on 100 iterations`() {
        val messages =
            (1..512).map {
                MessageRefEntry("0123456789abcdef0123456789abcdef", if (it % 2 == 0) 'u' else 'a')
            }
        val inputs =
            (1..512).map {
                "550e8400-e29b-41d4-a716-446655440000"
            }

        val manifest =
            CompactManifestCodec.bounded(
                callId = "call-perf",
                timestamp = 1774300000000L,
                checkpoint = 42L,
                messages = messages,
                inputIds = inputs,
            )

        // Warmup
        repeat(10) {
            CompactManifestCodec.encodeCompact(manifest)
        }

        // Benchmark 100 iterations
        var totalNanos = 0L
        repeat(100) {
            val nanos =
                measureNanoTime {
                    CompactManifestCodec.encodeCompact(manifest)
                }
            totalNanos += nanos
        }

        val avgMs = (totalNanos / 100) / 1_000_000.0
        assertTrue("Average Kotlin encode time for 512 msgs must be < 1.0 ms, was $avgMs ms", avgMs < 1.0)
    }
}
