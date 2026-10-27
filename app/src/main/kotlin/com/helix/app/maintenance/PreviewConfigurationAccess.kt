package com.helix.app.maintenance

/** Flavor-owned configuration inspection; absent when the capability is not installed. */
internal data class PreviewConfigurationAccess(
    val read: () -> List<String>,
    val valid: () -> Boolean,
    val clear: () -> Unit,
)
