package com.helix.app.proot

/** Display data only: recovering an archive never dispatches its text or artifacts as tools. */
data class ProotRecoveredOutput(
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
    val files: List<ProotRecoveredFile>,
    val acknowledged: Boolean? = null,
)

data class ProotRecoveredFile(
    val path: String,
    val size: Long,
    val sha256: String,
)
