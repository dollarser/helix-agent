package com.helix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Keep disabled actions discoverable without making them look enabled. */
@Composable
internal fun actionButtonBorder(
    enabled: Boolean,
    filled: Boolean = false,
): BorderStroke? =
    if (filled && enabled) {
        null
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.5f))
    }
