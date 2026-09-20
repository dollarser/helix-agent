package com.helix.core.storage.export

/** The application supplies its existing credential rules; storage must not export unchecked bodies. */
fun interface SessionExportSanitizer {
    fun sanitize(
        text: String,
        structured: Boolean,
    ): String
}
