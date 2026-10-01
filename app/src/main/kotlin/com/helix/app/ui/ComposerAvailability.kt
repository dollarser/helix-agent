package com.helix.app.ui

internal data class ComposerAvailability(
    val input: Boolean = true,
    val delivery: Boolean = true,
    val attachments: Boolean = true,
    val localCommands: Boolean = true,
    val modelSelected: Boolean = true,
) {
    fun canAttach(): Boolean = input && attachments

    /** Local controls do not create a model request; all actual sends require a selection. */
    fun canDeliver(localOnly: Boolean): Boolean = if (localOnly) localCommands else delivery && modelSelected
}
