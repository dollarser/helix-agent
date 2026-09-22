package com.helix.app.ui

internal data class ComposerAvailability(
    val input: Boolean = true,
    val delivery: Boolean = true,
    val attachments: Boolean = true,
) {
    fun canAttach(): Boolean = input && attachments
}
