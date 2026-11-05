package com.helix.app.ui

import com.helix.app.R

internal data class ComposerAvailability(
    val input: Boolean = true,
    val delivery: Boolean = true,
    val attachments: Boolean = true,
    val localCommands: Boolean = true,
    val modelSelected: Boolean = true,
    val deliveryReason: Int? = null,
) {
    fun canAttach(): Boolean = input && attachments

    fun unavailableReason(
        localOnly: Boolean,
        commandPending: Boolean,
        permissionPending: Boolean,
    ): Int? =
        when {
            commandPending -> R.string.composer_mode_switching
            permissionPending -> R.string.composer_permission_saving
            !canAttempt(localOnly) -> deliveryReason ?: R.string.composer_temporarily_unavailable
            else -> null
        }

    /** Clicking can explain a missing model; all actual deliveries still require a selection. */
    fun canAttempt(localOnly: Boolean): Boolean = if (localOnly) localCommands else delivery

    fun canDeliver(localOnly: Boolean): Boolean = canAttempt(localOnly) && (localOnly || modelSelected)
}
