package com.helix.app.vision

import com.helix.core.model.VisionLimits

/** Selection never loads old images. The global byte budget includes user reference images. */
internal object ToolImageWindow {
    const val RECENT_IMAGES = 2

    fun select(
        currentTurnId: String?,
        candidateTurns: List<String?>,
    ): Set<Int> =
        if (currentTurnId == null) {
            emptySet()
        } else {
            candidateTurns.indices
                .filter { candidateTurns[it] == currentTurnId }
                .takeLast(RECENT_IMAGES)
                .toSet()
        }

    fun base64Bytes(rawBytes: Long): Long {
        require(rawBytes in 0..VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong()) { "Invalid image byte count" }
        return ((rawBytes + 2) / 3) * 4
    }

    fun remaining(referenceBytes: List<Long>): Long {
        val used = referenceBytes.fold(0L) { sum, bytes -> Math.addExact(sum, base64Bytes(bytes)) }
        return (VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES - used).also {
            require(it >= 0) { "Reference images exceed request budget" }
        }
    }
}
