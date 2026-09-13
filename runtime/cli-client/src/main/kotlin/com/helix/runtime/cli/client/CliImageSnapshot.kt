package com.helix.runtime.cli.client

import com.helix.core.model.ImageReference
import com.helix.core.model.VisionLimits
import java.util.Base64

/** Immutable normalized bytes from the app's existing session-bound resolver; never a URI to open. */
data class CliImageSnapshot(
    val reference: ImageReference,
    val base64: String,
) {
    init {
        require(base64.length in 1..VisionLimits.MAX_BASE64_PER_IMAGE_BYTES)
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.size in 1..VisionLimits.MAX_NORMALIZED_RAW_BYTES)
        val valid =
            when (reference.mediaType) {
                "image/png" -> {
                    bytes.size >= 8 &&
                        bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
                }

                "image/jpeg" -> {
                    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                        bytes[2] == 0xff.toByte()
                }

                "image/webp" -> {
                    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
                }

                else -> {
                    false
                }
            }
        require(valid) { "image snapshot MIME mismatch" }
    }
}
