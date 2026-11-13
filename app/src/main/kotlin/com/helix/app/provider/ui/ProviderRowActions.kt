package com.helix.app.provider.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal data class ProviderRowActions(
    val onTest: () -> Unit,
    val onEdit: (modelOverride: String?) -> Unit,
    val onDelete: () -> Unit,
    val onManageAccount: () -> Unit,
    val onDetectCapabilities: (String) -> Unit = {},
    val onContext: (String) -> Unit = {},
    val onTestModel: (String) -> Unit = {},
    val onUnload: () -> Unit = {},
    val onManageModels: () -> Unit = {},
)
