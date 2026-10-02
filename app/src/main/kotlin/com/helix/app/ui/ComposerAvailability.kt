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
            !localOnly && !modelSelected -> R.string.chat_model_required_before_send
            !canDeliver(localOnly) -> deliveryReason ?: R.string.composer_temporarily_unavailable
            else -> null
        }

    /** Local controls do not create a model request; all actual sends require a selection. */
    fun canDeliver(localOnly: Boolean): Boolean = if (localOnly) localCommands else delivery && modelSelected
}
