package com.helix.app.account

import java.text.Normalizer

/** Matches the gateway's NFC / Unicode category C / code-point contract. */
internal fun normalizedDisplayName(input: String): String {
    val value = Normalizer.normalize(input, Normalizer.Form.NFC).trim { it.isWhitespace() || it == '\uFEFF' }
    require(
        value.isNotEmpty() && value.codePointCount(0, value.length) <= 80 && !Regex("\\p{C}").containsMatchIn(value),
    )
    return value
}

internal fun isDisplayNameValid(input: String): Boolean =
    try {
        normalizedDisplayName(input)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
