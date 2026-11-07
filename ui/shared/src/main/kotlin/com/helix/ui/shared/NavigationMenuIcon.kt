package com.helix.ui.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

@Composable
@Suppress("FunctionName")
fun NavigationMenuIcon() {
    val label = stringResource(R.string.chat_app_navigation)
    Text(
        text = "☰",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
    )
}
