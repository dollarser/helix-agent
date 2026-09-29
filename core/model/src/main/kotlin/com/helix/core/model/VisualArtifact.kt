package com.helix.core.model

/** Trusted file-backed tool observation; image bytes never enter tool or history JSON. */
data class VisualArtifact(
    val artifactId: String,
    val sha256: String,
    val mediaType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
) {
    init {
        require(artifactId.isNotBlank())
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" })
        require(mediaType in ImageReference.MEDIA_TYPES)
        require(sizeBytes in 1..VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong())
        require(VisionLimits.normalizedEdgeFits(width, height))
    }
}

/** Request-local provenance, rechecked against durable relations at byte materialization. */
data class ImageBinding(
    val sessionId: String,
    val messageId: String,
    val sha256: String,
    val turnId: String? = null,
    val modelId: String? = null,
    val consentId: String? = null,
) {
    init {
        require(sessionId.isNotBlank() && messageId.isNotBlank())
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" })
        require(consentId == null || !modelId.isNullOrBlank())
        require(modelId == null || !turnId.isNullOrBlank())
    }
}
