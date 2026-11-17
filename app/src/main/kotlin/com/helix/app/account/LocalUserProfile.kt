package com.helix.app.account

import java.io.File

/** Installation-local presentation only; never used as a cloud account or authorization identity. */
internal data class LocalUserProfile(
    val displayName: String? = null,
    val avatarFile: File? = null,
)

internal fun normalizedProfileName(input: String): String? {
    val value = input.trim()
    require(value.codePointCount(0, value.length) <= 40) { "Name must be at most 40 characters" }
    require(value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
        "Name must be a single line"
    }
    return value.takeIf(String::isNotEmpty)
}
