package com.helix.app.proot

import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.ipc.ProotIpcException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProotArtifactReferencesTest {
    @Test
    fun boundsLargeSummariesWithoutHidingCount() {
        val entries = (0 until 100).map { JobManifestEntry("output/frame-$it.png", "a".repeat(64), 10) }
        val summary = ProotArtifactReferences.summary("job_001122334455", entries)
        assertEquals("100", summary.getValue("artifactCount").jsonPrimitive.content)
        assertEquals("true", summary.getValue("artifactsTruncated").jsonPrimitive.content)
        assertEquals(64, summary.getValue("artifacts").jsonArray.size)
    }

    @Test
    fun doesNotTreatInputsAsProducedFiles() {
        val summary =
            ProotArtifactReferences.summary(
                "job_001122334455",
                listOf(JobManifestEntry("input.mp4", "a".repeat(64), 10)),
            )
        assertEquals("0", summary.getValue("artifactCount").jsonPrimitive.content)
        assertEquals("false", summary.getValue("artifactsTruncated").jsonPrimitive.content)
    }

    @Test
    fun invalidJobIdentityCannotCreateOutputPath() {
        assertThrows(ProotIpcException::class.java) { ProotProducedFiles.relativeDirectory("job_media") }
    }
}
