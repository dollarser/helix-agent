package com.helix.app.application.runtime

/** Passive installation facts, not execution availability or permission; presentation owns labels. */
internal enum class ProotInstallationStatus {
    NOT_AVAILABLE,
    NOT_PREPARED,
    PREPARED,
    UPDATE_REQUIRED,
    DAMAGED,
    RESET_REQUIRED,
}
