package com.helix.app.proot

import com.helix.runtime.proot.core.JobManifestEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A bounded result view; all produced files remain registered even if the model summary is truncated. */
internal object ProotArtifactReferences {
    private const val MAX_ITEMS = 64

    fun summary(
        jobId: String,
        entries: List<JobManifestEntry>,
    ) = buildJsonObject {
        val outputs = entries.filter { it.path.startsWith("output/") }
        val directory = "scope:app:${ProotProducedFiles.relativeDirectory(jobId)}"
        put("artifactDirectory", directory)
        put("artifactCount", outputs.size)
        put("artifactsTruncated", outputs.size > MAX_ITEMS)
        put(
            "artifacts",
            JsonArray(
                outputs.take(MAX_ITEMS).map { entry ->
                    buildJsonObject {
                        put("path", "$directory/${entry.path.removePrefix("output/")}")
                        put("sizeBytes", entry.size)
                        put("sha256", entry.sha256)
                    }
                },
            ),
        )
    }
}
