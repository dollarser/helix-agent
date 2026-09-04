@file:Suppress(
    "EmptyCatchBlock", // the empty catch IS the assertion: the fail() before it proves the throw
    "SwallowedException", // same idiom; nothing to preserve from an expected rejection
    "LongMethod", // one field-coherence scenario suite, not to be fragmented
)

package com.helix.runtime.proot.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HXA-084: the journal record IS the reconciliation proof, so its codec must be
 * strict on every side — unknown keys, a tampered terminalCommit, and state/field
 * incoherence all fail closed, and the commit is stable across encode/parse.
 */
class ProotJobRecordTest {
    private val inputHash = "0".repeat(64)

    private fun pending(): ProotJobRecord =
        ProotJobRecord(
            jobId = "job_001122334455",
            executionId = "exec-1",
            inputManifestSha256 = inputHash,
            state = ProotJobState.PENDING,
            createdAtEpochMs = 1_000L,
        )

    private fun succeeded(): ProotJobRecord =
        ProotJobRecord(
            jobId = "job_001122334455",
            executionId = "exec-1",
            inputManifestSha256 = inputHash,
            state = ProotJobState.SUCCEEDED,
            createdAtEpochMs = 1_000L,
            terminalAtEpochMs = 2_000L,
            exitCode = 0,
            stdoutBytes = 42L,
            stderrBytes = 7L,
            truncated = false,
            outputManifestSha256 = "f".repeat(64),
        )

    @Test
    fun aRecordRoundTripsThroughTheCodec() {
        assertEquals(pending(), ProotJobRecordCodec.parse(ProotJobRecordCodec.encode(pending())))
        val encoded = ProotJobRecordCodec.encode(succeeded())
        val parsed = ProotJobRecordCodec.parse(encoded)
        assertEquals(succeeded(), parsed)
        assertEquals(succeeded().terminalCommit, parsed.terminalCommit)
    }

    @Test
    fun theTerminalCommitIsStableAndStateDependent() {
        assertNull(pending().terminalCommit)
        val a = succeeded()
        // Same outcome, different bookkeeping -> same commit.
        val reconciled = a.copy(reconciledAtEpochMs = 9_999L)
        assertEquals(a.terminalCommit, reconciled.terminalCommit)
        // A different outcome -> different commit.
        val other = a.copy(stdoutBytes = 43L)
        assertTrue(a.terminalCommit != other.terminalCommit)
        val reEncodedCommit = ProotJobRecordCodec.parse(ProotJobRecordCodec.encode(a)).terminalCommit
        assertEquals(a.terminalCommit, reEncodedCommit)
    }

    @Test
    fun aTamperedTerminalCommitIsRejected() {
        val document =
            ProotJobRecordCodec
                .encode(succeeded())
                .replace(
                    "\"terminalCommit\":\"${succeeded().terminalCommit}\"",
                    "\"terminalCommit\":\"${"a".repeat(64)}\"",
                )
        try {
            ProotJobRecordCodec.parse(document)
            fail("tampered commit accepted")
        } catch (e: ProotIpcException) {
        }
    }

    @Test
    fun unknownAndMissingKeysAreRejected() {
        val base = ProotJobRecordCodec.encode(pending())
        val unknown = base.replace("}", ",\"extra\":1}")
        try {
            ProotJobRecordCodec.parse(unknown)
            fail("unknown key accepted")
        } catch (e: ProotIpcException) {
        }
        val missing = base.replace("\"truncated\":false", "")
        try {
            ProotJobRecordCodec.parse(missing)
            fail("missing key accepted")
        } catch (e: ProotIpcException) {
        }
    }

    @Test
    fun stateAndFieldCoherenceIsEnforced() {
        // A terminal record without terminalAtEpochMs.
        val document = ProotJobRecordCodec.encode(succeeded())
        val noTerminalAt = document.replace("\"terminalAtEpochMs\":2000,", "")
        try {
            ProotJobRecordCodec.parse(noTerminalAt)
            fail("terminal without terminalAt accepted")
        } catch (e: ProotIpcException) {
        }
        // SUCCEEDED without an exitCode.
        val noExit = ProotJobRecordCodec.encode(succeeded()).replace("\"exitCode\":0,", "")
        try {
            ProotJobRecordCodec.parse(noExit)
            fail("SUCCEEDED without exitCode accepted")
        } catch (e: ProotIpcException) {
        }
        // ORPHANED carries no exit or output.
        val orphaned =
            ProotJobRecord(
                jobId = "job_001122334455",
                executionId = "exec-1",
                inputManifestSha256 = inputHash,
                state = ProotJobState.ORPHANED,
                createdAtEpochMs = 1_000L,
                terminalAtEpochMs = 2_000L,
            )
        ProotJobRecordCodec.parse(ProotJobRecordCodec.encode(orphaned))
        try {
            ProotJobRecord(
                "job_001122334455",
                "exec-1",
                inputHash,
                ProotJobState.ORPHANED,
                1_000L,
                2_000L,
                exitCode = 137,
            )
            fail("ORPHANED with exitCode accepted")
        } catch (e: IllegalArgumentException) {
        }
        // Non-terminal carries no terminal bookkeeping.
        try {
            ProotJobRecord(
                "job_001122334455",
                "exec-1",
                inputHash,
                ProotJobState.RUNNING,
                1_000L,
                terminalAtEpochMs = 2_000L,
            )
            fail("RUNNING with terminalAt accepted")
        } catch (e: IllegalArgumentException) {
        }
        // The wire document of a PENDING record carries no terminalCommit key at all.
        assertTrue(ProotJobRecordCodec.encode(pending()).indexOf("terminalCommit") == -1)
        // A wire document that adds terminalCommit to PENDING is rejected.
        val withCommit =
            ProotJobRecordCodec
                .encode(pending())
                .replace("}", ",\"terminalCommit\":\"${"a".repeat(64)}\"}")
        try {
            ProotJobRecordCodec.parse(withCommit)
            fail("PENDING with commit accepted")
        } catch (e: ProotIpcException) {
        }
    }

    @Test
    fun identifiersAreValidatedOnBothSides() {
        try {
            ProotJobRecord("job_ZZ", "exec-1", inputHash, ProotJobState.PENDING, 1L)
            fail("bad jobId accepted")
        } catch (e: ProotIpcException) {
        }
        try {
            ProotJobRecord("job_001122334455", "has space", inputHash, ProotJobState.PENDING, 1L)
            fail("bad executionId accepted")
        } catch (e: ProotIpcException) {
        }
        try {
            ProotJobRecord("job_001122334455", "e".repeat(129), inputHash, ProotJobState.PENDING, 1L)
            fail("long executionId accepted")
        } catch (e: ProotIpcException) {
        }
        ProotJobRecord("job_001122334455", "exec-1_ok", inputHash, ProotJobState.PENDING, 1L)
    }
}
