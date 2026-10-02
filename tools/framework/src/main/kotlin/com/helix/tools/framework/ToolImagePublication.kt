package com.helix.tools.framework

import com.helix.core.model.VisualArtifact

/** Trusted screenshot producers use the existing host-owned artifact and vision pipeline. */
fun interface ToolImagePublication {
    fun publish(
        call: ExecutableToolCall,
        png: ByteArray,
        acquisitionScopeRef: String?,
    ): PublishedToolImage
}

data class PublishedToolImage(
    val reference: String,
    val sha256: String,
    val sizeBytes: Long,
    val visual: VisualArtifact?,
    val note: String = "",
)
