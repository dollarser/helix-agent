package com.helix.app.proot

/** A localized verification message plus the stable action state it represents. */
internal data class ProotVerificationNote(
    val text: String,
    val needsRebaseline: Boolean = false,
)
