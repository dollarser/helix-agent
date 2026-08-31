package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class ToolExecutionEnvelopeTest {
    private fun sha(c: Char): Sha256 = Sha256(c.toString().repeat(64))

    private fun envelope(
        approval: ApprovalId? = ApprovalId("appr-1"),
        manifest: List<ArtifactRef> = listOf(ArtifactRef("in-1"), ArtifactRef("out-1")),
    ) = ToolExecutionEnvelope(
        protocolVersion = 1,
        executionId = ExecutionId("exec-1"),
        targetId = ExecutionTargetId("target-1"),
        toolName = ToolName("code.javascript.run"),
        toolVersion = ToolVersion(2),
        descriptorHash = sha('a'),
        inputRef = ArtifactRef("in-1"),
        inputHash = sha('b'),
        limits = ExecutionLimits(timeout = Duration.ofMillis(60_000), maxOutputBytes = 262_144),
        approvalProofRef = approval,
        correlationId = CorrelationId("corr-1"),
        artifactManifest = manifest,
    )

    @Test
    fun executionLimitsRejectNonPositiveBounds() {
        assertThrows<IllegalArgumentException> { ExecutionLimits(Duration.ZERO, 1) }
        assertThrows<IllegalArgumentException> { ExecutionLimits(Duration.ofMillis(-1), 1) }
        assertThrows<IllegalArgumentException> { ExecutionLimits(Duration.ofMillis(1), 0) }
        assertThrows<IllegalArgumentException> { ExecutionLimits(Duration.ofMillis(1), -5) }
        // The storage encoding is timeoutMillis (ADR-0001): a sub-millisecond timeout would
        // truncate to 0 on encode and be rejected on decode, so the domain value is
        // millisecond-granular by construction.
        assertThrows<IllegalArgumentException> { ExecutionLimits(Duration.ofNanos(999_999), 1) }
        assertThrows<IllegalArgumentException> { ExecutionLimits(Duration.ofMillis(1).plusNanos(1), 1) }
        assertEquals(Duration.ofMillis(1), ExecutionLimits(Duration.ofMillis(1), 1).timeout)
    }

    @Test
    fun executionLimitsRoundTrip() {
        val limits = ExecutionLimits(Duration.ofMillis(10_000), 262_144)
        assertEquals("""{"timeoutMillis":10000,"maxOutputBytes":262144}""", limits.toStorageString())
        assertEquals(limits, ExecutionLimits.parse(limits.toStorageString()))
    }

    @Test
    fun rejectsNonPositiveProtocolVersion() {
        assertThrows<IllegalArgumentException> { envelope().copy(protocolVersion = 0) }
    }

    @Test
    fun rejectsDuplicateArtifactManifestEntries() {
        assertThrows<IllegalArgumentException> { envelope(manifest = listOf(ArtifactRef("x"), ArtifactRef("x"))) }
    }

    @Test
    fun storageEncodingRoundTripsWithApproval() {
        val encoded = envelope().toStorageString()
        val parsed = ToolExecutionEnvelope.parse(encoded)
        assertEquals(envelope(), parsed)
        assertEquals(ApprovalId("appr-1"), parsed.approvalProofRef)
        assertEquals(listOf(ArtifactRef("in-1"), ArtifactRef("out-1")), parsed.artifactManifest)
        // Deterministic encoding.
        assertEquals(encoded, envelope().toStorageString())
    }

    @Test
    fun storageEncodingRoundTripsWithoutApproval() {
        val source = envelope(approval = null, manifest = emptyList())
        val encoded = source.toStorageString()
        assertTrue(
            "null approval must be encoded as JSON null",
            encoded.contains("\"approvalProofRef\":null,"),
        )
        val parsed = ToolExecutionEnvelope.parse(encoded)
        assertEquals(source, parsed)
        assertEquals(null as ApprovalId?, parsed.approvalProofRef)
        assertEquals(emptyList<ArtifactRef>(), parsed.artifactManifest)
    }

    @Test
    fun parseRejectsMalformedInput() {
        val valid = envelope().toStorageString()
        assertThrows<IllegalArgumentException> { ToolExecutionEnvelope.parse("") }
        assertThrows<IllegalArgumentException> { ToolExecutionEnvelope.parse(valid.dropLast(1) + ",\"extra\":1}") }
        // Invalid nested Sha256 (63 chars).
        val descriptorHashHex = sha('a').hex
        val corruptedHash = descriptorHashHex.dropLast(1)
        assertThrows<IllegalArgumentException> {
            ToolExecutionEnvelope.parse(valid.replace(descriptorHashHex, corruptedHash))
        }
        // Invalid ToolName in the payload.
        assertThrows<IllegalArgumentException> {
            ToolExecutionEnvelope.parse(valid.replace("\"code.javascript.run\"", "\"a..b\""))
        }
        // Wrong type for approvalProofRef.
        assertThrows<IllegalArgumentException> { ToolExecutionEnvelope.parse(valid.replace("\"appr-1\"", "123")) }
        // Corrupted limits object.
        assertThrows<IllegalArgumentException> { ToolExecutionEnvelope.parse(valid.replace("60000", "60000.5")) }
    }
}
