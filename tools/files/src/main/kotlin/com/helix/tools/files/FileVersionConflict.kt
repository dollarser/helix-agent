package com.helix.tools.files

import com.helix.core.workspace.PreconditionHashMismatch

/** Use the existing failed check, without another read or an overwrite/retry bypass. */
internal fun fileVersionConflict(
    reference: String,
    mismatch: PreconditionHashMismatch,
): String {
    val observed =
        mismatch.actualSha256.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: if (mismatch.actualSha256 == "missing") "missing" else "unknown (changed)"
    return "expectedSha256 precondition failed (hash mismatch): $reference; " +
        "expectedSha256=${mismatch.expected}, observedSha256=$observed. " +
        "Re-read the file and retry with a fresh expectedSha256."
}
