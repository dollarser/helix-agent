package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Decorative native mark. A two-color asset must never be flattened with a single Icon tint. */
@Composable
@Suppress("FunctionName")
internal fun HelixBrandMark(size: Dp = 44.dp) {
    Surface(
        modifier = Modifier.size(size).clearAndSetSemantics {},
        shape = RoundedCornerShape(size * 0.27f),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("H", style = MaterialTheme.typography.titleLarge)
        }
    }
}
