package com.helix.app.files

/** Observed access, not a new authorization. Write support is an advertised hint, never a successful write test. */
data class WorkspaceAccessReport(
    val backend: String,
    val directoryReadable: Boolean,
    val writeAdvertised: Boolean,
    val directPath: Boolean,
)
