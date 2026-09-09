package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal data class ProviderRowActions(
    val onTest: () -> Unit,
    val onEdit: (modelOverride: String?) -> Unit,
    val onDelete: () -> Unit,
    val onDeclareVision: (enabled: Boolean) -> Unit,
    val onManageAccount: () -> Unit,
)
