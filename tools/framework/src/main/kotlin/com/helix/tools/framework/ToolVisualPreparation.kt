package com.helix.tools.framework

import com.helix.core.model.VisualArtifact

/** Host-only image preparation after normal tool admission. This never authorizes model egress. */
fun interface ToolVisualPreparation {
    fun prepare(
        call: ExecutableToolCall,
        reference: String,
        expectedSha256: String?,
    ): VisualArtifact
}

/** Stable public reason; never includes file contents, credentials or raw filesystem paths. */
class VisualPreparationException(
    val code: String,
) : IllegalArgumentException(code)
