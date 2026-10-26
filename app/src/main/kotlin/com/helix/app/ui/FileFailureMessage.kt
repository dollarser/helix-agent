package com.helix.app.ui

import com.helix.app.R
import java.io.FileNotFoundException

/** Known actionable causes only. Exception text may contain paths or provider internals. */
internal fun fileFailureMessage(
    failure: Throwable,
    fallback: Int,
): Int =
    when (failure) {
        is FileNotFoundException -> R.string.files_file_unavailable
        is SecurityException, is java.nio.file.AccessDeniedException -> R.string.files_access_failed
        else -> fallback
    }
