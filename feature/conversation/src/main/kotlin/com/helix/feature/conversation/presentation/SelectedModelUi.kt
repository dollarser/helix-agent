package com.helix.feature.conversation.presentation

/** Exact selected identity; presentation and capabilities come from current provider rows. */
data class SelectedModelUi(
    val providerId: String,
    val model: String,
)
