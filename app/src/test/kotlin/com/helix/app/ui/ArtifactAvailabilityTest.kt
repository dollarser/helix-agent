package com.helix.app.ui

import com.helix.app.chat.ArtifactRowUi
import com.helix.app.files.FileManagerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-203: the pure artifact-availability decisions — the "content changed since the task
 * ran" verdict must fire ONLY on a positive size or verifiable-hash mismatch (a hash the
 * facade could not compute is not claimed as a change), and the scope parse must fail to
 * null (honest unavailable), never throw, on a malformed reference.
 */
class ArtifactAvailabilityTest {
    private fun row(
        relativePath: String = "scope:workspace:output/report.txt",
        sizeBytes: Long = 100L,
        sha256: String? = "a".repeat(64),
        isSafScope: Boolean = false,
    ) = ArtifactRowUi(
        id = "art-1",
        sessionId = "s-1",
        relativePath = relativePath,
        fileName = "report.txt",
        mediaType = "text/plain",
        sizeBytes = sizeBytes,
        sha256 = sha256,
        turnId = "t-1",
        sessionTitle = "Task session",
        isSafScope = isSafScope,
    )

    private fun meta(
        sizeBytes: Long = 100L,
        sha256: String? = "a".repeat(64),
    ) = FileManagerService.FileMeta(
        sizeBytes = sizeBytes,
        mtimeEpochMillis = 1L,
        mimeType = "text/plain",
        isText = true,
        sha256 = sha256,
        hashOmittedBecauseTooLarge = sha256 == null,
    )

    @Test
    fun unchangedFileIsNotReportedAsChanged() {
        assertFalse(artifactAvailabilityChanged(row(), meta()))
    }

    @Test
    fun sizeMismatchIsAChange() {
        assertTrue(artifactAvailabilityChanged(row(sizeBytes = 100L), meta(sizeBytes = 101L)))
        assertTrue(artifactAvailabilityChanged(row(sizeBytes = 101L), meta(sizeBytes = 100L)))
    }

    @Test
    fun verifiableHashMismatchIsAChange() {
        assertTrue(
            artifactAvailabilityChanged(
                row(sha256 = "a".repeat(64)),
                meta(sha256 = "b".repeat(64)),
            ),
        )
    }

    @Test
    fun unverifiableHashIsNotClaimedAsAChange() {
        // The real file's hash is unknown (too large for hashing / I/O): only the size can
        // decide. Same size, unknown hash — NO change claim.
        assertFalse(
            artifactAvailabilityChanged(row(sha256 = "a".repeat(64)), meta(sha256 = null)),
        )
        assertFalse(artifactAvailabilityChanged(row(sha256 = null), meta(sha256 = "a".repeat(64))))
        assertFalse(artifactAvailabilityChanged(row(sha256 = null), meta(sha256 = null)))
    }

    @Test
    fun sizeMismatchStillWinsWhenHashIsUnknown() {
        assertTrue(artifactAvailabilityChanged(row(sizeBytes = 100L), meta(sizeBytes = 99L, sha256 = null)))
    }

    @Test
    fun wellFormedModelReferenceParsesItsScope() {
        assertEquals("workspace", row().parsedScopePath()?.scopeId)
        assertEquals(
            "output/report.txt",
            row().parsedScopePath()?.relativePath,
        )
        assertEquals(
            "saf-abc123def456",
            row(relativePath = "scope:saf-abc123def456:note.txt").parsedScopePath()?.scopeId,
        )
    }

    @Test
    fun malformedModelReferenceParsesToNullInsteadOfThrowing() {
        assertNull(row(relativePath = "output/report.txt").parsedScopePath())
        assertNull(row(relativePath = "scope:").parsedScopePath())
    }
}
